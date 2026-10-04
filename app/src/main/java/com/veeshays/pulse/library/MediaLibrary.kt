package com.veeshays.pulse.library

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.tags.TagParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local media library over MediaStore — the Android answer to PC `scan_folder`.
 *
 * READ-ONLY DISCOVERY. This class only queries MediaStore (the system's own
 * index) and reads metadata columns. It never deletes, moves, renames,
 * overwrites, copies, or retouches media files, timestamps, or metadata, and
 * never crawls arbitrary filesystem paths or other apps' private storage.
 * The only writes Pulse ever makes are its own library/index/state data
 * (DataStore), never media directories.
 *
 * PC pipeline adapted: Rust walk → single MediaStore query (the system keeps
 * the index, so startup sync is inherently incremental — new/deleted/renamed
 * files appear/disappear without our own walker, tombstones, or polling).
 *
 * Hardening contract:
 *  - cooperative cancellation ([ensureActive] per batch): backgrounding,
 *    rotation, navigation, or playback-starter cancels promptly; the held
 *    Context is always the application context — never an Activity.
 *  - throttled progress (every 100 rows) for FULL scans; STARTUP stays silent.
 *  - per-row isolation: one malformed/unreadable row increments
 *    [ScanResult.skippedUnreadable] and never aborts the collection.
 *  - stable identity: dedupe by content URI ([Track.key]); filename/title
 *    are display data only, never identity.
 *  - [ScanGuard] prevents overlapping scans even if the UI is tapped twice.
 */
class MediaLibrary(context: Context) {

    /** Application context only — a scan must never pin an Activity. */
    private val appContext: Context = context.applicationContext
    private val guard = ScanGuard()

    /** STARTUP: fast silent sync. FULL: explicit user Fetch with progress. */
    enum class ScanMode { STARTUP, FULL }

    data class ScanResult(
        val audio: List<Track>,
        val video: List<Track>,
        val skippedUnsupported: Int,
        val skippedUnreadable: Int,
        val truncated: Boolean,
        val rowsSeen: Int, // raw cursor rows iterated (both volumes)
    ) {
        companion object {
            val EMPTY = ScanResult(emptyList(), emptyList(), 0, 0, false, 0)
        }
    }

