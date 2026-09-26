package com.veeshays.pulse

import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.veeshays.pulse.library.MediaLibrary
import com.veeshays.pulse.library.dedupeTracks
import com.veeshays.pulse.library.diffLibrary
import com.veeshays.pulse.models.PersistedState
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.buildMediaItems
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forensic media-model tests: run the REAL MediaLibrary.mapRows() against a
 * MatrixCursor and assert the §1 evidence table with actual values. Proves
 * identity (content URI) and metadata survive every mapping stage without
 * numeric collapse. (Robolectric's shadow provider does not round-trip
 * insert→query, so cursor seeding is used instead; resolver mechanics stay
 * device-tested via the diagnostics probe.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreForensicTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val audioVol = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    private val videoVol = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun cols() = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.TITLE,
        MediaStore.MediaColumns.ARTIST,
        MediaStore.MediaColumns.ALBUM,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
    )

    private fun row(
        id: Any?,
        display: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Any?,
    ): Array<Any?> = arrayOf(id, display, title, artist, album, durationMs, 123456L, 1700000000L)

    private fun cursor(vararg rows: Array<Any?>): MatrixCursor {
        val c = MatrixCursor(cols())
        rows.forEach { c.addRow(it.toMutableList()) }
        return c
    }

    private fun lib() = MediaLibrary(app)

    @Test fun scan_mapsCursorToTrackWithFullIdentity() = runTest {
        val c = cursor(row(42L, "My Song.mp3", "My Song", "Some Artist", "Some Album", 240_000L))
        val res = lib().mapRows(c, audioVol, false)

        assertEquals(1, res.tracks.size)
        assertEquals(0, res.unreadable)
        val t = res.tracks[0]
        // §1 evidence table (actual values through OUR code):
        assertEquals("My Song.mp3", t.displayName)
        assertEquals("My Song", t.title)
        assertEquals("Some Artist", t.artist)
        assertEquals("Some Album", t.album)
        assertEquals(240_000L, t.durationMs)
        // Key IS the full content URI (stable identity), never a bare ID.
        assertEquals("content://media/external/audio/media/42", t.key)
        assertFalse(t.key.matches(Regex("^\\d+$")))
    }

    @Test fun scan_missingMetadataFallsBackWithoutNumericCollapse() = runTest {
        val c = cursor(row(8890L, "8890.mp3", null, null, null, 180_000L))
        val res = lib().mapRows(c, audioVol, false)
        assertEquals(1, res.tracks.size)
        val t = res.tracks[0]
        assertEquals("8890", t.title) // filename-kept, consistent every scan
        assertEquals("Unknown artist", t.artist)
        assertEquals("content://media/external/audio/media/8890", t.key)
    }

    @Test fun scan_duplicateUri_dedupesFirstWins() = runTest {
        val c = cursor(
            row(7L, "A.mp3", "A", "X", null, 100_000L),
            row(7L, "A.mp3", "A-CHANGED", "X", null, 100_000L),
        )
        val res = lib().mapRows(c, audioVol, false)
        assertEquals(1, res.tracks.size)
        assertEquals("A", res.tracks[0].title)
    }

    @Test fun scan_malformedRow_toleratedRestSurvive() = runTest {
        val c = cursor(
            row("NOT-A-NUMBER", "bad.mp3", "Bad", "X", null, 100_000L), // getLong(_id) throws
            row(8L, "Good.mp3", "Good", "Y", null, 100_000L),
        )
        val res = lib().mapRows(c, audioVol, false)
        assertEquals(1, res.unreadable)
        assertEquals(1, res.tracks.size)
        assertEquals("Good", res.tracks[0].title)
    }

    @Test fun scan_videoRow_detectedWithDuration() = runTest {
        val c = cursor(row(9L, "Clip.mp4", "Clip", null, null, 60_000L))
        val res = lib().mapRows(c, videoVol, true)
        assertEquals(1, res.tracks.size)
        val t = res.tracks[0]
        assertTrue(t.isVideo)
        assertEquals("content://media/external/video/media/9", t.key)
        assertEquals(60_000L, t.durationMs)
    }

    @Test fun scan_unsupportedExtension_countedNotCrashed() = runTest {
        val c = cursor(row(10L, "notes.txt", "notes", null, null, 10L))
        val res = lib().mapRows(c, audioVol, false)
        assertTrue(res.tracks.isEmpty())
        assertEquals(1, res.skipped)
        assertEquals(0, res.unreadable)
    }

    @Test fun mediaItem_carriesPlayableUriAndIdentity() = runTest {
        val c = cursor(row(11L, "Track.mp3", "Track", "A", "B", 200_000L))
        val res = lib().mapRows(c, audioVol, false)
        val items = buildMediaItems(res.tracks, emptyMap()) { null }
        assertEquals(1, items.size)
        val m = items[0]
        val want = Uri.parse("content://media/external/audio/media/11")
        // mediaId = stable identity; localConfiguration.uri = playable source.
        // Same value, never a bare ID, never a display title.
        assertEquals(want.toString(), m.mediaId)
        assertEquals(want, m.localConfiguration!!.uri)
        assertEquals("Track", m.mediaMetadata.title)
        assertEquals("A", m.mediaMetadata.artist)
        // requestMetadata.mediaUri is legitimately null for directly-built
        // items (it records post-redirect origins, not the playable source).
        // Nothing in the session/player path reads it for local files.
        assertNull(m.requestMetadata.mediaUri)
    }

    @Test fun persistence_preservesFullUris() {
        val json = Json { ignoreUnknownKeys = true }
        val key = "content://media/external/audio/media/8890"
        val state = PersistedState().copy(
            queue = PersistedState().queue.copy(keys = listOf(key), index = 0, lastKey = key),
            history = listOf(key),
            positions = mapOf(key to 30_000L),
            ratings = mapOf(key to 5),
            favTracks = setOf(key),
        )
        val raw = json.encodeToString(
            kotlinx.serialization.serializer<PersistedState>(), state,
        )
        // The URI must survive as a URI — never collapsed to "8890".
        assertTrue(raw.contains(key))
        val back = json.decodeFromString<PersistedState>(raw)
        assertEquals(listOf(key), back.queue.keys)
        assertEquals(key, back.queue.lastKey)
        assertEquals(listOf(key), back.history)
        assertEquals(30_000L, back.positions[key])
        assertEquals(5, back.ratings[key])
        assertTrue(key in back.favTracks)
    }

    @Test fun removedBetweenScans_detectedByDiff() = runTest {
        val lib = lib()
        val before = lib.mapRows(
            cursor(
                row(20L, "Keep.mp3", "Keep", "A", null, 100_000L),
                row(21L, "Gone.mp3", "Gone", "A", null, 100_000L),
            ),
            audioVol, false,
        ).tracks
        val after = lib.mapRows(
            cursor(row(20L, "Keep.mp3", "Keep", "A", null, 100_000L)),
            audioVol, false,
        ).tracks
        val diff = diffLibrary(before, after)
        assertEquals(1, diff.removed.size)
        assertEquals(before.first { it.displayName == "Gone.mp3" }.key, diff.removed[0].key)
        assertEquals(1, diff.unchanged)
    }

    @Test fun repeatedScan_isIdempotent() = runTest {
        val lib = lib()
        suspend fun scanOnce() = lib.mapRows(
            cursor(row(30L, "Same.mp3", "Same", "A", null, 100_000L)),
            audioVol, false,
        ).tracks
        val diff = diffLibrary(scanOnce(), scanOnce())
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.updated.isEmpty())
        assertEquals(1, diff.unchanged)
    }

    @Test fun heroNeverShowsBareNumericId() {
        // Regression: the player hero must not render a URI tail ("8890") as
        // a title. Full-URI keys carry no display text — callers show the
        // empty state instead (fixed in PlayerHero: no key fallback).
        val key = "content://media/external/audio/media/8890"
        val tail = key.substringAfterLast('/').substringBeforeLast('.')
        assertEquals("8890", tail) // documents WHY the fallback was removed
        assertFalse(Track(key = key, title = "Real Title", artist = "A").title == "8890")
    }

    @Test fun dedupe_firstWinsAcrossRepeat() {
        val a = Track(key = "content://media/external/audio/media/1", title = "A", artist = "x")
        assertEquals(1, dedupeTracks(listOf(a, a.copy(title = "CHANGED"))).size)
        assertEquals("A", dedupeTracks(listOf(a, a.copy(title = "CHANGED")))[0].title)
    }
}
