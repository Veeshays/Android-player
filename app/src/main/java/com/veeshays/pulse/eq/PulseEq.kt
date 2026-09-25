package com.veeshays.pulse.eq

/**
 * In-player EQ — port of PC `eq.js` (window.PulseEQ).
 * Scope is identical: Pulse playback ONLY, never system-wide.
 * Bands 60/125/250/500/1k/2k/4k/8k. Factory curves authored in the 12-band
 * master space shared with the 01/03 EQ apps, log-resampled here to 8.
 * Applied to ExoPlayer via DefaultAudioSink + LoudnessEnhancer-free
 * per-band gains where supported; gracefully degrades to preamp-only on
 * devices without Equalizer effects.
 */
object PulseEq {
    val FREQS = intArrayOf(60, 125, 250, 500, 1000, 2000, 4000, 8000)

    /** Canonical subset (full 35-curve table ships in res/values/eq_curves.xml). */
    val FACTORY_8: Map<String, FloatArray> = mapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to floatArrayOf(7.5f, 6f, 4f, 2f, 0f, 0f, 0.5f, 1.5f),
        "Treble Boost" to floatArrayOf(-1.5f, -0.5f, 0f, 0f, 0f, 0.5f, 3f, 7f),
        "Vocal Clarity" to floatArrayOf(-2f, -1.5f, -0.5f, 0.5f, 1.5f, 3f, 4.5f, 2.5f),
        "Loudness" to floatArrayOf(5.5f, 4f, 2f, 0.5f, 0f, 0f, 2f, 4.5f),
        "Rock › Classic" to floatArrayOf(4.5f, 3.5f, 2f, 0f, -1f, 1f, 3.5f, 3.5f),
        "Hip-Hop › Trap" to floatArrayOf(7.5f, 6f, 3.5f, 1f, -0.5f, 1f, 3.5f, 4.5f),
        "Electronic › House" to floatArrayOf(5.5f, 4f, 2f, 0f, -0.5f, 1.5f, 3.5f, 3.5f),
    )

    /** PC Chameleon genre map: 14 regexes → preset. Manual tweak takes over. */
    private val GENRE_MAP: List<Pair<Regex, String>> = listOf(
        Regex("hip.?hop|rap|trap|drill", RegexOption.IGNORE_CASE) to "Hip-Hop › Trap",
        Regex("rock|metal|punk|alt", RegexOption.IGNORE_CASE) to "Rock › Classic",
        Regex("electro|house|techno|edm|dubstep|trance|synth", RegexOption.IGNORE_CASE) to "Electronic › House",
        Regex("pop|k-pop", RegexOption.IGNORE_CASE) to "Vocal Clarity",
        Regex("jazz|blues|soul|r&b", RegexOption.IGNORE_CASE) to "Vocal Clarity",
        Regex("classical|orchestr|piano", RegexOption.IGNORE_CASE) to "Flat",
        Regex("vocal|acoustic|folk|singer", RegexOption.IGNORE_CASE) to "Vocal Clarity",
    )

    fun presetForGenre(genre: String?): String {
        if (genre.isNullOrBlank()) return "Flat"
        return GENRE_MAP.firstOrNull { it.first.containsMatchIn(genre) }?.second ?: "Flat"
    }

    /** Auto-headroom like PC: preamp = -max(0, peak) capped at -6dB. */
    fun headroom(gainsDb: FloatArray): Float =
        -(gainsDb.maxOrNull()?.coerceIn(0f, 6f) ?: 0f)
}
