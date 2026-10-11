package com.veeshays.pulse.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Independent diagnostic tone (rebuilt foundation).
 *
 * Deliberately OUTSIDE ExoPlayer/MediaSession/MediaController: it proves the
 * device audio output path (AudioTrack → mixer → speaker) without touching
 * the playback engine, so a silent library can never be masked by — or blamed
 * on — a tone fixture, and vice versa. No content URI, no DataSource, no
 * pupplay:// label anywhere in this path.
 *
 * Verified separately from library playback (see TonePlayerTest for the pure
 * sample math; audibility itself is ears-on, device-only).
 */
object TonePlayer {

    const val SAMPLE_RATE = 44100
    const val FREQUENCY_HZ = 440.0
    const val DURATION_MS = 1500

    /** Pure + JVM-testable: one 16-bit PCM sine sample at [index]. */
    fun sampleAt(index: Int): Short {
        val t = index.toDouble() / SAMPLE_RATE
        return (sin(2.0 * PI * FREQUENCY_HZ * t) * Short.MAX_VALUE * 0.5).toInt()
            .toShort()
    }

    /** Pure + JVM-testable: full PCM buffer for the tone. */
    fun pcmBuffer(): ShortArray {
        val n = (SAMPLE_RATE * DURATION_MS) / 1000
        return ShortArray(n) { sampleAt(it) }
    }

    /** Plays the tone on a fire-and-forget AudioTrack. Returns a human result. */
    suspend fun play(): String = withContext(Dispatchers.IO) {
        try {
            val pcm = pcmBuffer()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@withContext "tone failed: no audio output available"
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                val playedMs = DURATION_MS.toLong() + 200
                withContext(Dispatchers.Main) { kotlinx.coroutines.delay(playedMs) }
                "tone played (${DURATION_MS}ms 440Hz via AudioTrack — output path OK)"
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        } catch (e: Exception) {
            "tone failed (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    /** One-line output check (volume/mute state) without playing anything. */
    fun outputLine(am: AudioManager): String {
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted = if (android.os.Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else false
        return "music volume $vol/$max${if (muted) " MUTED" else ""}"
    }
}
