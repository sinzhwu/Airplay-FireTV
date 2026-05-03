package com.airplay.firetv.airplay

import org.junit.Assert.*
import org.junit.Test

/**
 * SdpParser unit tests.
 *
 * WHY: AirPlay clients send an SDP body in the ANNOUNCE request.  The parser
 * must extract video (H264) and audio (AAC-LC) parameters so the receiver can
 * configure MediaCodec later.  A malformed SDP must not crash the app.
 *
 * HOW: We keep real SDP fixtures in a companion object and assert on the
 * parsed data classes.  This tests the parser in isolation without any
 * Android framework classes.
 */
class SdpParserTest {

    @Test
    fun `valid video plus audio SDP parses correctly`() {
        val session = SdpParser.parse(SDP_VIDEO_AUDIO)

        assertNotNull(session.getVideoMedia())
        assertNotNull(session.getAudioMedia())
        assertEquals(2, session.medias.size)
    }

    @Test
    fun `valid video plus audio SDP parses audio params correctly`() {
        val session = SdpParser.parse(SDP_VIDEO_AUDIO)
        val audioMedia = session.getAudioMedia()!!
        val audioParams = SdpParser.parseAudioParams(audioMedia)!!

        assertEquals(44100, audioParams.sampleRate)
        assertEquals(2, audioParams.channelCount)
        assertNotNull(audioParams.audioSpecificConfig)
        assertEquals(2, audioParams.audioSpecificConfig!!.size)
        assertEquals(0x11.toByte(), audioParams.audioSpecificConfig!![0])
        assertEquals(0x90.toByte(), audioParams.audioSpecificConfig!![1])
    }

    @Test
    fun `audio-only SDP without video parses correctly`() {
        val session = SdpParser.parse(SDP_AUDIO_ONLY)

        assertNull(session.getVideoMedia())
        assertNotNull(session.getAudioMedia())
        assertEquals(1, session.medias.size)
    }

    @Test
    fun `empty SDP returns empty session`() {
        val session = SdpParser.parse("")

        assertTrue(session.medias.isEmpty())
        assertNull(session.getVideoMedia())
        assertNull(session.getAudioMedia())
    }

    @Test
    fun `SDP without media sections has empty medias`() {
        val noMedia = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=Test
        """.trimIndent()

        val session = SdpParser.parse(noMedia)

        assertTrue(session.medias.isEmpty())
    }

    @Test
    fun `hexStringToByteArray converts valid hex`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        val audioMedia = result.getAudioMedia()!!
        val audioParams = SdpParser.parseAudioParams(audioMedia)!!

        assertNotNull(audioParams.audioSpecificConfig)
        assertEquals(2, audioParams.audioSpecificConfig!!.size)
    }

    @Test
    fun `hexStringToByteArray rejects odd-length hex`() {
        // The parser is internal, but we can verify via a malformed SDP
        // config=119 would be odd-length and should result in null ASC
        val badConfigSdp = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=Test
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 mode=AAC-hbr;config=119;sizeLength=13
        """.trimIndent()

        val session = SdpParser.parse(badConfigSdp)
        val audioMedia = session.getAudioMedia()

        if (audioMedia != null) {
            val params = SdpParser.parseAudioParams(audioMedia)
            // Odd-length hex should fail to parse → null
            assertTrue(params == null || params.audioSpecificConfig == null)
        }
    }

    @Test
    fun `getH264FmtpParams extracts profile level id and sprop`() {
        val session = SdpParser.parse(SDP_VIDEO_AUDIO)
        val videoMedia = session.getVideoMedia()!!
        val fmtp = SdpParser.getH264FmtpParams(videoMedia)

        assertEquals("1", fmtp["packetization-mode"])
        assertEquals("640028", fmtp["profile-level-id"])
        assertNotNull(fmtp["sprop-parameter-sets"])
    }

    companion object {
        /**
         * Typical AirPlay video+audio SDP.  iOS sends something like this
         * in the ANNOUNCE RTSP request.
         */
        val SDP_VIDEO_AUDIO = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=AirPlay
            c=IN IP4 127.0.0.1
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z0LAKNoB7xLcBA==,aM48gA==
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 mode=AAC-hbr;config=1190;sizeLength=13;indexLength=3;indexDeltaLength=3
        """.trimIndent()

        /**
         * Audio-only SDP (e.g. music streaming without mirroring).
         */
        val SDP_AUDIO_ONLY = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=AirPlay Audio
            c=IN IP4 127.0.0.1
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 mode=AAC-hbr;config=1190;sizeLength=13;indexLength=3;indexDeltaLength=3
        """.trimIndent()
    }
}
