package com.veeshays.pulse

import com.veeshays.pulse.library.MediaLibrary
import com.veeshays.pulse.playback.TonePlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

/**
 * Rebuild-gate tests: the diagnostic tone is independent of ExoPlayer
 * (pure PCM math here; audibility is ears-on), and artwork identity is
 * keyed off the album id — never the track id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TonePlayerTest {

    @Test fun pcmBuffer_hasExpectedLengthAndRange() {
        val pcm = TonePlayer.pcmBuffer()
        assertEquals((TonePlayer.SAMPLE_RATE * TonePlayer.DURATION_MS) / 1000, pcm.size)
        assertEquals(0, TonePlayer.sampleAt(0).toInt())
        assertTrue(pcm.any { it > 1000 })
        assertTrue(pcm.any { it < -1000 })
        assertTrue(pcm.all { it in -Short.MAX_VALUE..Short.MAX_VALUE })
    }

    @Test fun pcmBuffer_isPeriodicAt440Hz() {
        // One full 440Hz period ≈ 100 samples at 44.1kHz; sample[0] ≈ sample[period].
        val period = (TonePlayer.SAMPLE_RATE / TonePlayer.FREQUENCY_HZ).toInt()
        val a = TonePlayer.sampleAt(1)
        val b = TonePlayer.sampleAt(1 + period)
        assertTrue("a=$a b=$b", kotlin.math.abs(a - b) < 2000)
    }

    @Test fun artwork_unknownAlbum_returnsNull() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val lib = MediaLibrary(app)
        assertNull(lib.artworkUri("content://media/external/audio/media/42", -1L, false))
    }

    @Test fun artwork_albumId_buildsAlbumUri() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val lib = MediaLibrary(app)
        val uri = lib.artworkUri("content://media/external/audio/media/42", 7L, false)
        assertNotNull(uri)
        // Keyed off the ALBUM id (7), never the track id (42).
        assertTrue("uri=$uri", uri.toString().endsWith("/7"))
        assertFalse("uri=$uri", uri.toString().endsWith("/42"))
    }

    @Test fun artwork_video_returnsTrackUri() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val lib = MediaLibrary(app)
        val key = "content://media/external/video/media/9"
        assertEquals(key, lib.artworkUri(key, -1L, true).toString())
    }
}
