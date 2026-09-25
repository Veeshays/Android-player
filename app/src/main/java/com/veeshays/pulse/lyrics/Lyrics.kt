package com.veeshays.pulse.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Context.lyricsDataStore by preferencesDataStore(name = "pulse-lyrics-v1")

/** One synced line. */
data class LyricLine(val ms: Long, val text: String)

/** Parsed song lyrics: synced lines and/or plain text. */
data class LyricResult(val synced: List<LyricLine>, val plain: String)

/** LRCLIB wire shape (subset). Serializable = pure-JVM testable. */
@Serializable
data class LrclibTrack(
    val trackName: String = "",
    val artistName: String = "",
    val duration: Double = 0.0,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

sealed interface LyricOutcome {
    data class Found(val result: LyricResult, val fromCache: Boolean) : LyricOutcome
    data object NotFound : LyricOutcome // track unknown to LRCLIB
    data class Offline(val reason: String) : LyricOutcome // network failure
}

private val json = Json { ignoreUnknownKeys = true }

/** Cache key: normalized artist + title (LRCLIB lookups are metadata-based). */
fun lyricKey(artist: String, title: String): String =
    (artist.trim().lowercase() + "\n" + title.trim().lowercase()).take(300)

/**
 * LRC parser — never throws. Handles [mm:ss.xx] (multiple stamps per line),
 * [ar:/ti:/al:] headers (skipped), and inline <karaoke> tags (stripped).
 */
fun parseLrc(raw: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    for (line in raw.lines()) {
        val stamps = stamp.findAll(line).toList()
        if (stamps.isEmpty()) continue
        var text = stamp.replace(line, "").replace(Regex("""<[^>]*>"""), "").trim()
        if (text.isEmpty()) continue
        for (s in stamps) {
            val min = s.groupValues[1].toLongOrNull() ?: continue
            val sec = s.groupValues[2].toLongOrNull() ?: continue
            val frac = s.groupValues[3]
            val ms = min * 60_000 + sec * 1000 + when (frac.length) {
                3 -> frac.toLongOrNull() ?: 0L
                2 -> (frac.toLongOrNull() ?: 0L) * 10
                1 -> (frac.toLongOrNull() ?: 0L) * 100
                else -> 0L
            }
            out += LyricLine(ms, text)
        }
    }
    return out.sortedBy { it.ms }
}

fun plainFromLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { it.text }

/** Maps a /api/get body (or one /api/search entry) to a result, or null. */
fun mapTrack(t: LrclibTrack): LyricResult? {
    val synced = t.syncedLyrics?.let { parseLrc(it) }.orEmpty()
    val plain = t.plainLyrics?.trim().orEmpty()
    if (synced.isEmpty() && plain.isEmpty()) return null
    return LyricResult(synced, plain.ifEmpty { plainFromLrc(synced) })
}

/** Current synced line for [positionMs], or -1. */
fun currentLine(lines: List<LyricLine>, positionMs: Long): Int {
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].ms <= positionMs) idx = i else break
    }
    return idx
}

private fun get(url: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "PupPlay/1.0")
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != 200) return null
        conn.inputStream.bufferedReader().use { it.readText() }.take(200_000)
    } catch (_: Exception) {
        throw OfflineException()
    } finally {
        conn?.disconnect()
    }
}

private class OfflineException : Exception()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/**
 * LRCLIB lookup: exact /api/get first (artist+title+duration), then /api/search
 * best match. Network-isolated from playback; throws nothing (Offline on error).
 */
suspend fun fetchLyrics(artist: String, title: String, durationSec: Long): LyricOutcome =
    withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext LyricOutcome.NotFound
        try {
            val exact = get(
                "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
                    (if (durationSec > 0) "&duration=$durationSec" else ""),
            )
            if (exact != null) {
                val hit = runCatching { mapTrack(json.decodeFromString<LrclibTrack>(exact)) }.getOrNull()
                if (hit != null) return@withContext LyricOutcome.Found(hit, false)
            }
            val search = get(
                "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}",
            ) ?: return@withContext LyricOutcome.NotFound
            val list = runCatching {
                json.decodeFromString<List<LrclibTrack>>(search)
            }.getOrNull().orEmpty()
            val best = list.firstOrNull { mapTrack(it) != null }
                ?: return@withContext LyricOutcome.NotFound
            LyricOutcome.Found(mapTrack(best)!!, false)
        } catch (_: OfflineException) {
            LyricOutcome.Offline("Couldn't reach the lyrics service")
        } catch (_: Exception) {
            LyricOutcome.NotFound
        }
    }

/**
 * Small persistent lyric cache (60 entries like the PC lyric lab). Keyed by
 * normalized artist/title; value encodes synced-vs-plain + body. Read-only
 * w.r.t. media files; network failures never cached.
 */
class LyricStore(private val context: Context) {
    private val mem = object : LinkedHashMap<String, LyricResult>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, LyricResult>): Boolean =
            size > 60
    }

    suspend fun get(artist: String, title: String): LyricResult? {
        val key = lyricKey(artist, title)
        synchronized(mem) { mem[key]?.let { return it } }
        val raw = context.lyricsDataStore.data.first()[stringPreferencesKey(key)]
        if (raw == null) return null
        val synced = raw.startsWith("1\n")
        val body = raw.substring(2)
        val res = if (synced) LyricResult(parseLrc(body), plainFromLrc(parseLrc(body))) else LyricResult(emptyList(), body)
        if (res.synced.isEmpty() && res.plain.isEmpty()) return null
        synchronized(mem) { mem[key] = res }
        return res
    }

    suspend fun put(artist: String, title: String, result: LyricResult) {
        val key = lyricKey(artist, title)
        synchronized(mem) { mem[key] = result }
        context.lyricsDataStore.edit { prefs ->
            if (prefs.asMap().size >= 60) {
                prefs.asMap().keys.firstOrNull()?.let { prefs.remove(it) }
            }
            val encoded = (if (result.synced.isNotEmpty()) "1\n" else "0\n") +
                (if (result.synced.isNotEmpty()) result.synced.joinToString("\n") { l ->
                    "[${"%02d".format(l.ms / 60000)}:${"%02d".format((l.ms / 1000) % 60)}.${"%02d".format((l.ms % 1000) / 10)}]${l.text}"
                } else result.plain).take(100_000)
            prefs[stringPreferencesKey(key)] = encoded
        }
    }
}
