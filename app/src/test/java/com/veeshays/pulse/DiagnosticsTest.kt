package com.veeshays.pulse

import androidx.media3.common.Player
import com.veeshays.pulse.playback.PlaybackConnection
import com.veeshays.pulse.playback.stallReport
import org.junit.Assert.*
import org.junit.Test

/** Transition history + error retention (pure JVM, no Android runtime). */
class DiagnosticsTest {

    @Test fun transitions_cappedNewestLast() {
        repeat(60) { PlaybackConnection.recordTransition("S$it") }
        val t = PlaybackConnection.transitions.value
        assertEquals(40, t.size)
        assertEquals("S59", t.last())
        assertEquals("S20", t.first())
        // Reset for other tests (object singleton).
        repeat(40) { PlaybackConnection.recordTransition("reset$it") }
        assertTrue(PlaybackConnection.transitions.value.all { it.startsWith("reset") })
    }

    @Test fun lastError_roundTrips() {
        PlaybackConnection.reportError("probe-msg")
        assertEquals("probe-msg", PlaybackConnection.lastError.value)
        PlaybackConnection.clearError()
        assertNull(PlaybackConnection.lastError.value)
    }

    @Test fun stall_frozenBuffering_reportsWithUri() {
        // The exact device symptom: BUFFERING, same episode, pos=0,
        // buffered=0 → loud message naming the URI.
        val msg = stallReport(
            Player.STATE_BUFFERING, true, 0L, 0L, 0L, 0L,
            "content://media/external/audio/media/87969",
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("content://media/external/audio/media/87969"))
    }

    @Test fun stall_progressMade_staysSilent() {
        assertNull(
            stallReport(Player.STATE_BUFFERING, true, 0L, 0L, 500L, 4096L, "content://x"),
        )
    }

    @Test fun stall_leftBuffering_staysSilent() {
        assertNull(
            stallReport(Player.STATE_READY, true, 0L, 0L, 0L, 0L, "content://x"),
        )
    }

    @Test fun stall_retiredEpisode_staysSilent() {
        assertNull(
            stallReport(Player.STATE_BUFFERING, false, 0L, 0L, 0L, 0L, "content://x"),
        )
    }
}
