package com.airplay.firetv.airplay

import org.junit.Assert.*
import org.junit.Test

/**
 * SpsBitReader unit tests.
 *
 * WHY: The video decoder needs to parse the H.264 SPS (Sequence Parameter Set)
 * NAL unit to extract width/height BEFORE creating the MediaCodec.  A bug in
 * bit-level parsing would cause the decoder to be configured with wrong
 * dimensions, leading to a black screen or crash.
 *
 * HOW: We build synthetic SPS bitstreams with a private [SpsBitWriter] helper
 * inside this test class.  This avoids hard-coding binary magic numbers and
 * makes the tests readable and maintainable.
 */
class VideoDecoderSpsTest {

    @Test
    fun `readBit returns single bits in order`() {
        // Byte: 0b1010_0000 → bits: 1, 0, 1, 0, 0, 0, 0, 0
        val reader = SpsBitReader(byteArrayOf(0xA0.toByte()))

        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
    }

    @Test
    fun `readBits returns multi-bit values`() {
        // Byte: 0b1010_0000 → first 4 bits = 0b1010 = 10
        val reader = SpsBitReader(byteArrayOf(0xA0.toByte()))

        assertEquals(10, reader.readBits(4))
    }

    @Test
    fun `readUE decodes zero`() {
        // Exp-Golomb: 1 → value 0
        val reader = SpsBitReader(byteArrayOf(0x80.toByte())) // 0b1_000_0000

        assertEquals(0, reader.readUE())
    }

    @Test
    fun `readUE decodes small value`() {
        // Exp-Golomb: 010 → value 1
        val reader = SpsBitReader(byteArrayOf(0x40.toByte())) // 0b010_00000

        assertEquals(1, reader.readUE())
    }

    @Test
    fun `readUE decodes large value`() {
        // Exp-Golomb: 0000101 → value 5 (leading zero count 6)
        val reader = SpsBitReader(byteArrayOf(0x05.toByte())) // 0b0000_0101

        assertEquals(5, reader.readUE())
    }

    @Test
    fun `parse returns correct resolution for 720p`() {
        // Build a synthetic SPS for 1280x720 baseline profile
        val sps = buildBaselineSps(
            profileIdc = 66, // Baseline
            levelIdc = 31,   // Level 3.1
            picWidth = 1280,
            picHeight = 720
        )

        val info = SpsBitReader.parse(sps)

        assertNotNull(info)
        assertEquals(1280, info!!.width)
        assertEquals(720, info.height)
    }

    @Test
    fun `parse returns correct resolution for 1080p`() {
        val sps = buildBaselineSps(
            profileIdc = 100, // High
            levelIdc = 40,    // Level 4.0
            picWidth = 1920,
            picHeight = 1080
        )

        val info = SpsBitReader.parse(sps)

        assertNotNull(info)
        assertEquals(1920, info!!.width)
        assertEquals(1080, info.height)
    }

    @Test
    fun `parse returns null for empty SPS`() {
        val info = SpsBitReader.parse(byteArrayOf())
        assertNull(info)
    }

    @Test
    fun `parse returns null for single-byte SPS`() {
        val info = SpsBitReader.parse(byteArrayOf(0x00))
        assertNull(info)
    }

    @Test
    fun `parse handles frame cropping`() {
        val sps = buildBaselineSps(
            profileIdc = 100,
            levelIdc = 40,
            picWidth = 1920,
            picHeight = 1080,
            frameCropLeft = 0,
            frameCropRight = 0,
            frameCropTop = 0,
            frameCropBottom = 0
        )

        val info = SpsBitReader.parse(sps)
        assertNotNull(info)
        assertEquals(1920, info!!.width)
        assertEquals(1080, info.height)
    }

    // ----------------------------------------------------------------------
    // Helpers: build synthetic SPS bitstreams for testing
    // ----------------------------------------------------------------------

