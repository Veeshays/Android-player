package com.veeshays.pulse

import com.veeshays.pulse.playback.PlaybackConnection
import org.junit.Assert.*
import org.junit.Test

/** Transition history + error retention (pure JVM, no Android runtime). */
class DiagnosticsTest {

    @Test fun transitions_cappedNewestLast() {
        repeat(20) { PlaybackConnection.recordTransition("S$it") }
        val t = PlaybackConnection.transitions.value
        assertEquals(12, t.size)
        assertEquals("S19", t.last())
        assertEquals("S8", t.first())
        // Reset for other tests (object singleton).
        repeat(12) { PlaybackConnection.recordTransition("reset$it") }
        assertTrue(PlaybackConnection.transitions.value.all { it.startsWith("reset") })
    }

    @Test fun lastError_roundTrips() {
        PlaybackConnection.reportError("probe-msg")
        assertEquals("probe-msg", PlaybackConnection.lastError.value)
        PlaybackConnection.clearError()
        assertNull(PlaybackConnection.lastError.value)
    }
}
