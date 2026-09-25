package com.veeshays.pulse

import com.veeshays.pulse.eq.PulseEq
import com.veeshays.pulse.models.PersistedState
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.resolveResumeMs
import com.veeshays.pulse.playback.startIndexOf
import com.veeshays.pulse.subs.Subs
import com.veeshays.pulse.tags.TagParser
import com.veeshays.pulse.ui.Brand
import com.veeshays.pulse.ui.PulseThemeId
import org.junit.Assert.*
import org.junit.Test

class TagParserTest {

    @Test fun stripLeadingNo_keepsBandNames() {
        // PC tags.js safe rules: Blink-182 / 30STM / UB40 untouched.
        assertEquals("Blink-182", TagParser.stripLeadingNo("Blink-182"))
        assertEquals("30 Seconds to Mars", TagParser.stripLeadingNo("30 Seconds to Mars"))
        assertEquals("UB40", TagParser.stripLeadingNo("UB40"))
        assertEquals("Miley Cyrus", TagParser.stripLeadingNo("22. Miley Cyrus"))
        assertEquals("Song", TagParser.stripLeadingNo("[04] Song"))
    }

    @Test fun fromFilename_splitsArtistTitle() {
        val r = TagParser.fromFilename("Adele - Hello.mp3")
        assertEquals("Hello", r.title)
        assertEquals("Adele", r.artist)
    }

    @Test fun fromFilename_trackNumberPrefix() {
        val r = TagParser.fromFilename("22 - Song.mp3")
        assertEquals("Song", r.title)
        assertEquals(22, r.track)
    }

    @Test fun parseId3v2Head_neverThrows_onGarbage() {
        val r = TagParser.parseId3v2Head(byteArrayOf(1, 2, 3, 4))
        assertEquals("", r.title)
        assertFalse(r.hasCover)
    }

    @Test fun parseId3v2Head_readsTit2Tpe1() {
        // Minimal ID3v2.3: "ID3" + ver(3,0) + flags(0) + size + TIT2("Hi") + TPE1("Yo").
        fun frame(id: String, text: String): ByteArray {
            val body = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            val n = body.size
            return id.toByteArray() + byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte(), 0, 0) + body
        }
        val frames = frame("TIT2", "Hi") + frame("TPE1", "Yo")
        val size = frames.size
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte()) + frames
        val r = TagParser.parseId3v2Head(head)
        assertEquals("Hi", r.title)
        assertEquals("Yo", r.artist)
    }
}

class SubsTest {
    @Test fun srt_parsesAndSeeksWithOffset() {
        val raw = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\n<b>World</b>\n"
        val cues = Subs.parseSrt(raw)
        assertEquals(2, cues.size)
        assertEquals("Hello", Subs.at(cues, 1500))
        assertEquals("World", Subs.at(cues, 3500)) // tags stripped
        assertEquals("World", Subs.at(cues, 3000, offsetMs = 500)) // +0.5s offset
        assertEquals("", Subs.at(cues, 2500))
    }

    @Test fun lang_detectedFromSuffix() {
        assertEquals("en", Subs.detectLang("Movie.2024.en.srt"))
        assertEquals("eng", Subs.detectLang("Show.S01E02.eng.vtt"))
    }
}

class PulseLogicTest {
    @Test fun themeFallback_migratesRetiredNames() {
        assertEquals(PulseThemeId.AURA, PulseThemeId.fallback("teddy")) // retired PC palette
        assertEquals(PulseThemeId.OCEAN, PulseThemeId.fallback("ocean"))
        assertEquals(PulseThemeId.AURA, PulseThemeId.fallback(null))
    }

    @Test fun chameleon_mapsGenres() {
        assertEquals("Hip-Hop › Trap", PulseEq.presetForGenre("Trap"))
        assertEquals("Rock › Classic", PulseEq.presetForGenre("Alternative Rock"))
        assertEquals("Flat", PulseEq.presetForGenre(null))
        assertEquals("Flat", PulseEq.presetForGenre("Classical"))
    }
}

class BrandLabelsTest {
    @Test fun sectionNames_matchDirection() {
        assertEquals("PopTune", Brand.Labels.MUSIC)
        assertEquals("PupScreen", Brand.Labels.VIDEO)
    }

    @Test fun fetchActions_useFetchWording() {
        assertEquals("Fetch", Brand.Labels.FETCH)
        for (label in listOf(Brand.Labels.FETCH_FILES, Brand.Labels.FETCH_FOLDER, Brand.Labels.FETCH_MEDIA)) {
            assertTrue("'$label' should start with Fetch", label.startsWith("Fetch"))
        }
    }

    @Test fun fetchCopy_centralizedAndNatural() {
        assertTrue(Brand.Copy.FETCHING.isNotBlank())
        val done = Brand.Copy.fetched(42)
        assertTrue(done.contains("42"))
        assertTrue(done.contains("library"))
    }

    @Test fun fetchResult_namesKindsAccurately() {
        assertTrue(Brand.Copy.fetchResult(1284, 0).contains("songs"))
        assertFalse(Brand.Copy.fetchResult(1284, 0).contains("video"))
        assertTrue(Brand.Copy.fetchResult(0, 84).contains("videos"))
        val mixed = Brand.Copy.fetchResult(1200, 84)
        assertTrue(mixed.contains("1200"))
        assertTrue(mixed.contains("84"))
        assertTrue(Brand.Copy.fetchResult(0, 0).contains("no media"))
        assertTrue(Brand.Copy.fetchResult(1, 1).contains("1 song"))
        assertTrue(Brand.Copy.fetchResult(1, 1).contains("1 video"))
    }
}

class QueueMediaTest {
    private fun track(key: String, durationMs: Long = 200_000L) =
        Track(key = key, title = key, artist = "a", durationMs = durationMs)

    @Test fun resumeApplied_onlyWhenGuardsPass() {
        val a = track("a")
        // 30s into 200s: resume point kept.
        assertEquals(30_000L, resolveResumeMs(a, mapOf("a" to 30_000L)))
        // 5s (< 8s guard): ignored, starts at 0.
        assertNull(resolveResumeMs(a, mapOf("a" to 5_000L)))
        // No bookmark: starts at 0.
        assertNull(resolveResumeMs(a, emptyMap()))
    }

    @Test fun nearEndBookmark_clearedToZero() {
        assertNull(resolveResumeMs(track("a"), mapOf("a" to 195_000L)))
    }

    @Test fun startIndex_neverNegative() {
        val tracks = listOf(track("a"), track("b"))
        assertEquals(1, startIndexOf(tracks, "b"))
        assertEquals(0, startIndexOf(tracks, "missing"))
        assertEquals(0, startIndexOf(tracks, null))
    }
}

class PerfModeTest {
    @Test fun default_isNormal() {
        assertEquals("NORMAL", PersistedState().perfMode)
    }

    @Test fun roundTrip_preservesEfficient() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = json.encodeToString(
            kotlinx.serialization.serializer<PersistedState>(),
            PersistedState().copy(perfMode = "EFFICIENT"),
        )
        val back = json.decodeFromString<PersistedState>(raw)
        assertEquals("EFFICIENT", back.perfMode)
        // Caps still enforced alongside the new field.
        assertTrue(back.positions.size <= 200)
    }
}
