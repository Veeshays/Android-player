package com.veeshays.pulse

import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.QueueManager
import org.junit.Assert.*
import org.junit.Test

private fun t(key: String) = Track(key = key, title = key, artist = "a")

class QueueManagerTest {

    @Test fun playView_setsQueueAndStartIndex() {
        val q = QueueManager()
        q.playView(listOf(t("a"), t("b"), t("c")), "b")
        assertEquals("b", q.currentKey)
        assertEquals(3, q.ui.value.keys.size)
    }

    @Test fun reorder_neverAutoPlays_andKeepsCurrent() {
        val q = QueueManager()
        q.playView(listOf(t("a"), t("b"), t("c")), "b")
        q.move(2, 0) // c,a,b — current stays "b"
        assertEquals("b", q.currentKey)
    }

    @Test fun removeCurrent_staysInRange_withoutAutoPlay() {
        val q = QueueManager()
        q.playView(listOf(t("a"), t("b"), t("c")), "c")
        q.removeAt(2)
        assertEquals("b", q.currentKey)
    }

    @Test fun repeatCycles_offAllOne() {
        val q = QueueManager()
        assertEquals(1, q.cycleRepeat())
        assertEquals(2, q.cycleRepeat())
        assertEquals(0, q.cycleRepeat())
    }

    @Test fun step_repeatAll_wraps() {
        val q = QueueManager()
        q.playView(listOf(t("a"), t("b")), "b")
        q.cycleRepeat() // all
        assertEquals("a", q.step(1))
    }

    @Test fun step_noRepeat_atEnd_returnsNull() {
        val q = QueueManager()
        q.playView(listOf(t("a"), t("b")), "b")
        assertNull(q.step(1))
    }

    @Test fun errorStreak_allowsTwoSkips_thenStops() {
        val q = QueueManager()
        assertTrue(q.reportError())
        assertTrue(q.reportError())
        assertFalse(q.reportError()) // third consecutive → stop, single message
    }

    @Test fun resumeGuards_matchPcConstants() {
        val q = QueueManager()
        assertFalse(q.shouldResume(5_000, 200_000)) // <8s → no bookmark
        assertTrue(q.shouldResume(30_000, 200_000))
        assertFalse(q.shouldResume(195_000, 200_000)) // <15s remaining → clear
        // Short media: proportional floors (PC resumeGuards parity)
        assertTrue(q.shouldResume(10_000, 40_000))
    }
}
