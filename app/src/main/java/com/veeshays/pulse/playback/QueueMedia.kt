package com.veeshays.pulse.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.store.PulseStore

/**
 * Translates a library view (the queue-follows-view snapshot) into the
 * player's playlist. Pure + unit-tested:
 *  - every view plays as a unit, starting at the tapped track
 *  - saved positions become per-item resume points ONLY when the PC resume
 *    guards pass (pos > 8s, > 15s remaining); stale/short bookmarks are
 *    ignored so playback starts at 0 instead of a wrong offset
 *  - missing/unknown keys are never invented here — callers pass real Tracks
 */
fun buildMediaItems(
    tracks: List<Track>,
    positions: Map<String, Long>,
    artworkOf: (Track) -> Uri? = { null },
): List<MediaItem> = tracks.map { t ->
    pulseMediaItem(
        key = t.key,
        title = t.title,
        artist = t.artist,
        album = t.album,
        artwork = artworkOf(t),
        durationMs = t.durationMs,
        resumeMs = resolveResumeMs(t, positions),
    )
}

/**
 * Pure resume decision (JVM-testable; the MediaItem assembly above is
 * device-tested). Returns the bookmark ms, or null to start at 0.
 */
fun resolveResumeMs(track: Track, positions: Map<String, Long>): Long? =
    positions[track.key]?.takeIf { QueueManager.shouldResumeAt(it, track.durationMs) }

/** Start index of [startKey] inside [tracks], 0 when absent (never -1). */
fun startIndexOf(tracks: List<Track>, startKey: String?): Int =
    tracks.indexOfFirst { it.key == startKey }.takeIf { it >= 0 } ?: 0

/** Seek-bar fraction from live player values; unknown duration renders empty, never NaN. */
fun progressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** One controller-playlist row, with display metadata for queue/notification truth. */
data class QueueEntry(val key: String, val title: String, val artist: String)

/** Reads the live player playlist (titles included) — thin, no caching. */
fun queueEntries(c: MediaController?): List<QueueEntry> {
    if (c == null) return emptyList()
    return (0 until c.mediaItemCount).map { i ->
        val m = c.getMediaItemAt(i)
        QueueEntry(
            key = m.mediaId,
            // Never derive display text from the URI tail (numeric MediaStore
            // IDs like "8890" are not titles); blank metadata stays blank.
            title = m.mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown title" },
            artist = m.mediaMetadata.artist?.toString().orEmpty(),
        )
    }
}

/** Mirrors the live controller playlist back into the persisted snapshot. */
fun persistQueueSnapshot(store: PulseStore, c: MediaController) {
    val keys = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
    store.update { it.copy(queue = it.queue.copy(keys = keys, index = c.currentMediaItemIndex)) }
}

/** Sentinel re-export so UI call sites don't import ExoPlayer constants. */
val TIME_UNSET: Long = C.TIME_UNSET

/**
 * Result of the pre-play readability gate: proves a track's content URI
 * opens through ContentResolver BEFORE it reaches Media3, so an unreadable
 * file reports explicitly instead of stalling in BUFFERING. Carries the
 * MIME type + bytes actually read so the tap trace can name the file.
 * Robolectric-testable against real files (see SongTapRegressionTest).
 */
data class ReadableProbe(
    val readable: Boolean,
    val mime: String?,
    val bytesRead: Int,
    val error: String?,
)

/** Opens [key] for reading; never throws — failures return readable=false. */
fun probeReadable(resolver: android.content.ContentResolver, key: String): ReadableProbe {
    return try {
        val uri = Uri.parse(key)
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        var bytes = 0
        resolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(4096)
            val n = ins.read(buf)
            if (n > 0) bytes = n
        } ?: return ReadableProbe(false, mime, 0, "openInputStream returned null")
        ReadableProbe(true, mime, bytes, null)
    } catch (e: Exception) {
        ReadableProbe(false, null, 0, "${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * Session resolution contract (rebuilt foundation): given a requested media
 * identity, decide the playable URI. Rules, in order:
 *  - content://, file:// or android.resource:// identity → itself (canonical
 *    local sources; the ONLY playable inputs).
 *  - anything else (bare IDs, titles, custom schemes including any
 *    pupplay:// label) → null: unresolvable, must be reported, never guessed.
 *
 * There is deliberately NO probe-tone branch: the diagnostic tone lives
 * outside ExoPlayer (see playback/TonePlayer.kt) and must never appear in
 * the playback path. A mediaId is an opaque identity label; only
 * localConfiguration.uri plays.
 * Pure + unit-tested.
 */
fun resolveIdentityToUri(mediaId: String): Uri? = when {
    mediaId.startsWith("content://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    mediaId.startsWith("file://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    mediaId.startsWith("android.resource://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    else -> null
}
