package com.veeshays.pulse.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.WorkerThread

/**
 * Artwork decoding without image-loading libraries: system thumbnail APIs +
 * sampled ContentResolver decode, small LRU memory cache. Covers audio album
 * art (stream-decodable) and video frames (thumbnail API). Never throws;
 * failures show the monogram fallback. All work off the main thread.
 */
object Artwork {
    private const val MAX_MEM = 32
    private const val MAX_PX = 256

    private val mem = object : LinkedHashMap<String, Bitmap>(MAX_MEM, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_MEM
    }

    @Synchronized
    fun cached(key: String): Bitmap? = mem[key]

    @WorkerThread
    fun load(resolver: ContentResolver, uri: Uri): Bitmap? {
        val key = uri.toString()
        synchronized(mem) { mem[key]?.let { return it } }
        val bmp = runCatching {
            // Stream path first (audio album art, small images).
            decodeSampled(resolver, uri)
                // Video media URIs don't stream-decode: ask the thumbnail API.
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(uri, Size(MAX_PX, MAX_PX), null)
                } else null
        }.getOrNull()
        if (bmp != null) synchronized(mem) { mem[key] = bmp }
        return bmp
    }

    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
            return null
        }
        val raw = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (raw / (sample * 2) >= MAX_PX) sample *= 2
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }
}
