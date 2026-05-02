package com.airplay.firetv.airplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * AudioPlayer unit tests.
 *
 * WHY: AudioPlayer manages an Android AudioTrack instance.  AudioTrack is a
 * platform class and cannot be instantiated in a JVM unit test.  However, we
 * CAN test the pre-initialization safety (calling play/pause/release before
 * initialize must not crash) and the public API contract.
 *
 * HOW: We instantiate AudioPlayer but never call initialize(), then verify
 * that all operations fail gracefully rather than throwing NPE.
 */
class AudioPlayerTest {

    private lateinit var player: AudioPlayer

    @Before
    fun setup() {
        player = AudioPlayer()
    }

    @Test
    fun `play before initialize returns silently`() {
        // Must not throw
        player.play(ByteArray(1024), ptsUs = 0L)
    }

    @Test
    fun `pause before initialize returns silently`() {
        // Must not throw
        player.pause()
    }

    @Test
    fun `resume before initialize returns silently`() {
        // Must not throw
        player.resume()
    }

    @Test
    fun `release before initialize returns silently`() {
        // Must not throw
        player.release()
    }

    @Test
    fun `double release does not crash`() {
        player.release()
        player.release() // second release must be idempotent
    }

    @Test
    fun `play release play sequence does not crash`() {
        // Simulate lifecycle: play → release → (later) play again
        player.play(ByteArray(512), ptsUs = 0L)
        player.release()
        player.play(ByteArray(512), ptsUs = 1000L)
    }

    @Test
    fun `pause and resume before initialize do not crash`() {
        player.pause()
        player.resume()
        player.pause()
    }
}
