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
 */
class RtpInterleavedTest {

    @Test
    fun `valid video RTP frame triggers callback`() {
        var received = false
        val frame = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val stream = buildInterleavedStream(buildInterleavedFrame(channel = 0, payload = frame))

        RtpInterleaved.readLoop(
            stream = stream,
            onVideoPacket = { _, _ -> received = true },
            onAudioPacket = { _, _ -> fail("Audio should not be triggered") }
        )

        assertTrue("Video callback should have been triggered", received)
    }

    @Test
    fun `valid audio RTP frame triggers callback`() {
        var received = false
        // Minimal 12-byte RTP header for audio
        val audioRtp = ByteArray(12) { 0x00 }
        audioRtp[0] = 0x80.toByte() // V=2
        val stream = buildInterleavedStream(buildInterleavedFrame(channel = 2, payload = audioRtp))

        RtpInterleaved.readLoop(
            stream = stream,
            onVideoPacket = { _, _ -> fail("Video should not be triggered") },
            onAudioPacket = { _, _ -> received = true }
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
            stream = stream,
            onVideoPacket = { _, _ -> videoCalled = true },
            onAudioPacket = { _, _ -> audioCalled = true }
        )

        assertFalse("Video callback should not be triggered", videoCalled)
        assertFalse("Audio callback should not be triggered", audioCalled)
    }

    @Test
    fun `empty stream returns immediately`() {
        val stream = ByteArrayInputStream(byteArrayOf())

        // Should not throw
        RtpInterleaved.readLoop(
            stream = stream,
            onVideoPacket = { _, _ -> },
            onAudioPacket = { _, _ -> }
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
            stream = stream,
            onVideoPacket = { _, _ -> received = true },
            onAudioPacket = { _, _ -> fail("Audio should not be triggered") }
        )

        assertTrue("Should skip garbage and find the video frame", received)
    }

    /**
     * Builds a minimal RTP packet (12-byte header) with the given 90kHz timestamp.
     */
    private fun buildMinimalVideoRtpFrame(timestampRtp90k: Long): ByteArray {
        val rtp = ByteArray(12)
        rtp[0] = 0x80.toByte() // V=2, no padding, no extension
        rtp[1] = 0x60 // PT=96
        // Timestamp at bytes 4-7
        rtp[4] = ((timestampRtp90k shr 24) and 0xFF).toByte()
        rtp[5] = ((timestampRtp90k shr 16) and 0xFF).toByte()
        rtp[6] = ((timestampRtp90k shr 8) and 0xFF).toByte()
        rtp[7] = (timestampRtp90k and 0xFF).toByte()
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
