package com.veeshays.pulse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for every PupPlay-specific string, color seed, and
 * typographic choice. The future Brand + Visual Identity redesign must only
 * touch this file (plus res/ icon + splash) — never hunt through components.
 *
 * Mirrors the PC identity: name "PupPlay" (PopTune music · PupScreen video),
 * aura-violet default, same copy tone.
 */
object Brand {
    const val NAME = "PupPlay"
    const val TAGLINE = "Offline-first music & video"
    const val PACKAGE = "com.veeshays.pulse"
    const val NOTIFICATION_CHANNEL_ID = "pulse_playback"
    const val NOTIFICATION_CHANNEL_NAME = "Playback"
    const val PREFS_NAME = "pulse-android-v1"
    const val SCHEMA_VERSION = 1

    /** Visible section/action names. Pup-inspired, used sparingly — edit here to reword. */
    object Labels {
        const val MUSIC = "PopTune" // music section / player
        const val VIDEO = "PupScreen" // video section / player
        const val FETCH = "Fetch" // scan / import action
        const val FETCH_FILES = "Fetch Files"
        const val FETCH_FOLDER = "Fetch Folder"
        const val FETCH_MEDIA = "Fetch Media"
        const val PAW = "🐾" // subtle pup cue for the Fetch action only
    }

    /** Copy kept in one place so tone stays consistent (no scattered literals). */
    object Copy {
        const val NOTHING_PLAYING = "Nothing playing"
        const val ADD_TO_START = "Add music to get started"
        const val FILES_ON_DISK_UNTOUCHED = "Files on disk are untouched"
        const val LIBRARY_EMPTY = "Fetch your media to browse by location."
        const val RESUMED = "Resumed"
        const val FETCHING = "Fetching media…"
        const val UNSUPPORTED_HINT = "Unsupported format — skipped, library intact"
        const val MISSING_FILE = "File no longer available — removed from queue"
        const val PERMISSION_RATIONALE =
            "PupPlay needs media access to play your local files. Nothing leaves the device."

        /** Success notice once media has been fetched into the library. */
        fun fetched(count: Int): String = fetchResult(count, 0)

        /**
         * Accurate completion copy: names the media kinds actually found, so a
         * mixed library never misreports videos as "tracks" (or vice versa).
         */
        fun fetchResult(audio: Int, video: Int): String = when {
            audio == 0 && video == 0 -> "${Labels.PAW} Fetch complete — no media found"
            video == 0 -> "${Labels.PAW} Fetched $audio ${if (audio == 1) "song" else "songs"} into your library"
            audio == 0 -> "${Labels.PAW} Fetched $video ${if (video == 1) "video" else "videos"} into your library"
            else -> "${Labels.PAW} Fetched $audio ${if (audio == 1) "song" else "songs"} · $video ${if (video == 1) "video" else "videos"} into your library"
        }
    }

    /** Appearance/perf preference keys. Playback quality is never touched. */
    object Perf {
        const val NORMAL = "NORMAL" // default: full effects + normal tickers
        const val EFFICIENT = "EFFICIENT" // nap UI: static Sun wash, slower ticker
    }

    /** One-UI-friendly metrics: 48dp touch floor, 14dp Pulse radius (PC --radius). */
    object Metrics {
        val TouchMin = 48.dp
        val Radius = 14.dp
        val RadiusS = 9.dp
    }

    /** UI + lyric font stacks. Offline system fonts only (PC data-uifont ethos). */
    object Type {
        val Ui: FontFamily = FontFamily.Default
        val UiSizeBase = 14.sp
        val TitleBase = 20.sp
        val LyricBase = 16.sp
    }
}

/** PC palette role, ported 1:1 from style.css semantic variables. */
data class PulsePalette(
    val acc: Color,
    val acc2: Color,
    val acc3: Color,
    val txt: Color,
    val tx2: Color,
    val dim: Color,
    val panel: Color,
    val brd: Color,
    val sel: Color,
    val imp: Color,
)

/**
 * The five live PC themes (style.css). Retired palettes stay retired:
 * THEME_FALLBACK migrates them, exactly like PC app.js.
 */
