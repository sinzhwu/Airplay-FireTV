package com.airplay.firetv.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtpInterleaved unit tests.
 *
 * WHY: AirPlay 2 sends video and audio over the same TCP socket using the
 * RTSP interleaved framing format ($ + channel + 2-byte length + payload).
 * We must correctly demux channel 0 (video RTP), channel 1 (video RTCP),
 * channel 2 (audio RTP), channel 3 (audio RTCP).
 *
 * HOW: We build synthetic interleaved frames in memory and feed them to
 * [RtpInterleaved.readLoop] via ByteArrayInputStream.  Callbacks are captured
 * with simple boolean flags.
 *
 * NOTE: [RtpPacket.parse] requires at least 12 bytes AND validates version bits.
 * [processVideoRtpFrame] requires a valid NAL type (1-23 or 28) in the payload.
 */
class RtpInterleavedTest {

    @Test
    fun `valid video RTP frame triggers callback`() {
        var received = false
        val frame = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val stream = buildInterleavedStream(buildInterleavedFrame(channel = 0, payload = frame))

        RtpInterleaved.readLoop(
            input = stream,
            videoCallback = { _, _ -> received = true },
            audioCallback = { _, _ -> fail("Audio should not be triggered") }
        )

        assertTrue("Video callback should have been triggered", received)
    }

    @Test
    fun `valid audio RTP frame triggers callback`() {
        var received = false
        val audioRtp = buildMinimalAudioRtpFrame(timestampRtp = 44100)
        val stream = buildInterleavedStream(buildInterleavedFrame(channel = 2, payload = audioRtp))

        RtpInterleaved.readLoop(
            input = stream,
            videoCallback = { _, _ -> fail("Video should not be triggered") },
            audioCallback = { _, _ -> received = true }
        )

        assertTrue("Audio callback should have been triggered", received)
    }

    @Test
    fun `RTCP packets on channel 1 are ignored`() {
        var videoCalled = false
        var audioCalled = false
        val rtcp = byteArrayOf(0x81.toByte(), 0xCA.toByte(), 0x00, 0x00) // minimal RTCP SR
        val stream = buildInterleavedStream(buildInterleavedFrame(channel = 1, payload = rtcp))

        RtpInterleaved.readLoop(
            input = stream,
            videoCallback = { _, _ -> videoCalled = true },
            audioCallback = { _, _ -> audioCalled = true }
        )

        assertFalse("Video callback should not be triggered", videoCalled)
        assertFalse("Audio callback should not be triggered", audioCalled)
    }

    @Test
    fun `empty stream returns immediately`() {
        val stream = ByteArrayInputStream(byteArrayOf())

        // Should not throw
        RtpInterleaved.readLoop(
            input = stream,
            videoCallback = { _, _ -> },
            audioCallback = { _, _ -> }
        )
    }

    @Test
    fun `garbage bytes before dollar sign are skipped`() {
        var received = false
        val frame = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val interleaved = buildInterleavedFrame(channel = 0, payload = frame)
        // Prepend garbage bytes
        val garbage = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val stream = ByteArrayInputStream(garbage + interleaved)

        RtpInterleaved.readLoop(
            input = stream,
            videoCallback = { _, _ -> received = true },
            audioCallback = { _, _ -> fail("Audio should not be triggered") }
        )

        assertTrue("Should skip garbage and find the video frame", received)
    }

    /**
     * Builds a minimal valid RTP packet (12-byte header + 1-byte payload).
     *
     * The payload contains NAL type 0x01 (non-IDR slice) so that
     * [processVideoRtpFrame] routes it to the callback as a single NAL unit.
     *
     * RTP header fields set:
     * - V=2, no padding, no extension, CSRC count = 0
     * - PT = 96 (H.264 dynamic payload)
     * - SSRC = 0x12345678 (must be non-zero for a valid packet)
     * - Sequence number = 0x0001
     */
    private fun buildMinimalVideoRtpFrame(timestampRtp90k: Long): ByteArray {
        val rtp = ByteArray(13) // 12-byte header + 1-byte NAL payload
        rtp[0] = 0x80.toByte() // V=2, no padding, no extension, CSRC=0
        rtp[1] = 0x60 // M=0, PT=96 (H.264)
        // Sequence number at bytes 2-3
        rtp[2] = 0x00
        rtp[3] = 0x01
        // Timestamp at bytes 4-7
        rtp[4] = ((timestampRtp90k shr 24) and 0xFF).toByte()
        rtp[5] = ((timestampRtp90k shr 16) and 0xFF).toByte()
        rtp[6] = ((timestampRtp90k shr 8) and 0xFF).toByte()
        rtp[7] = (timestampRtp90k and 0xFF).toByte()
        // SSRC at bytes 8-11
        rtp[8] = 0x12
        rtp[9] = 0x34
        rtp[10] = 0x56
        rtp[11] = 0x78
        // NAL payload: type 0x01 (non-IDR slice) — valid single NAL unit
        rtp[12] = 0x01
        return rtp
    }

    /**
     * Builds a minimal valid audio RTP packet (12-byte header).
     *
     * SSRC is set to a non-zero value so [RtpPacket.parse] accepts it.
     */
    private fun buildMinimalAudioRtpFrame(timestampRtp: Int): ByteArray {
        val rtp = ByteArray(12)
        rtp[0] = 0x80.toByte() // V=2
        rtp[1] = 0x60 // PT=96
        // Sequence number
        rtp[2] = 0x00
        rtp[3] = 0x01
        // Timestamp
        rtp[4] = ((timestampRtp shr 24) and 0xFF).toByte()
        rtp[5] = ((timestampRtp shr 16) and 0xFF).toByte()
        rtp[6] = ((timestampRtp shr 8) and 0xFF).toByte()
        rtp[7] = (timestampRtp and 0xFF).toByte()
        // SSRC
        rtp[8] = 0x12
        rtp[9] = 0x34
        rtp[10] = 0x56
        rtp[11] = 0x78
        return rtp
    }

    /**
     * Wraps a raw payload with the RTSP interleaved frame header.
     * Format: $ | channel (1 byte) | length (2 bytes, BE) | payload
     */
    private fun buildInterleavedFrame(channel: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            '$'.code.toByte(),
            channel.toByte(),
            (payload.size shr 8).toByte(),
            (payload.size and 0xFF).toByte()
        )
        return header + payload
    }

    private fun buildInterleavedStream(vararg frames: ByteArray): ByteArrayInputStream {
        val combined = frames.fold(byteArrayOf()) { acc, frame -> acc + frame }
        return ByteArrayInputStream(combined)
    }
}
