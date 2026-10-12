package com.veeshays.pulse

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
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
import java.io.ByteArrayInputStream
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

    private fun chainOf(e: Throwable?): String =
        generateSequence(e) { it.cause }.joinToString(" <- ") {
            "${it.javaClass.name}: ${it.message}"
        }

    @Test fun fixtureProvider_servesRealAssetFd() {
        val file = File(app.cacheDir, "fd_check.wav")
        file.writeBytes(javaClass.classLoader!!.getResource("probe_tone.wav")!!.readBytes())
        val uri = serveViaProvider(app, file, "audio/wav")
        assertEquals("audio/wav", app.contentResolver.getType(uri))
        val afd = app.contentResolver.openAssetFileDescriptor(uri, "r")
        assertNotNull("provider returned null fd for $uri", afd)
        assertTrue("fd unreadable", afd!!.createInputStream().read() >= 0)
        // NOTE: streams/fds deliberately never closed here — Robolectric's
        // FileDescriptor interceptor cannot reflect on JDK 17 internals
        // (InaccessibleObjectException on close); they die with the test JVM.
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

    @Test fun productionItem_contentScheme_decodesToReady() {
        // The failing device symptom is a MediaStore content:// URI stuck in
        // BUFFERING. This serves REAL audio bytes at a content:// URI from a
        // registered ContentProvider over a real file (the same
        // openAssetFileDescriptor path the production ContentResolverDataSource
        // uses) and requires READY + true duration. A Source error here
        // reproduces the device stall.
        val file = File(app.cacheDir, "shared_song.wav")
        file.writeBytes(javaClass.classLoader!!.getResource("probe_tone.wav")!!.readBytes())
        val uri = serveViaProvider(app, file, "audio/wav")
        assertEquals("content", uri.scheme)
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(
                pulseMediaItem(
                    key = uri.toString(),
                    title = "Scanned Song", artist = "Some Artist",
                    album = "Some Album", artwork = null,
                    durationMs = 5_000L, resumeMs = null,
                ),
            )
            assertEquals(uri, p.currentMediaItem?.localConfiguration?.uri)
            p.prepare()
            val ok = pumpFor(match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error chain=[${chainOf(probe.error)}]", probe.error)
            assertTrue("content:// item never left buffering, states=${probe.states}", ok)
            assertTrue("duration=${p.duration}", p.duration in 4500..5500)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    @Test fun mp3Extractor_selectsAudioMpegAndReadsSamples() {
        // The failing file is audio/mpeg. This proves the production
        // extractor stack (Mp3Extractor, as chosen by
        // DefaultMediaSourceFactory for .mp3 content) sniffs synthesized
        // silent MP3 frames and yields audio/mpeg samples — no codec stub
        // needed, so it is deterministic on JVM.
        val mp3 = silentMp3(frames = 40)
        val byteSource = ByteArrayDataSource(mp3)
        byteSource.open(DataSpec(Uri.EMPTY))
        val input = DefaultExtractorInput(byteSource, /* position= */ 0, mp3.size.toLong())
        val extractor = Mp3Extractor()
        assertTrue("mp3 frames not sniffed", extractor.sniff(input))
        var format: Format? = null
        var samples = 0
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(f: Format) { format = f }
                override fun sampleData(
                    input: DataReader, length: Int, allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val skipped = ByteArray(length.coerceAtMost(8192))
                    var remaining = length
                    while (remaining > 0) {
                        val n = try {
                            input.read(skipped, 0, remaining.coerceAtMost(skipped.size))
                        } catch (_: java.io.EOFException) {
                            break
                        }
                        if (n == C.RESULT_END_OF_INPUT) break
                        remaining -= n
                    }
                    return length - remaining
                }
                override fun sampleData(data: ParsableByteArray, limit: Int, sampleDataPart: Int) {
                    data.position = data.limit()
                }
                override fun sampleMetadata(
                    timeUs: Long, flags: Int, size: Int, offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) { samples++ }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        val holder = PositionHolder()
        var reads = 0
        while (reads++ < 400) {
            val r = extractor.read(input, holder)
            if (r == Extractor.RESULT_END_OF_INPUT) break
        }
        assertNotNull("no track format produced", format)
        assertEquals(MimeTypes.AUDIO_MPEG, format!!.sampleMimeType)
        assertTrue("no samples read (reads=$reads)", samples > 0)
    }

    @Test fun mp3ThroughProductionStack_reachesReady() {
        // Full production path for the failing MIME: synthesized MP3 bytes
        // at a content:// URI → pulseMediaItem → service ExoPlayer. Requires
        // READY with no error (duration needs no Xing header for this).
        val file = File(app.cacheDir, "shared_song.mp3")
        file.writeBytes(silentMp3(frames = 200))
        val uri = serveViaProvider(app, file, "audio/mpeg")
        val p = player()
        val probe = Probe()
        p.addListener(probe)
        try {
            p.setMediaItem(
                pulseMediaItem(
                    key = uri.toString(),
                    title = "Failing Song", artist = "Some Artist",
                    album = "Some Album", artwork = null,
                    durationMs = 0L, resumeMs = null,
                ),
            )
            p.prepare()
            val ok = pumpFor(timeoutMs = 30000, match = {
                p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
            })
            assertNull("player error chain=[${chainOf(probe.error)}]", probe.error)
            assertTrue("mp3 never left buffering, states=${probe.states}", ok)
        } finally {
            p.removeListener(probe)
            p.stop()
        }
    }

    companion object {
        /** Authority for the fixture provider serving real files at content:// URIs. */
        const val FIXTURE_AUTHORITY = "com.veeshays.pulse.testmedia"

        /**
         * Registers a ContentProvider serving [file] at
         * content://[FIXTURE_AUTHORITY]/[file.name] backed by a REAL
         * AssetFileDescriptor — the exact open path Media3's
         * ContentResolverDataSource takes for MediaStore URIs on device.
         */
        fun serveViaProvider(app: android.content.Context, file: File, mime: String): Uri {
            val provider = object : android.content.ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
                    val f = File(file.parent, uri.lastPathSegment ?: "")
                    return android.content.res.AssetFileDescriptor(
                        android.os.ParcelFileDescriptor.open(
                            f, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                        ),
                        0,
                        android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH,
                    )
                }
                override fun getType(uri: Uri): String = mime
                override fun query(
                    uri: Uri, projection: Array<String>?, selection: String?,
                    selectionArgs: Array<String>?, sortOrder: String?,
                ): android.database.Cursor? = null
                override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
                override fun update(
                    uri: Uri, values: android.content.ContentValues?,
                    selection: String?, selectionArgs: Array<String>?,
                ): Int = 0
            }
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                FIXTURE_AUTHORITY, provider,
            )
            // registerProviderInternal does not wire ProviderInfo, so the
            // resolver's authority check would reject every call — attach it.
            val info = android.content.pm.ProviderInfo().apply {
                authority = FIXTURE_AUTHORITY
                exported = true
                grantUriPermissions = true
            }
            provider.attachInfo(app, info)
            return Uri.parse("content://$FIXTURE_AUTHORITY/${file.name}")
        }
        /**
         * Synthesized silent MPEG-1 Layer III (128kbps, 44.1kHz stereo):
         * valid frame headers + zeroed side info (empty granules = digital
         * silence). [frames] × 1152 samples ≈ frames × 26.1ms of audio.
         */
        fun silentMp3(frames: Int): ByteArray {
            val out = ByteArray(417 * frames)
            var o = 0
            repeat(frames) {
                out[o++] = 0xFF.toByte()
                out[o++] = 0xFB.toByte() // MPEG-1, Layer III, no CRC
                out[o++] = 0x90.toByte() // 128kbps, 44.1kHz, no padding
                out[o++] = 0x00.toByte() // stereo
                repeat(32) { out[o++] = 0 } // side info: empty granules
                repeat(381) { out[o++] = 0 } // main data: silence
            }
            return out
        }
    }
}
