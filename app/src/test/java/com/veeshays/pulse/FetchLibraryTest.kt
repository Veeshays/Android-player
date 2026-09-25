package com.veeshays.pulse

import com.veeshays.pulse.library.ScanGuard
import com.veeshays.pulse.library.dedupeTracks
import com.veeshays.pulse.library.diffLibrary
import com.veeshays.pulse.models.Track
import org.junit.Assert.*
import org.junit.Test

/**
 * Fetch regression tests — all pure JVM, no device needed.
 * Identity is ALWAYS the stable content URI (Track.key); filenames and
 * titles are display data and must never create duplicates.
 */
class FetchLibraryTest {

    private fun track(key: String, title: String = key, artist: String = "a") =
        Track(key = key, title = title, artist = artist)

    @Test fun sameUriTwice_dedupesToOne() {
        val inList = listOf(track("uri://1", "A"), track("uri://1", "A"), track("uri://2", "B"))
        val out = dedupeTracks(inList)
        assertEquals(2, out.size)
        assertEquals("uri://1", out[0].key) // first wins, order kept
    }

    @Test fun sameFilenameDifferentUri_staysSeparate() {
        val inList = listOf(track("uri://1", "Same"), track("uri://2", "Same"))
        assertEquals(2, dedupeTracks(inList).size)
    }

    @Test fun repeatedFetch_reportsNoChanges() {
        val lib = listOf(track("uri://1"), track("uri://2"))
        val diff = diffLibrary(lib, lib.map { it.copy() })
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.updated.isEmpty())
        assertEquals(2, diff.unchanged)
    }

    @Test fun newUri_detectedAsAdded() {
        val diff = diffLibrary(listOf(track("uri://1")), listOf(track("uri://1"), track("uri://9")))
        assertEquals(listOf("uri://9"), diff.added.map { it.key })
        assertTrue(diff.removed.isEmpty())
    }

    @Test fun removedUri_detectedAsRemoved() {
        val diff = diffLibrary(
            listOf(track("uri://1"), track("uri://2")),
            listOf(track("uri://2")),
        )
        assertEquals(listOf("uri://1"), diff.removed.map { it.key })
        assertTrue(diff.added.isEmpty())
    }

    @Test fun metadataChange_detectedAsUpdated_notAddedRemoved() {
        val before = listOf(track("uri://1", "Old"))
        val after = listOf(track("uri://1", "New"))
        val diff = diffLibrary(before, after)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(listOf("uri://1"), diff.updated.map { it.key })
        assertEquals(0, diff.unchanged)
    }

    @Test fun blankMetadata_survivesDiff() {
        // Malformed rows become "Unknown" shells, never crashes/duplicates.
        val blank = Track(key = "uri://x", title = "", artist = "")
        val diff = diffLibrary(emptyList(), listOf(blank, blank.copy()))
        assertEquals(1, diff.added.size + diff.unchanged)
    }

    @Test fun scanGuard_preventsOverlap() {
        val guard = ScanGuard()
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire()) // second Fetch refuses while busy
        assertTrue(guard.isBusy)
        guard.release()
        assertTrue(guard.tryAcquire()) // released: Fetch works again
        guard.release()
    }
}
