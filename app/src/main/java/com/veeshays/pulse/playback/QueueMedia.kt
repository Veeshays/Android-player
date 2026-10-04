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

/** Stable identity of the bundled self-test tone (a LABEL, never a source). */
const val TONE_MEDIA_ID = "pupplay://probe-tone"

/** Package-scoped android.resource URI for the bundled tone (immutable install asset). */
fun toneResourceUri(packageName: String): String =
    "android.resource://$packageName/raw/probe_tone"

/**
 * Session resolution contract (§3): given a requested media identity, decide
 * the playable URI. Rules, in order:
 *  - tone identity → caller-supplied tone file URI (no custom scheme is ever
 *    playable; the label only identifies).
 *  - content:// or file:// identity → itself (canonical local sources).
 *  - anything else (bare IDs, titles, unknown schemes) → null: unresolvable,
 *    must be reported, never guessed.
 * Pure + unit-tested.
 */
fun resolveIdentityToUri(mediaId: String, toneUri: () -> Uri?): Uri? = when {
    mediaId == TONE_MEDIA_ID -> runCatching { toneUri() }.getOrNull()
    mediaId.startsWith("content://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    mediaId.startsWith("file://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    mediaId.startsWith("android.resource://") -> runCatching { Uri.parse(mediaId) }.getOrNull()
    else -> null
}
