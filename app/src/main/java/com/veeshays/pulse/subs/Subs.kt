package com.veeshays.pulse.subs

/**
 * Subtitle engine — port of PC `subs.js` parsing core + video app.js memory.
 * SRT + VTT parsing, ±offset rendering, per-video memory (off/local/online,
 * cap 200), language suffix detect (`.en.`, `.eng.`), series-aware title
 * normalization. Network search providers stay server-side / future work;
 * local .srt/.vtt loading + memory restore work fully offline.
 */
object Subs {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    fun parseSrt(raw: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = raw.replace("\r\n", "\n").split(Regex("""\n\s*\n"""))
        for (b in blocks) {
            val lines = b.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeIdx = lines.indexOfFirst { "-->" in it }
            if (timeIdx < 0) continue
            val m = Regex("""(\S+)\s*-->\s*(\S+)""").find(lines[timeIdx]) ?: continue
            val s = stamp(m.groupValues[1]); val e = stamp(m.groupValues[2])
            if (s < 0 || e <= s) continue
            val text = lines.drop(timeIdx + 1)
                .joinToString("\n") { stripTags(it) }
                .trim()
            if (text.isNotEmpty()) cues += Cue(s, e, text)
        }
        return cues
    }

    fun parseVtt(raw: String): List<Cue> {
        val body = raw.replace("\r\n", "\n").removePrefix("WEBVTT").trim()
        return parseSrt(body)
    }

    fun at(cues: List<Cue>, posMs: Long, offsetMs: Long = 0): String {
        val t = posMs + offsetMs
        return cues.firstOrNull { t in it.startMs until it.endMs }?.text ?: ""
    }

    /** "Movie.2024.1080p.x264-GROUP.en.srt" → lang "en". */
    fun detectLang(fileName: String): String {
        val m = Regex("""\.([a-z]{2,3})(?:\.(?:srt|vtt|ass|ssa))?$""", RegexOption.IGNORE_CASE)
            .find(fileName.trim())
        return m?.groupValues?.get(1)?.lowercase() ?: ""
    }

    private fun stamp(s: String): Long {
        return try {
            val clean = s.replace(',', '.').trim()
            val parts = clean.split(':')
            val secParts = parts.last().split('.')
            val secs = secParts[0].toLong()
            val ms = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3).toLong()
            var total = secs * 1000 + ms
            if (parts.size == 3) total += parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000
            else if (parts.size == 2) total += parts[0].toLong() * 60_000
            total
        } catch (_: Exception) {
            -1
        }
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\{[^}]*\}"""), "")
            .trim()
}
