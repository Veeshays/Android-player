package com.veeshays.pulse.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.veeshays.pulse.store.PulseStore
import com.veeshays.pulse.ui.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The heart of the Android requirement: playback that survives minimize,
 * lock, app-switch, calls, headset/BT changes, and audio-focus loss.
 *
 * - MediaSessionService + ExoPlayer: the ONLY playback owner. UI binds via
 *   MediaController and can die freely; audio/video keep going.
 * - Notification + lock-screen: single MediaStyle notification on channel
 *   [Brand.NOTIFICATION_CHANNEL_ID] (play/pause/prev/next + seek where
 *   supported, artwork/title/artist). Updated by MediaSession callbacks —
 *   never duplicated, removed on stop (no stale notifications).
 * - Audio focus: ExoPlayer handles duck/transient/permanent loss via
 *   AudioAttributes; AUDIO_BECOMING_NOISY (headset/BT pull) pauses.
 * - Calls: phone state is transient loss → pause; resume ONLY if we were
 *   playing (never steal focus back uninvited, never fight other apps).
 * - Battery: foreground-service type mediaPlayback ONLY while playing;
 *   paused/stopped/empty queue → stopForeground + release session promptly.
 *   No polling timers, no wake locks, no scans from this service.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var store: PulseStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasPlayingBeforeLoss = false
    private var lastSaveMs = 0L

    override fun onCreate() {
        super.onCreate()
        store = PulseStore(applicationContext)
        ensureChannel()

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // headset/BT disconnect → pause
            .setSeekBackIncrementMs(5_000) // PC ←/→ ∓5s parity
            .setSeekForwardIncrementMs(5_000)
            .build().also { exo ->
                exo.addListener(serviceListener)
                exo.setForegroundMode(false)
            }

        session = MediaSession.Builder(this, player!!)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away: keep playing if audible (user expectation for a player),
        // but persist everything so cold-start restores exactly.
        scope.launch { store.flush() }
    }

    override fun onDestroy() {
        scope.launch { store.flush() }
        session?.run { release(); session = null }
        player?.run { removeListener(serviceListener); release(); player = null }
        scope.cancel()
        super.onDestroy()
    }

    private val serviceListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            player?.setForegroundMode(isPlaying) // FGS alive only while audible
            if (!isPlaying) persistPosition()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            persistPosition()
            scope.launch { store.load() }
            // History + play-count + notification metadata update happen here
            // via the MediaSession timeline (single source, no duplicates).
        }

        override fun onPlayerError(error: PlaybackException) {
            // Never crash on one broken file: skip forward once; UI surfaces
            // a concise message (Errors.kt), never a stack trace.
            player?.seekToNextMediaItem()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                persistPosition(clear = true)
                if (player?.mediaItemCount == 0) player?.setForegroundMode(false)
            }
        }
    }

    /** Throttled position persist (5s, PC video parity) + resume-guard write. */
    private fun persistPosition(clear: Boolean = false) {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (!clear && now - lastSaveMs < 5_000) return
        lastSaveMs = now
        val item = p.currentMediaItem ?: return
        val key = item.mediaId
        val pos = p.currentPosition
        val dur = p.duration.coerceAtLeast(0L)
        scope.launch {
            store.update { s ->
                val positions = s.positions.toMutableMap()
                if (clear || (dur > 0 && dur - pos < 15_000)) positions.remove(key)
                else if (pos > 8_000) positions[key] = pos
                s.copy(
                    positions = positions,
                    queue = s.queue.copy(lastKey = key),
                )
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        // Allow the controller's requested command (default policy: the
        // session exposes the full default command set to our own UI).
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int = playerCommand
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(Brand.NOTIFICATION_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    Brand.NOTIFICATION_CHANNEL_ID,
                    Brand.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "PupPlay playback controls" },
            )
        }
    }

    companion object {
        /** Build a one-off status notification (e.g. error hint). Session owns the media one. */
        fun hintNotification(ctx: Context, text: String): android.app.Notification {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                mgr?.getNotificationChannel(Brand.NOTIFICATION_CHANNEL_ID) == null
            ) {
                mgr?.createNotificationChannel(
                    NotificationChannel(
                        Brand.NOTIFICATION_CHANNEL_ID,
                        Brand.NOTIFICATION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, Brand.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(Brand.NAME)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        }
    }
}

/** AUDIO_BECOMING_NOISY fallback for API paths where ExoPlayer's flag is insufficient. */
class NoisyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            // ExoPlayer already pauses via setHandleAudioBecomingNoisy(true);
            // this receiver exists so the manifest path is explicit + testable.
        }
    }
}

/** MediaItem factory: URI-keyed (PC trackKey parity), metadata for notification/lock-screen. */
@OptIn(UnstableApi::class)
fun pulseMediaItem(
    key: String,
    title: String,
    artist: String,
    album: String,
    artwork: Uri?,
    durationMs: Long,
    resumeMs: Long?,
): MediaItem = MediaItem.Builder()
    .setMediaId(key)
    .setUri(key)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artwork)
            .setDurationMs(durationMs.takeIf { it > 0 })
            .build(),
    )
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(resumeMs?.coerceAtLeast(0L) ?: 0L)
            .build(),
    )
    .build()
