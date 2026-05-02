package com.airplay.firetv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTrack.MODE_STREAM
import android.os.Build
import timber.log.Timber

/**
 * AudioTrack PCM 音频播放器
 *
 * 接收 AudioDecoder 解码后的 PCM 数据，通过 AudioTrack 进行播放。
 * 支持基本的音视频同步：通过 AudioTrack 的 presentationTimeUs 参数
 * 或者基于 presentation time 的 buffer 延迟控制。
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var isInitialized = false

    private var sampleRate = 44100
    private var channelCount = 2
    private var bytesPerFrame = 4 // 16-bit stereo = 4 bytes/frame

    /** 基础音视频同步：首帧时间戳基准 */
    private var firstPtsUs: Long = -1
    private var firstSystemTimeNs: Long = -1

    /** 是否启用简单同步 */
    private var avSyncEnabled = true

    /**
     * 初始化 AudioTrack
     *
     * @param sampleRate   采样率（Hz），通常为 44100 或 48000
     * @param channelCount 通道数，通常为 1（mono）或 2（stereo）
     */
    fun initialize(sampleRate: Int, channelCount: Int) {
        if (isInitialized && this.sampleRate == sampleRate && this.channelCount == channelCount) {
            Timber.d("AudioPlayer already initialized with same params")
            return
        }

        release()

        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.bytesPerFrame = channelCount * 2 // 16-bit = 2 bytes per sample

        try {
            val channelConfig = when (channelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> {
                    Timber.w("Unsupported channel count: $channelCount, defaulting to stereo")
                    AudioFormat.CHANNEL_OUT_STEREO
                }
            }

            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

            // 使用较大的 buffer 以减少 underrun
            val bufferSize = minBufferSize.coerceAtLeast(sampleRate * bytesPerFrame / 10) // at least 100ms

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(encoding)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferSize,
                    MODE_STREAM
                )
            }

            audioTrack = track
            track.play()
            isInitialized = true
            firstPtsUs = -1
            firstSystemTimeNs = -1

            Timber.i(
                "AudioPlayer initialized: %dHz, %dch, buffer=%d bytes, minBuffer=%d",
                sampleRate,
                channelCount,
                bufferSize,
                minBufferSize
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AudioPlayer")
            isInitialized = false
        }
    }

    /**
     * 播放 PCM 数据
     *
     * @param pcmData 16-bit PCM 数据（little-endian）
     * @param ptsUs   呈现时间戳（微秒），用于 A/V sync
     */
    fun play(pcmData: ByteArray, ptsUs: Long) {
        if (!isInitialized) {
            Timber.w("AudioPlayer not initialized, dropping PCM data")
            return
        }

        if (avSyncEnabled) {
            applySyncDelay(ptsUs)
        }

        val track = audioTrack ?: return

        try {
            var written = 0
            while (written < pcmData.size) {
                val result = track.write(pcmData, written, pcmData.size - written)
                if (result < 0) {
                    Timber.w("AudioTrack write error: $result")
                    break
                }
                written += result
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing to AudioTrack")
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        try {
            audioTrack?.pause()
            Timber.d("AudioPlayer paused")
        } catch (e: Exception) {
            Timber.w(e, "Error pausing AudioTrack")
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        try {
            audioTrack?.play()
            Timber.d("AudioPlayer resumed")
        } catch (e: Exception) {
            Timber.w(e, "Error resuming AudioTrack")
        }
    }

    /**
     * 释放播放器资源
     */
    fun release() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        isInitialized = false
        firstPtsUs = -1
        firstSystemTimeNs = -1
        Timber.d("AudioPlayer released")
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    /**
     * 应用简单的 A/V 同步延迟
     *
     * 基于 presentation timestamp 和系统时间的差异，
     * 在必要时 sleep 一段时间来对齐音视频。
     *
     * 注意：这是最简单的同步策略。更好的方案是使用 AudioTrack 的
     * setPresentationTime()（API 24+）或者基于音频硬件时钟的同步。
     */
    private fun applySyncDelay(ptsUs: Long) {
        if (firstPtsUs < 0) {
            firstPtsUs = ptsUs
            firstSystemTimeNs = System.nanoTime()
            return
        }

        val elapsedUs = (System.nanoTime() - firstSystemTimeNs) / 1000
        val targetPtsUs = firstPtsUs + elapsedUs
        val delayUs = ptsUs - targetPtsUs

        // 如果音频比预期快，稍微延迟一下
        if (delayUs > SYNC_THRESHOLD_US) {
            val sleepMs = (delayUs / 1000).coerceAtMost(MAX_SYNC_DELAY_MS)
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    companion object {
        /** 同步阈值：超过此值才进行 delay（微秒） */
        private const val SYNC_THRESHOLD_US = 20_000L // 20ms

        /** 最大同步延迟（毫秒） */
        private const val MAX_SYNC_DELAY_MS = 50L
    }
}
