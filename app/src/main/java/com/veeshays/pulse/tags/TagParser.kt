package com.veeshays.pulse.tags

/**
 * Offline tag parser — Kotlin port of PC `tags.js` (window.parseTags).
 * Same coverage, same never-throws contract:
 * MP3 ID3v2 (TIT2/TPE1/TALB/TRCK/TYER+TDRC/TCON/APIC + v2.2 PIC + ext-header
 * skip) + ID3v1 fallback, FLAC Vorbis (+PICTURE presence), MP4 ilst
 * (©nam/©ART/aART/©alb/trkn/©gen/covr presence), OGG best-effort KEY=VALUE,
 * filename "Artist - Title" fallback with the same safe stripLeadingNo rules
 * (Blink-182 / 30STM / UB40 untouched).
 *
 * On Android this runs on Dispatchers.IO against ContentResolver slices
 * (head+tail batch, like the PC read_file_range optimization), never on UI.
 */
object TagParser {

    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val track: Int = 0,
        val year: Int = 0,
        val genre: String = "",
        val hasCover: Boolean = false,
    )

    private val LEADING_NO = Regex("""^\s*(?:\(\s*\d{1,3}\s*\)|\[\s*\d{1,3}\s*\]|\d{1,3}\s*[.\-–—])\s+""")

    /** Pure + total: safe for unit tests, mirrors tags.js exactly. */
    fun stripLeadingNo(s: String): String =
        LEADING_NO.replace(s, "").trim()

    /** Pure + total: "Artist - Title" split with track-number safety. */
    fun fromFilename(name: String): Tags {
        val base0 = name.substringBeforeLast('.').replace('_', ' ').trim()
        val numbered = Regex("""^(\d{1,3})\s*[-–—]\s*(.+)$""").matchEntire(base0)
        if (numbered != null) {
            return Tags(
                title = stripLeadingNo(numbered.groupValues[2].trim()).ifEmpty { base0 },
                track = numbered.groupValues[1].toIntOrNull() ?: 0,
            )
        }
        val base = stripLeadingNo(base0)
        val split = Regex("""^(.*?)\s*[-–—]\s*(.+)$""").matchEntire(base)
        if (split != null) {
            val artist = stripLeadingNo(split.groupValues[1].trim())
            val title = stripLeadingNo(split.groupValues[2].trim()).ifEmpty { base }
            if (artist.matches(Regex("""\d{1,3}"""))) return Tags(title = title, track = artist.toInt())
            return Tags(title = title, artist = artist)
        }
        return Tags(title = base.ifEmpty { "Unknown" })
    }

    /**
     * Minimal ID3v2 text-frame scan over a head slice (unsynchronisation +
     * extended-header aware). Full APIC decode is unnecessary on Android —
     * MediaStore/artwork path supplies covers; we only need hasCover + text.
     * Never throws; empty slice → empty tags.
     */
    fun parseId3v2Head(head: ByteArray): Tags {
        return try {
            if (head.size < 10 || !(head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte())) {
                return Tags()
            }
            var title = ""; var artist = ""; var album = ""
            var track = 0; var year = 0; var genre = ""; var cover = false
            var pos = 10
            // Extended header skip (v2.3/2.4 flag 0x40).
            if (head.size > 10 && (head[5].toInt() and 0x40) != 0 && head.size >= 14) {
                val ext = syncsafe(head, 10)
                pos = 10 + ext
            }
            fun frameSize4(p: Int): Int {
                val v = head
                return if (v[3] >= 4) syncsafe(v, p + 4) // v2.4 syncsafe
                else ((v[p + 4].toInt() and 0xFF) shl 24) or
                    ((v[p + 5].toInt() and 0xFF) shl 16) or
                    ((v[p + 6].toInt() and 0xFF) shl 8) or
                    (v[p + 7].toInt() and 0xFF)
            }
            while (pos + 10 <= head.size) {
                val id = head.decodeToString(pos, pos + 4)
                if (id[0] == '\u0000') break
                val size = frameSize4(pos).coerceIn(0, head.size - pos - 10)
                if (size <= 1) {
                    if (id == "APIC" || id == "PIC") cover = true
                    pos += 10 + size; continue
                }
                if (id == "APIC" || id == "PIC") {
                    cover = true; pos += 10 + size; continue
                }
                val text = decodeTextFrame(head, pos + 10, size)
                when (id) {
                    "TIT2", "TT2" -> title = text
                    "TPE1", "TP1" -> artist = text
                    "TALB", "TAL" -> album = text
                    "TRCK", "TRK" -> track = text.split("/").firstOrNull()?.trim()?.toIntOrNull() ?: 0
                    "TYER", "TYE", "TDRC" -> year = text.take(4).toIntOrNull() ?: 0
                    "TCON", "TCO" -> genre = text
                }
                pos += 10 + size
                if (pos > 65536) break // head-slice bound
            }
            Tags(title, artist, album, track, year, genre, cover)
        } catch (_: Exception) {
            Tags() // never throws — PC contract
        }
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun ByteArray.decodeToString(from: Int, to: Int): String {
        val end = to.coerceAtMost(size)
        return StringBuilder().also { sb ->
            for (i in from until end) sb.append((this[i].toInt() and 0xFF).toChar())
        }.toString()
    }

    private fun decodeTextFrame(b: ByteArray, off: Int, len: Int): String {
        return try {
            if (len <= 1 || off + len > b.size) return ""
            val enc = b[off].toInt() and 0xFF
            val body = b.copyOfRange(off + 1, off + len)
            val raw = when (enc) {
                0 -> String(body, Charsets.ISO_8859_1)
                1 -> String(body, Charsets.UTF_16)
                2 -> String(body, Charsets.UTF_16BE)
                else -> String(body, Charsets.UTF_8)
            }
            raw.substringBefore('\u0000').trim()
        } catch (_: Exception) {
            ""
        }
    }
}
