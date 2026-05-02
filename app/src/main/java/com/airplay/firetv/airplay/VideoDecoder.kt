package com.airplay.firetv.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import timber.log.Timber
import java.nio.ByteBuffer

class VideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var lastWidth = 0
    private var lastHeight = 0

    fun decode(nalUnit: ByteArray, ptsUs: Long) {
        if (nalUnit.size < 5) return

        val nalType = nalUnit[4].toInt() and 0x1F

        when (nalType) {
            7 -> { // SPS
                pendingSps = nalUnit
                val spsInfo = SpsBitReader.parse(nalUnit)
                if (spsInfo != null &&
                    (!isConfigured || spsInfo.width != lastWidth || spsInfo.height != lastHeight)
                ) {
                    Timber.d("SPS detected: ${spsInfo.width}x${spsInfo.height}, profile=${spsInfo.profileIdc}")
                    reconfigure(spsInfo)
                }
            }
            8 -> { // PPS
                pendingPps = nalUnit
            }
        }

        if (!isConfigured) {
            // Buffer NALs until SPS/PPS arrive
            return
        }

        feedToCodec(nalUnit, ptsUs)
    }

    private fun reconfigure(spsInfo: SpsInfo) {
        releaseCodec()

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                spsInfo.width,
                spsInfo.height
            )
            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                spsInfo.width * spsInfo.height * 2
            )
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 30)

            val sps = pendingSps
            val pps = pendingPps
            if (sps != null && pps != null) {
                val csd = ByteArray(sps.size + pps.size)
                System.arraycopy(sps, 0, csd, 0, sps.size)
                System.arraycopy(pps, 0, csd, sps.size, pps.size)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                Timber.d("Configured codec with SPS+PPS csd, size=${csd.size}")
            }

            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
            lastWidth = spsInfo.width
            lastHeight = spsInfo.height

            Timber.i("VideoDecoder configured: ${spsInfo.width}x${spsInfo.height}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to configure VideoDecoder")
            isConfigured = false
        }
    }

    private fun feedToCodec(nalUnit: ByteArray, ptsUs: Long) {
        val c = codec ?: return

        try {
            val inputBufferId = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputBufferId < 0) {
                Timber.w("No input buffer available, dropping frame")
                return
            }

            val inputBuffer = c.getInputBuffer(inputBufferId) ?: return
            inputBuffer.clear()
            inputBuffer.put(nalUnit)

            c.queueInputBuffer(inputBufferId, 0, nalUnit.size, ptsUs, 0)

            drainOutput()
        } catch (e: Exception) {
            Timber.e(e, "Error feeding frame to codec")
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferId = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputBufferId >= 0 -> {
                    c.releaseOutputBuffer(outputBufferId, true)
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = c.outputFormat
                    Timber.d("Output format changed: $outputFormat")
                }
                else -> break
            }
        }
    }

    fun release() {
        releaseCodec()
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
        pendingSps = null
        pendingPps = null
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 100_000L // 100ms
    }
}

data class SpsInfo(
    val width: Int,
    val height: Int,
    val profileIdc: Int,
    val levelIdc: Int
)

class SpsBitReader(private val data: ByteArray) {
    private var byteOffset = 0
    private var bitOffset = 0 // 0-7, MSB first

    private fun readBit(): Int {
        if (byteOffset >= data.size) return 0
        val bit = (data[byteOffset].toInt() shr (7 - bitOffset)) and 0x1
        bitOffset++
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        return bit
    }

    private fun readBits(n: Int): Int {
        var result = 0
        repeat(n) {
            result = (result shl 1) or readBit()
        }
        return result
    }

    private fun readUE(): Int {
        var leadingZeroBits = 0
        while (readBit() == 0 && leadingZeroBits < 32) {
            leadingZeroBits++
        }
        return if (leadingZeroBits >= 32) {
            Int.MAX_VALUE
        } else {
            (1 shl leadingZeroBits) - 1 + readBits(leadingZeroBits)
        }
    }

    private fun readSE(): Int {
        val codeNum = readUE()
        val value = (codeNum + 1) / 2
        return if (codeNum % 2 == 0) -value else value
    }

    private fun readBool(): Boolean = readBit() == 1

    private fun skipScalingList(size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                val deltaScale = readSE()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    companion object {
        fun parse(nalUnit: ByteArray): SpsInfo? {
            if (nalUnit.size < 5) return null

            val nalType = nalUnit[4].toInt() and 0x1F
            if (nalType != 7) return null

            val reader = SpsBitReader(nalUnit.copyOfRange(5, nalUnit.size))

            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint_set flags + reserved
            val levelIdc = reader.readBits(8)
            reader.readUE() // seq_parameter_set_id

            var chromaFormatIdc = 1 // default 4:2:0
            var separateColourPlaneFlag = false

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128 ||
                profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135
            ) {
                chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) {
                    separateColourPlaneFlag = reader.readBool()
                }
                reader.readUE() // bit_depth_luma_minus8
                reader.readUE() // bit_depth_chroma_minus8
                reader.readBool() // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresentFlag = reader.readBool()
                if (seqScalingMatrixPresentFlag) {
                    val matrixCount = if (chromaFormatIdc != 3) 8 else 12
                    repeat(matrixCount) {
                        val seqScalingListPresentFlag = reader.readBool()
                        if (seqScalingListPresentFlag) {
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            if (picOrderCntType == 0) {
                reader.readUE() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBool() // delta_pic_order_always_zero_flag
                reader.readSE() // offset_for_non_ref_pic
                reader.readSE() // offset_for_top_to_bottom_field
                val numRefFramesInPicOrderCntCycle = reader.readUE()
                repeat(numRefFramesInPicOrderCntCycle) {
                    reader.readSE() // offset_for_ref_frame
                }
            }

            reader.readUE() // max_num_ref_frames
            reader.readBool() // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBool()

            if (!frameMbsOnlyFlag) {
                reader.readBool() // mb_adaptive_frame_field_flag
            }

            reader.readBool() // direct_8x8_inference_flag
            val frameCroppingFlag = reader.readBool()

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0

            if (frameCroppingFlag) {
                cropLeft = reader.readUE()
                cropRight = reader.readUE()
                cropTop = reader.readUE()
                cropBottom = reader.readUE()
            }

            val picWidthInMbs = picWidthInMbsMinus1 + 1
            val picHeightInMapUnits = picHeightInMapUnitsMinus1 + 1

            var width = picWidthInMbs * 16
            var height = picHeightInMapUnits * 16

            if (!frameMbsOnlyFlag) {
                height *= 2
            }

            if (frameCroppingFlag) {
                val cropUnitX = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
                val cropUnitY = if (chromaFormatIdc == 1) 2 else 1
                if (!separateColourPlaneFlag || chromaFormatIdc == 3) {
                    width -= cropUnitX * (cropLeft + cropRight)
                    height -= cropUnitY * (cropTop + cropBottom)
                }
            }

            return SpsInfo(width, height, profileIdc, levelIdc)
        }
    }
}