    /**
     * Builds a minimal Baseline/High profile SPS NAL unit.
     * This is NOT a complete H.264 encoder — just enough for unit tests.
     */
    private fun buildBaselineSps(
        profileIdc: Int,
        levelIdc: Int,
        picWidth: Int,
        picHeight: Int,
        frameCropLeft: Int = 0,
        frameCropRight: Int = 0,
        frameCropTop: Int = 0,
        frameCropBottom: Int = 0
    ): ByteArray {
        val writer = SpsBitWriter()

        // NAL unit header
        writer.writeBits(1, 0)     // forbidden_zero_bit
        writer.writeBits(2, 3)     // nal_ref_idc = 3
        writer.writeBits(5, 7)     // nal_unit_type = 7 (SPS)

        // profile_idc, constraint_set flags, level_idc
        writer.writeBits(8, profileIdc)
        writer.writeBits(8, 0xE0)  // constraint_set flags
        writer.writeBits(8, levelIdc)

        // seq_parameter_set_id
        writer.writeUE(0)

        // For High profile (100), write profile-specific fields
        if (profileIdc == 100) {
            writer.writeUE(1)      // chroma_format_idc = 1 (4:2:0)
            writer.writeUE(0)      // bit_depth_luma_minus8
            writer.writeUE(0)      // bit_depth_chroma_minus8
            writer.writeBits(1, 0) // qpprime_y_zero_transform_bypass_flag
            writer.writeBits(1, 0) // seq_scaling_matrix_present_flag
        }

        // log2_max_frame_num_minus4
        writer.writeUE(0)
        // pic_order_cnt_type
        writer.writeUE(0)
        // log2_max_pic_order_cnt_lsb_minus4
        writer.writeUE(0)
        // max_num_ref_frames
        writer.writeUE(1)
        // gaps_in_frame_num_value_allowed_flag
        writer.writeBits(1, 0)

        // pic_width_in_mbs_minus1: picWidth / 16 - 1
        writer.writeUE(picWidth / 16 - 1)
        // pic_height_in_map_units_minus1: picHeight / 16 - 1
        writer.writeUE(picHeight / 16 - 1)

        // frame_mbs_only_flag = 1 (progressive)
        writer.writeBits(1, 1)
        // direct_8x8_inference_flag
        writer.writeBits(1, 1)

        // frame_cropping_flag
        val hasCrop = frameCropLeft != 0 || frameCropRight != 0 ||
                frameCropTop != 0 || frameCropBottom != 0
        writer.writeBits(1, if (hasCrop) 1 else 0)
        if (hasCrop) {
            writer.writeUE(frameCropLeft)
            writer.writeUE(frameCropRight)
            writer.writeUE(frameCropTop)
            writer.writeUE(frameCropBottom)
        }

        // vui_parameters_present_flag = 0 (simplified)
        writer.writeBits(1, 0)

        // RBSP trailing bits
        writer.writeBits(1, 1) // rbsp_stop_one_bit

        return writer.toByteArray()
    }

    /**
     * A minimal bit writer for building synthetic SPS NAL units in tests.
     */
    private class SpsBitWriter {
        private val bits = mutableListOf<Int>()

        fun writeBits(n: Int, value: Int) {
            for (i in n - 1 downTo 0) {
                bits.add((value shr i) and 1)
            }
        }

        fun writeUE(value: Int) {
            // Exp-Golomb unsigned encoding
            val codeNum = value
            if (codeNum == 0) {
                writeBits(1, 1)
                return
            }
            val leadingZeros = (32 - codeNum.countLeadingZeroBits()) - 1
            writeBits(leadingZeros, 0)
            writeBits(leadingZeros + 1, codeNum + 1)
        }

        fun toByteArray(): ByteArray {
            // Pad to byte boundary
            while (bits.size % 8 != 0) {
                bits.add(0)
            }
            val bytes = mutableListOf<Byte>()
            for (i in bits.indices step 8) {
                var b = 0
                for (j in 0 until 8) {
                    b = (b shl 1) or bits[i + j]
                }
                bytes.add(b.toByte())
            }
            return bytes.toByteArray()
        }
    }
}
