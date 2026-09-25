package com.veeshays.pulse.core

/**
 * User-facing error taxonomy. PC contract preserved: per-file try/catch,
 * concise offline-named messages, never a stack trace, never a crash for one
 * broken file. Callers map exceptions → [Kind] → string via [message].
 */
enum class Kind {
    MISSING_FILE, UNSUPPORTED, CORRUPT, DECODER, PERMISSION, STORAGE_GONE,
    ARTWORK_BAD, PLAYBACK, BLUETOOTH, NETWORK, UNKNOWN,
}

fun classify(t: Throwable): Kind {
    val m = (t.message ?: "").lowercase()
    return when {
        "enoent" in m || "no such" in m || "not found" in m -> Kind.MISSING_FILE
        "permission" in m || "denied" in m || "security" in m -> Kind.PERMISSION
        "unsupported" in m || "mime" in m || "extractor" in m -> Kind.UNSUPPORTED
        "codec" in m || "decoder" in m || "render" in m -> Kind.DECODER
        "nodomain" in m || "unresolved" in m || "timeout" in m || "offline" in m -> Kind.NETWORK
        else -> Kind.PLAYBACK
    }
}

fun message(kind: Kind): String = when (kind) {
    Kind.MISSING_FILE -> "File no longer available — removed from queue"
    Kind.UNSUPPORTED -> "Unsupported format — skipped, library intact"
    Kind.CORRUPT -> "This file looks damaged — skipped"
    Kind.DECODER -> "Couldn't decode this file on this device — skipped"
    Kind.PERMISSION -> "Media permission needed — enable it in Settings"
    Kind.STORAGE_GONE -> "Storage unavailable — playback paused safely"
    Kind.ARTWORK_BAD -> "Artwork couldn't load — showing monogram"
    Kind.PLAYBACK -> "Playback hiccup — skipped ahead safely"
    Kind.BLUETOOTH -> "Audio device changed — paused, tap play to resume"
    Kind.NETWORK -> "You're offline — local files still play"
    Kind.UNKNOWN -> "Something didn't play — skipped ahead safely"
}