    val audioExts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "webm", "mid")
    val videoExts = setOf("mp4", "mkv", "webm", "mov", "3gp", "ts", "avi")

    /**
     * Returns null when another scan is already running (caller keeps the old
     * list; the Fetch button is also disabled while [scanning]).
     */
    suspend fun scan(
        mode: ScanMode = ScanMode.FULL,
        onProgress: (rowsSeen: Int) -> Unit = {},
        includeVideo: Boolean = true,
    ): ScanResult? {
        if (!guard.tryAcquire()) return null
        try {
            return withContext(Dispatchers.IO) {
                val report: (Int) -> Unit =
                    if (mode == ScanMode.FULL) onProgress else { _ -> }
                val audio = queryVolume(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, report)
                val video = if (includeVideo) {
                    queryVolume(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, report)
                } else {
                    Collection(emptyList(), 0, 0, false, 0)
                }
                ScanResult(
                    audio = audio.tracks,
                    video = video.tracks,
                    skippedUnsupported = audio.skipped + video.skipped,
                    skippedUnreadable = audio.unreadable + video.unreadable,
                    truncated = audio.truncated || video.truncated,
                    rowsSeen = audio.rows + video.rows,
                )
            }
        } finally {
            guard.release()
        }
    }

    internal data class Collection(
        val tracks: List<Track>,
        val skipped: Int,
        val unreadable: Int,
        val truncated: Boolean,
        val rows: Int,
    )

    private suspend fun queryVolume(
        uri: Uri,
        isVideo: Boolean,
        onProgress: (Int) -> Unit,
    ): Collection {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        var result = Collection(emptyList(), 0, 0, false, 0)
        runCatching {
            appContext.contentResolver.query(
                uri, proj,
                "${MediaStore.MediaColumns.DURATION} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c -> result = mapRows(c, uri, isVideo, onProgress) }
        }
        return result
    }

    /**
     * Cursor → library rows. Pure mapping over an open cursor (no resolver):
     * stable-URI dedupe, per-row isolation (one malformed row never aborts the
     * collection), 20k cap, throttled progress + cooperative cancellation.
     * Separated from the query so the mapping contract is directly testable
     * with a MatrixCursor.
     */
    internal suspend fun mapRows(
        c: Cursor,
        volume: Uri,
        isVideo: Boolean,
        onProgress: (Int) -> Unit = {},
    ): Collection {
        val seen = LinkedHashMap<String, Track>() // stable-URI dedupe, cursor order kept
        var skipped = 0
        var unreadable = 0
        var truncated = false
        var rows = 0
        val idx = ColumnIdx.forCursor(c)
        while (c.moveToNext()) {
            rows++
            if (rows % 100 == 0) {
                currentCoroutineContext().ensureActive() // cooperative cancellation point
                onProgress(rows)
            }
            val track = runCatching { mapRow(c, idx, volume, isVideo) }.getOrNull()
            if (track == null) {
                unreadable++ // malformed row tolerated, scan continues
                continue
            }
            if (track == UNSUPPORTED) {
                skipped++
                continue
            }
            seen.putIfAbsent(track.key, track) // first wins: stable identity
            if (seen.size >= MAX_ITEMS) {
                truncated = true
                break
            }
        }
        return Collection(seen.values.toList(), skipped, unreadable, truncated, rows)
    }

    private data class ColumnIdx(
        val id: Int, val name: Int, val title: Int, val artist: Int,
        val album: Int, val dur: Int, val size: Int, val added: Int,
    ) {
        constructor(c: Cursor, proj: Array<String>) : this(
            c.getColumnIndexOrThrow(proj[0]), c.getColumnIndexOrThrow(proj[1]),
            c.getColumnIndexOrThrow(proj[2]), c.getColumnIndexOrThrow(proj[3]),
            c.getColumnIndexOrThrow(proj[4]), c.getColumnIndexOrThrow(proj[5]),
            c.getColumnIndexOrThrow(proj[6]), c.getColumnIndexOrThrow(proj[7]),
        )

        companion object {
            /** Name-based lookup: immune to projection ordering. */
            fun forCursor(c: Cursor): ColumnIdx = ColumnIdx(
                c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.ARTIST),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.ALBUM),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE),
                c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED),
            )
        }
    }

    /** Maps one cursor row; returns UNSUPPORTED for filtered extensions. Throws on malformed rows. */
    private fun mapRow(c: Cursor, i: ColumnIdx, volume: Uri, isVideo: Boolean): Track {
        val name = c.getString(i.name).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val supported = if (isVideo) ext in videoExts else ext in audioExts
        if (!supported) return UNSUPPORTED
        val contentUri = ContentUris.withAppendedId(volume, c.getLong(i.id))
        var title = c.getString(i.title).orEmpty().ifBlank { name.substringBeforeLast('.') }
        var artist = c.getString(i.artist).orEmpty()
        if (title.isBlank() || artist.isBlank()) {
            val fb = TagParser.fromFilename(name)
            if (title.isBlank() || title == name.substringBeforeLast('.')) title = fb.title
            if (artist.isBlank()) artist = fb.artist
        }
        title = TagParser.stripLeadingNo(title)
        return Track(
            key = contentUri.toString(),
            title = title.ifBlank { "Unknown" },
            artist = artist.ifBlank { "Unknown artist" },
            album = c.getString(i.album).orEmpty(),
            durationMs = c.getLong(i.dur).coerceAtLeast(0L),
            displayName = name,
            sizeBytes = runCatching { c.getLong(i.size) }.getOrDefault(0L),
            dateAdded = runCatching { c.getLong(i.added) }.getOrDefault(0L),
            isVideo = isVideo,
        )
    }

    /** Artwork URI for lock-screen/notification/covers (never throws). */
    fun artworkUri(trackKey: String, video: Boolean): Uri? = runCatching {
        val id = ContentUris.parseId(Uri.parse(trackKey))
        if (video) Uri.parse(trackKey)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id)
        } else null
    }.getOrNull()

    companion object {
        /** Unified cap (PC parity: music 20k). Bounded memory by construction. */
        const val MAX_ITEMS = 20_000

        /** Sentinel for extension-filtered rows (never enters the library). */
        val UNSUPPORTED = Track(key = "", title = "", artist = "")
    }
}

/** Non-reentrant scan lock: Fetch taps can never stack overlapping scans. */
class ScanGuard {
    private val busy = AtomicBoolean(false)
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)
    fun release() {
        busy.set(false)
    }

    val isBusy: Boolean get() = busy.get()
}

/** Set difference between two scans, keyed by stable content URI. */
data class LibraryDiff(
    val added: List<Track>,
    val removed: List<Track>,
    val updated: List<Track>, // same key, any metadata field changed
    val unchanged: Int,
)

/** Pure + deterministic: repeated Fetch of the same list yields zero changes. */
fun diffLibrary(old: List<Track>, new: List<Track>): LibraryDiff {
    val before = old.associateBy { it.key }
    val after = new.associateBy { it.key }
    val added = after.keys.minus(before.keys).mapNotNull { after[it] }
    val removed = before.keys.minus(after.keys).mapNotNull { before[it] }
    val common = before.keys.intersect(after.keys)
    return LibraryDiff(
        added = added,
        removed = removed,
        updated = common.filter { before[it] != after[it] }.mapNotNull { after[it] },
        unchanged = common.count { before[it] == after[it] },
    )
}

/** Pure dedupe by stable URI (first wins); filenames/titles never identity. */
fun dedupeTracks(tracks: List<Track>): List<Track> {
    val seen = LinkedHashMap<String, Track>(tracks.size)
    for (t in tracks) seen.putIfAbsent(t.key, t)
    return seen.values.toList()
}