enum class PulseThemeId(val displayName: String, val dark: PulsePalette, val light: PulsePalette) {
    AURA(
        "Aura",
        PulsePalette(
            acc = Color(0xFF7C6CFF), acc2 = Color(0xFFA78BFF), acc3 = Color(0xFF4A3FD4),
            txt = Color(0xFFECEBF4), tx2 = Color(0xFFC6C3DA), dim = Color(0xFFA3A0BB),
            panel = Color(0x0F7C6CFF), brd = Color(0x29A78BFF), sel = Color(0x297C6CFF),
            imp = Color(0xFFC9BFFF),
        ),
        PulsePalette(
            acc = Color(0xFF4A3FD4), acc2 = Color(0xFF7C6CFF), acc3 = Color(0xFF2E2680),
            txt = Color(0xFF14141C), tx2 = Color(0xFF3A3A4A), dim = Color(0xFF6B6B80),
            panel = Color(0x147C6CFF), brd = Color(0x337C6CFF), sel = Color(0x1F7C6CFF),
            imp = Color(0xFF4A3FD4),
        ),
    ),
    OCEAN(
        "Ocean",
        PulsePalette(
            acc = Color(0xFF0EA5E9), acc2 = Color(0xFF2DD4BF), acc3 = Color(0xFF1E40AF),
            txt = Color(0xFFE9F4F8), tx2 = Color(0xFFB9D4DE), dim = Color(0xFF8FA9B5),
            panel = Color(0x120EA5E9), brd = Color(0x2E2DD4BF), sel = Color(0x290EA5E9),
            imp = Color(0xFF8FD8F2),
        ),
        PulsePalette(
            acc = Color(0xFF0284C7), acc2 = Color(0xFF0D9488), acc3 = Color(0xFF1E3A8A),
            txt = Color(0xFF0B1B22), tx2 = Color(0xFF2C4A55), dim = Color(0xFF5B7A86),
            panel = Color(0x140EA5E9), brd = Color(0x330EA5E9), sel = Color(0x1F0EA5E9),
            imp = Color(0xFF0284C7),
        ),
    ),
    EMERALD(
        "Emerald",
        PulsePalette(
            acc = Color(0xFF19C37D), acc2 = Color(0xFF63DFA4), acc3 = Color(0xFF0B7A4E),
            txt = Color(0xFFEAF6EF), tx2 = Color(0xFFBCD8C6), dim = Color(0xFF8FAE9B),
            panel = Color(0x0F19C37D), brd = Color(0x2E63DFA4), sel = Color(0x2619C37D),
            imp = Color(0xFF8FE6B6),
        ),
        PulsePalette(
            acc = Color(0xFF0B7A4E), acc2 = Color(0xFF19C37D), acc3 = Color(0xFF06452C),
            txt = Color(0xFF0C1A13), tx2 = Color(0xFF2E4A3B), dim = Color(0xFF5F7F6C),
            panel = Color(0x1419C37D), brd = Color(0x3319C37D), sel = Color(0x1F19C37D),
            imp = Color(0xFF0B7A4E),
        ),
    ),
    EMBER(
        "Ember",
        PulsePalette(
            acc = Color(0xFFE79B1E), acc2 = Color(0xFFF4C95D), acc3 = Color(0xFF9A5B0B),
            txt = Color(0xFFF6F0E4), tx2 = Color(0xFFD9CBAA), dim = Color(0xFFAB9C7D),
            panel = Color(0x12E79B1E), brd = Color(0x33F4C95D), sel = Color(0x29E79B1E),
            imp = Color(0xFFF6D47E),
        ),
        PulsePalette(
            acc = Color(0xFF9A5B0B), acc2 = Color(0xFFE79B1E), acc3 = Color(0xFF5C3606),
            txt = Color(0xFF1D1508), tx2 = Color(0xFF4D3F22), dim = Color(0xFF7D6C4C),
            panel = Color(0x14E79B1E), brd = Color(0x33E79B1E), sel = Color(0x1FE79B1E),
            imp = Color(0xFF9A5B0B),
        ),
    ),
    CRIMSON(
        "Crimson",
        PulsePalette(
            acc = Color(0xFFFF4D6D), acc2 = Color(0xFFFF8FA3), acc3 = Color(0xFFB42348),
            txt = Color(0xFFF8ECEF), tx2 = Color(0xFFDFBCC4), dim = Color(0xFFB08E96),
            panel = Color(0x0FFF4D6D), brd = Color(0x33FF8FA3), sel = Color(0x29FF4D6D),
            imp = Color(0xFFFFA4B5),
        ),
        PulsePalette(
            acc = Color(0xFFB42348), acc2 = Color(0xFFFF4D6D), acc3 = Color(0xFF6E142C),
            txt = Color(0xFF1F0E13), tx2 = Color(0xFF4E2A33), dim = Color(0xFF80555E),
            panel = Color(0x14FF4D6D), brd = Color(0x33FF4D6D), sel = Color(0x1FFF4D6D),
            imp = Color(0xFFB42348),
        ),
    );

    companion object {
        /** PC app.js THEME_FALLBACK: retired names migrate, never crash. */
        fun fallback(raw: String?): PulseThemeId =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AURA
    }
}

/** PC finishes + glass, presentation-only on mobile. */
enum class PulseFinish { NEON, MATTE, ARTISAN, NATURAL, CYBER }
enum class PulseGlass { TRANSLUCENT, FROSTED, STAINED }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
