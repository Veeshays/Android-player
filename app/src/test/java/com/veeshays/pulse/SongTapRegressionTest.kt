package com.veeshays.pulse

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.buildMediaItems
import com.veeshays.pulse.playback.probeReadable
import com.veeshays.pulse.playback.resolveIdentityToUri
import com.veeshays.pulse.playback.startIndexOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression tests for the "tap a song, silence at 00:00" failure path.
 *
 * The device report showed `media=pupplay://probe-tone` stuck active with
 * `pos=0 dur=0`: the tap never replaced the stale item with a readable
 * track. These tests pin the fixed contract with a REAL audio file
 * (never the diagnostic tone path):
 *  - a scanned-style track's URI must open + yield bytes via the same
 *    [probeReadable] gate the UI tap uses;
 *  - a playlist built from tapped tracks carries only playable URIs and
 *    can never contain a probe/stale identity;
 *  - durations propagate from scanner → MediaItem metadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongTapRegressionTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Copies the 5s WAV fixture so it plays the role of a scanned file. */
    private fun scannedAudioFile(): File {
        val out = File(app.cacheDir, "scanned_song.wav")
        out.writeBytes(javaClass.classLoader!!.getResource("probe_tone.wav")!!.readBytes())
        return out
    }

    @Test fun realAudioFile_passesReadabilityGateWithBytes() {
        val uri = Uri.fromFile(scannedAudioFile()).toString()
        val probe = probeReadable(app.contentResolver, uri)
        assertTrue("probe=$probe", probe.readable)
        assertTrue("probe=$probe", probe.bytesRead > 0)
        assertNull(probe.error)
    }

    @Test fun bogusContentUri_failsGateWithNamedError() {
        val probe = probeReadable(app.contentResolver, "content://media/external/audio/media/999999999")
        assertFalse(probe.readable)
        assertNotNull(probe.error)
        // The error names the cause — never a silent stall.
        assertTrue(probe.error!!.isNotBlank())
    }

    @Test fun tappedTracks_buildPlaylistWithOnlyPlayableUris() {
        val fileUri = Uri.fromFile(scannedAudioFile()).toString()
        val tracks = listOf(
            Track(
                key = fileUri, title = "Scanned Song", artist = "Some Artist",
                album = "Some Album", durationMs = 240_000L,
                displayName = "scanned_song.wav", albumId = 7L,
            ),
            Track(
                key = "content://media/external/audio/media/8890", title = "Second",
                artist = "A", durationMs = 180_000L, displayName = "second.mp3",
            ),
        )
        val items = buildMediaItems(tracks, emptyMap()) { null }
        assertEquals(2, items.size)
        items.forEach { item ->
            val playable = item.localConfiguration?.uri
            assertNotNull("item ${item.mediaId} has no playable URI", playable)
            assertTrue(
                "unplayable scheme in tap playlist: $playable",
                playable.toString().startsWith("content://") ||
                    playable.toString().startsWith("file://") ||
                    playable.toString().startsWith("android.resource://"),
            )
            assertFalse("stale probe identity in tap playlist", item.mediaId.contains("pupplay://"))
        }
        // Identity preserved: timeline keys match the scanned tracks.
        assertEquals(tracks.map { it.key }, items.map { it.mediaId })
        // Tap starts at the tapped track (queue replacement, not append).
        assertEquals(1, startIndexOf(tracks, tracks[1].key))
    }

    @Test fun staleProbeIdentity_canNeverResolve() {
        // The exact stuck item from the device report must be unresolvable:
        // even if it somehow reached the session guard, it is rejected.
        assertNull(resolveIdentityToUri("pupplay://probe-tone"))
    }

    @Test fun durations_propagateScannerToMediaItem() {
        val tracks = listOf(
            Track(key = "content://media/external/audio/media/1", title = "A", artist = "x", durationMs = 240_000L),
            Track(key = "content://media/external/audio/media/2", title = "B", artist = "y", durationMs = 0L),
        )
        val items = buildMediaItems(tracks, emptyMap()) { null }
        // Known scanner duration → notification/seek metadata.
        assertEquals(240_000L, items[0].mediaMetadata.durationMs)
        // Missing scanner duration stays unknown (null) — never a fake 0:00.
        // (ExoPlayer fills the real timeline duration from the extractor.)
        assertNull(items[1].mediaMetadata.durationMs)
    }
}
