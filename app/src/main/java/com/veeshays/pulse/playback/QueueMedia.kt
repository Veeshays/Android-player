package com.veeshays.pulse.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.veeshays.pulse.models.Track

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

/** Sentinel re-export so UI call sites don't import ExoPlayer constants. */
val TIME_UNSET: Long = C.TIME_UNSET
