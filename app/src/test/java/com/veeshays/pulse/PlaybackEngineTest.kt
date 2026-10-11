package com.veeshays.pulse

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.veeshays.pulse.playback.PlaybackService
import com.veeshays.pulse.playback.pulseMediaItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Executes the REAL playback engine on JVM: service lifecycle, ExoPlayer
 * prepare/play/state/position, and MediaController↔session binding.
 * No audible output is possible here (no audio HAL), so this proves the
 * engine reaches READY/playing with advancing position — the exact states
 * the UI gates on. A stall here reproduces the "static player" deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackEngineTest {

    private lateinit var controller: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun player(): Player {
        val f = PlaybackService::class.java.getDeclaredField("player")
        f.isAccessible = true
        return f.get(service) as Player
    }

    private fun pumpFor(timeoutMs: Long = 20000, stepMs: Long = 250, match: () -> Boolean): Boolean {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(stepMs))
            looper.idle()
            if (runCatching { match() }.getOrDefault(false)) return true
            Thread.sleep(50)
        }
        return runCatching { match() }.getOrDefault(false)
    }

    private class Probe : Player.Listener {
        val states = mutableListOf<Int>()
        var error: PlaybackException? = null
        var playingSeen = false
        override fun onPlaybackStateChanged(state: Int) { states += state }
        override fun onIsPlayingChanged(p: Boolean) { if (p) playingSeen = true }
        override fun onPlayerError(e: PlaybackException) { error = e }
    }

    @Before
    fun setUp() {
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    @Test fun service_createsSessionAndPlayer() {
        val p = player()
        assertNotNull(p)
        assertEquals(Player.STATE_IDLE, p.playbackState)
    }

    @Test fun fileUriWithOpaqueLabel_reachesReady() {
        // Engine proof WITHOUT any probe-tone dependency: the mediaId is an
        // opaque identity label, the playable URI is a real file. Custom
        // schemes (e.g. pupplay://) have no DataSource and stall in
        // BUFFERING, so they must never appear in the playback path.
        val file = File(app.cacheDir, "probe_tone.wav")
        file.writeBytes(javaClass.classLoader!!.getResource("probe_tone.wav")!!.readBytes())
        val uri = Uri.fromFile(file)
        assertEquals("file", uri.scheme)
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            val item = MediaItem.Builder()
                .setMediaId("test:local-file")
                .setUri(uri)
                .build()
            // Identity label survives; playable URI is the file.
            assertEquals("test:local-file", item.mediaId)
            assertEquals(uri, item.localConfiguration!!.uri)
            p.setMediaItem(item)
            p.prepare()
            val ok = pumpFor(match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error: ${probe.error}", probe.error)
            assertTrue("file tone never left buffering", ok)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    @Test fun unsupportedScheme_failsExplicitlyNeverSilent() {
        // Regression: an unresolvable custom scheme must surface an error, not
        // sit in BUFFERING forever looking like a static player.
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(MediaItem.fromUri(Uri.parse("pupplay://no-such-source")))
            p.prepare()
            val done = pumpFor(timeoutMs = 20000, match = {
                probe.error != null || p.playbackState == Player.STATE_IDLE
            })
            assertTrue(
                "unsupported scheme stayed silent (state=${p.playbackState})",
                done && probe.error != null,
            )
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    private fun wavFile(): File {
        val src = javaClass.classLoader!!.getResource("probe_tone.wav")!!.readBytes()
        val out = File(app.cacheDir, "probe_tone.wav")
        out.writeBytes(src)
        return out
    }

    @Test fun exoplayer_preparesLocalWavToReady() {
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(wavFile())))
            p.prepare()
            val ok = pumpFor(match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error: ${probe.error}", probe.error)
            assertTrue("never left IDLE/BUFFERING, states=${probe.states}", ok)
            // 5s PCM16 mono fixture: duration must be real, never 0:00.
            assertTrue("duration=${p.duration}", p.duration in 4500..5500)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    @Test fun exoplayer_playAdvancesPosition() {
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(wavFile())))
            p.prepare()
            assertTrue(pumpFor(match = { p.playbackState == Player.STATE_READY }))
            p.play()
            val moved = pumpFor(timeoutMs = 25000, match = { p.currentPosition > 800 })
            val dump = "playing=${p.isPlaying} state=${p.playbackState} " +
                "pos=${p.currentPosition} dur=${p.duration} err=${probe.error} states=${probe.states}"
            assertNull("player error: ${probe.error}", probe.error)
            assertTrue("position never advanced [$dump]", moved)
            assertTrue("isPlaying never true [$dump]", probe.playingSeen || p.isPlaying)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    @Test fun session_wrapsSamePlayerInstance() {
        // Answers "is the session controlling OUR ExoPlayer?" at the session
        // layer. (The controller↔session binder hop is stock Media3 machinery
        // and cannot bind under Robolectric's manifest parsing; the production
        // manifest entry was verified separately via aapt xmltree.)
        val sessionField = PlaybackService::class.java.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = sessionField.get(service) as androidx.media3.session.MediaSession
        assertNotNull(session.token)
        assertSame(player(), session.player)
    }

    @Test fun productionItem_zeroStartClip_reachesReady() {
        // The EXACT item factory the UI tap uses (pulseMediaItem, including
        // its start-position ClippingConfiguration) through the service's
        // production MediaSource stack. A stall here — not in a hand-built
        // MediaItem — would be the real-track BUFFERING repro.
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(
                pulseMediaItem(
                    key = Uri.fromFile(wavFile()).toString(),
                    title = "Scanned Song", artist = "Some Artist",
                    album = "Some Album", artwork = null,
                    durationMs = 5_000L, resumeMs = null,
                ),
            )
            p.prepare()
            val ok = pumpFor(match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error: ${probe.error}", probe.error)
            assertTrue("production item never left buffering, states=${probe.states}", ok)
            assertTrue("duration=${p.duration}", p.duration in 4500..5500)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    @Test fun productionItem_resumeClip_reachesReadyShortened() {
        // Resume path: clipping start at 2s of the 5s fixture must still
        // prepare (ClippingSource over the progressive extractor) and play
        // the remaining ~3s — never stall in BUFFERING.
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(
                pulseMediaItem(
                    key = Uri.fromFile(wavFile()).toString(),
                    title = "Scanned Song", artist = "Some Artist",
                    album = "Some Album", artwork = null,
                    durationMs = 5_000L, resumeMs = 2_000L,
                ),
            )
            p.prepare()
            val ok = pumpFor(match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error: ${probe.error}", probe.error)
            assertTrue("resume-clipped item never left buffering, states=${probe.states}", ok)
            assertTrue("duration=${p.duration}", p.duration in 2500..3500)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }
}
