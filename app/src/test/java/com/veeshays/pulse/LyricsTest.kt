package com.veeshays.pulse

import com.veeshays.pulse.lyrics.LrclibTrack
import com.veeshays.pulse.lyrics.currentLine
import com.veeshays.pulse.lyrics.lyricKey
import com.veeshays.pulse.lyrics.mapTrack
import com.veeshays.pulse.lyrics.parseLrc
import com.veeshays.pulse.lyrics.plainFromLrc
import org.junit.Assert.*
import org.junit.Test

/** LRCLIB parsing/mapping + cache-key behavior. Network itself stays on-device. */
class LyricsTest {

    @Test fun lrc_parsesStampsAndSorts() {
        val lines = parseLrc("[00:03.50]Second\n[00:01.00]First\n[ar:Someone]\n\n[00:02.5]Mid")
        assertEquals(3, lines.size)
        assertEquals(1000L, lines[0].ms)
        assertEquals("First", lines[0].text)
        assertEquals(2500L, lines[1].ms)
        assertEquals(3500L, lines[2].ms)
    }

    @Test fun lrc_multiStampAndCentiseconds() {
        val lines = parseLrc("[00:01.00][00:05.00]Chorus")
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 5000L), lines.map { it.ms })
        val ms = parseLrc("[01:02.34]X")[0].ms
        assertEquals(60_000L + 2_340L, ms)
    }

    @Test fun lrc_stripsKaraokeTags() {
        val lines = parseLrc("[00:01.00]Hel<00:01.20>lo")
        assertEquals("Hello", lines[0].text)
    }

    @Test fun lrc_blankAndHeaderOnly_yieldNothing() {
        assertTrue(parseLrc("[ar:X]\n[ti:Y]\n\n").isEmpty())
        assertTrue(parseLrc("").isEmpty())
    }

    @Test fun mapTrack_prefersSynced_fallsBackToPlain() {
        val both = mapTrack(LrclibTrack(plainLyrics = "la", syncedLyrics = "[00:01.00]la"))
        assertNotNull(both)
        assertEquals(1, both!!.synced.size)
        val plainOnly = mapTrack(LrclibTrack(plainLyrics = "la"))
        assertNotNull(plainOnly)
        assertTrue(plainOnly!!.synced.isEmpty())
        assertEquals("la", plainOnly.plain)
        assertNull(mapTrack(LrclibTrack()))
    }

    @Test fun plainFromLrc_joinsLines() {
        val lines = parseLrc("[00:01.00]a\n[00:02.00]b")
        assertEquals("a\nb", plainFromLrc(lines))
    }

    @Test fun currentLine_tracksPosition() {
        val lines = parseLrc("[00:01.00]a\n[00:05.00]b\n[00:10.00]c")
        assertEquals(-1, currentLine(lines, 0))
        assertEquals(0, currentLine(lines, 1000))
        assertEquals(1, currentLine(lines, 7000))
        assertEquals(2, currentLine(lines, 60_000))
    }

    @Test fun cacheKey_normalizes() {
        assertEquals(lyricKey(" Adele ", "HELLO"), lyricKey("adele", "hello"))
        assertTrue(lyricKey("a", "b").length <= 300)
    }
}
