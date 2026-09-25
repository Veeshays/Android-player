package com.veeshays.pulse.models

import kotlinx.serialization.Serializable

/**
 * Path/URI-keyed identity, exactly like PC (`trackKey = path`).
 * MediaStore content URIs are the keys on Android; SAF tree URIs for videos
 * the user picked outside MediaStore. Ratings/favs/positions/tagOverrides all
 * key off [key], so rescans relink automatically like the PC ghost rescan.
 */
@Serializable
data class Track(
    val key: String, // content:// URI string — stable across rescans
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String = "",
    val trackNo: Int = 0,
    val year: Int = 0,
    val durationMs: Long = 0L,
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
    val isVideo: Boolean = false,
)

@Serializable
data class QueueSnapshot(
    val keys: List<String> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Int = 0, // 0 off · 1 all · 2 one (PC parity)
    val lastKey: String = "",
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val keys: List<String> = emptyList(),
)

@Serializable
data class PersistedState(
    val schema: Int = 1,
    val queue: QueueSnapshot = QueueSnapshot(),
    val positions: Map<String, Long> = emptyMap(), // key → ms (cap 200, PC parity)
    val ratings: Map<String, Int> = emptyMap(), // key → 1..5 (cap 500/300)
    val favTracks: Set<String> = emptySet(),
    val favArtists: Set<String> = emptySet(),
    val favAlbums: Set<String> = emptySet(),
    val history: List<String> = emptyList(), // cap 100
    val playCounts: Map<String, Int> = emptyMap(), // cap 500
    val playlists: List<Playlist> = emptyList(),
    val tagOverrides: Map<String, TagOverride> = emptyMap(), // cap 300, files never touched
    val theme: String = "AURA",
    val finish: String = "NEON",
    val glass: String = "TRANSLUCENT",
    val themeMode: String = "SYSTEM",
    val volume: Float = 0.9f,
    val muted: Boolean = false,
    val flow: Boolean = true, // Flow ∞ default ON, PC parity
    val lastView: String = "all",
    val eqPreset: String = "Flat",
    val eqChameleon: Boolean = false,
    val sleepMins: Int = 0,
    // Appearance/perf preference: NORMAL (default, full effects) or EFFICIENT
    // ("nap" UI: static Sun wash, slower position ticker; playback untouched).
    val perfMode: String = "NORMAL",
)

@Serializable
data class TagOverride(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
)
