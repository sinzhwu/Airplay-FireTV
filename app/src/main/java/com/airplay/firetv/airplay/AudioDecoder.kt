package com.airplay.firetv.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * AAC-LC 音频硬件解码器（MediaCodec）
 *
 * AirPlay 音频流通过 RTP/AVP 传输，payload type 通常为 96，编码为 AAC-LC。
 * SDP 中通过 fmtp 的 config= 参数提供 AudioSpecificConfig（ASC），
 * 用于初始化 MediaCodec 的 csd-0。
 *
 * RTP payload 格式为 RFC 3640 模式（AudioMuxElement with AU-headers），
 * 需要剥离 AU-header 后再送入解码器。
 */
class AudioDecoder {

    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var audioParams: AudioParams? = null

    /** PCM 数据输出回调：(pcmData, presentationTimeUs) */
    private var pcmCallback: ((ByteArray, Long) -> Unit)? = null

    /** 是否已经开始播放（用于丢弃首帧前的缓冲数据） */
    private var hasReceivedRecord = false

    /**
     * 设置解码后的 PCM 数据回调
     */
    fun setCallback(callback: (ByteArray, Long) -> Unit) {
        pcmCallback = callback
    }

    /**
     * 初始化解码器
     *
     * @param params 从 SDP 解析出的音频参数（含 AudioSpecificConfig）
     */
    fun initialize(params: AudioParams) {
        if (isConfigured && audioParams == params) {
            Timber.d("AudioDecoder already configured with same params")
            return
        }

        releaseCodec()
        audioParams = params

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                params.sampleRate,
                params.channelCount
            )

            // csd-0: AudioSpecificConfig（2字节）
            // 例如 0x12 0x10 表示 AAC-LC, 44100Hz, mono
            params.audioSpecificConfig?.let { asc ->
                format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                Timber.d("Set csd-0 with ASC: ${asc.joinToString(" ") { "0x%02X".format(it) }}")
            }

            // AAC-LC max bitrate estimate: ~256kbps per channel
            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                16 * 1024 // 16KB max per AAC frame, generous
            )

            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            newCodec.configure(format, null, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
            hasReceivedRecord = false

            Timber.i(
                "AudioDecoder configured: %dHz, %dch, mime=%s",
                params.sampleRate,
                params.channelCount,
                MediaFormat.MIMETYPE_AUDIO_AAC
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to configure AudioDecoder")
            isConfigured = false
        }
    }

    /**
     * 标记 RECORD 已收到，可以开始解码输出
     * 在 AirPlay 中，客户端发送 RECORD 后才开始实际传输音频数据
     */
    fun onStreamingStarted() {
        hasReceivedRecord = true
        Timber.d("AudioDecoder: streaming started, will output PCM")
    }

    /**
     * 解码 AAC 帧
     *
     * @param aacFrame RTP payload（含 AU-header 的 AudioMuxElement）
     * @param ptsUs    呈现时间戳（微秒）
     */
    fun decode(aacFrame: ByteArray, ptsUs: Long) {
        if (!isConfigured) {
            Timber.w("AudioDecoder not configured, dropping frame")
            return
        }

        // 剥离 AU-header，获取 raw AAC frame
        val rawAac = stripAuHeader(aacFrame) ?: return

        feedToCodec(rawAac, ptsUs)
    }

    /**
     * 释放解码器资源
     */
    fun release() {
        releaseCodec()
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    /**
     * 剥离 RFC 3640 AudioMuxElement 中的 AU-header
     *
     * AirPlay AAC RTP payload 格式：
     *   [AU-headers-length (16 bits)] [AU-header (16 bits each)] [AU-data]
     *
     * AU-header 16-bit 格式：
     *   bits 0-12: AU-size (13 bits)
     *   bit  13:   AU-Index / AU-Index-delta (1 bit)
     *   bits 14-15: CTS-flag (2 bits, usually 0)
     *
     * 对于 AirPlay，通常只有 1 个 AU，AU-header 长度为 16 bits (2 bytes)，
     * AU-header 本身也是 16 bits (2 bytes)。
     *
     * @return raw AAC frame (ADTS 或 raw AAC)，失败返回 null
     */
    private fun stripAuHeader(aacFrame: ByteArray): ByteArray? {
        if (aacFrame.size < 4) {
            Timber.w("AAC frame too small: ${aacFrame.size}")
            return null
        }

        // AU-headers-length: big-endian 16-bit, in bits
        val auHeadersLengthBits = ((aacFrame[0].toInt() and 0xFF) shl 8) or
                (aacFrame[1].toInt() and 0xFF)
        val auHeadersLengthBytes = (auHeadersLengthBits + 7) / 8

        // 检查总长度
        if (aacFrame.size < 2 + auHeadersLengthBytes) {
            Timber.w("AAC frame too small for AU headers")
            return null
        }

        // 通常 AirPlay 只有 1 个 AU，header 占 16 bits
        // 跳过 AU-headers-length (2 bytes) + AU-headers
        val dataStart = 2 + auHeadersLengthBytes

        if (dataStart >= aacFrame.size) {
            Timber.w("No AAC data after AU headers")
            return null
        }

        return aacFrame.copyOfRange(dataStart, aacFrame.size)
    }

    private fun feedToCodec(rawAac: ByteArray, ptsUs: Long) {
        val c = codec ?: return

        try {
            val inputBufferId = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputBufferId < 0) {
                Timber.w("No audio input buffer available, dropping frame")
                return
            }

            val inputBuffer = c.getInputBuffer(inputBufferId) ?: return
            inputBuffer.clear()
            inputBuffer.put(rawAac)

            c.queueInputBuffer(inputBufferId, 0, rawAac.size, ptsUs, 0)

            drainOutput()
        } catch (e: Exception) {
            Timber.e(e, "Error feeding audio frame to codec")
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferId = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputBufferId >= 0 -> {
                    val outputBuffer = c.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null && hasReceivedRecord) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmData)

                        pcmCallback?.invoke(pcmData, bufferInfo.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outputBufferId, false)
                }

                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = c.outputFormat
                    Timber.d("Audio output format changed: $outputFormat")
                }

                else -> break
            }
        }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        isConfigured = false
        audioParams = null
        hasReceivedRecord = false
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 50_000L // 50ms
    }
}
