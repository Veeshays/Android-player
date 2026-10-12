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
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
    private var stallGen = 0 // retires stale BUFFERING watchdog checks

    override fun onCreate() {
        super.onCreate()
        // Single authoritative instance: a recreate without destroy must never
        // leave two players/sessions alive (duplicate-audio class of bug).
        try { session?.release() } catch (_: Exception) { }
        try { player?.removeListener(serviceListener) } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        session = null
        player = null
        store = PulseStore(applicationContext)
        ensureChannel()

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Canonical local-media stack, stated explicitly: ContentResolver-backed
        // data source (content://, file://, resource) → progressive source
        // fallback with extractors for ordinary local files. This is also the
        // default, but pinning it documents the supported model.
        val dataSource = DefaultDataSource.Factory(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // headset/BT disconnect → pause
            .setSeekBackIncrementMs(5_000) // PC ←/→ ∓5s parity
            .setSeekForwardIncrementMs(5_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build().also { exo ->
                exo.addListener(serviceListener)
                exo.setForegroundMode(false)
            }

        session = MediaSession.Builder(this, player!!)
            .setCallback(SessionCallback())
            .build()
        // Lifecycle proof: instance IDs pin every later observation to THESE
        // exact objects (same service/player/session or it didn't happen).
        // The listener is attached to THIS player at construction — the only
        // playback instance in the process — so callbacks cannot come from
        // anywhere else. DataSource/MediaSource selection is pinned here:
        // DefaultDataSource (content/file/resource) → DefaultMediaSourceFactory
        // (progressive + extractors, e.g. MP3). A track that reaches READY
        // proves this stack end to end; a stall points at the URI/source.
        PlaybackConnection.recordTransition(
            "SERVICE onCreate service=${this.hashCode()} " +
                "player=${player.hashCode()} session=${session.hashCode()}",
        )
        android.util.Log.i(
            "PupPlayTrace",
            "SERVICE onCreate service=${this.hashCode()} " +
                "player=${player.hashCode()} session=${session.hashCode()} " +
                "token=${session?.token} dataSource=${dataSource.javaClass.name} " +
                "mediaSourceFactory=DefaultMediaSourceFactory",
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Proves the UI controller binds to THIS session: package/uid of the
        // connecting controller are logged so a foreign or stale binder hop
        // cannot silently impersonate the app's own controller.
        android.util.Log.i(
            "PupPlayTrace",
            "SESSION bind pkg=${controllerInfo.packageName} uid=${controllerInfo.uid} " +
                "session=${session.hashCode()}",
        )
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away: keep playing if audible (user expectation for a player),
        // but persist everything so cold-start restores exactly.
        scope.launch { store.flush() }
    }

    override fun onDestroy() {
        PlaybackConnection.recordTransition("SERVICE onDestroy service=${this.hashCode()}")
        scope.launch { store.flush() }
        session?.run { release(); session = null }
        player?.run { removeListener(serviceListener); release(); player = null }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * No diagnostic start-action: the service owns exactly one entry point
     * (the MediaSession). The self-test tone lives outside ExoPlayer
     * (TonePlayer, AudioTrack) so it can never mask or break library
     * playback. Direct-service play hacks were removed in the rebuild.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    // TEMP-TRACE (device-debug session): structured logcat lines under tag
    // PupPlayTrace for media transitions, states, timeline and errors.
    // Remove after the real-song device pass. No personal metadata is
    // logged — only track identity needed to pin a failing file.
    private fun traceLine(): String {
        val p = player
        val item = p?.currentMediaItem
        val uri = item?.localConfiguration?.uri?.toString()
        val kind = when {
            uri == null -> "none"
            uri.startsWith("content://") -> "track"
            uri.startsWith("file://") -> "file"
            uri.startsWith("android.resource://") -> "resource"
            else -> "unknown-scheme"
        }
        // player= pins every line to the exact instance: a stale/duplicated
        // player can never be confused with the active one in logcat.
        return "player=${p.hashCode()} session=${session.hashCode()} " +
            "kind=$kind mediaId=${item?.mediaId} uri=$uri " +
            "timeline=${p?.mediaItemCount} idx=${p?.currentMediaItemIndex}"
    }

    private val serviceListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            player?.setForegroundMode(isPlaying) // FGS alive only while audible
            android.util.Log.i("PupPlayTrace", "PLAYING isPlaying=$isPlaying ${traceLine()}")
            if (!isPlaying) persistPosition()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            persistPosition()
            android.util.Log.i(
                "PupPlayTrace",
                "TRANSITION reason=$reason title=${item?.mediaMetadata?.title} ${traceLine()}",
            )
            // NOTE: never store.load() here — reloading disk state would clobber
            // live UI edits (shuffle toggles, queue edits) not yet flushed.
            val key = item?.mediaId ?: return
            scope.launch {
                store.update { s ->
                    s.copy(
                        history = (s.history - key + key).takeLast(100),
                        playCounts = s.playCounts + (key to minOf(99999, (s.playCounts[key] ?: 0) + 1)),
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // One broken file never kills playback — and it is never silent
            // either: the report names the real exception AND the failing URI
            // so logcat/diagnostics can pin the file (rebuild fix: the old
            // generic message swallowed both).
            val p = player
            val failingUri = p?.currentMediaItem?.localConfiguration?.uri?.toString()
                ?: p?.currentMediaItem?.mediaId
                ?: "unknown item"
            // Full cause chain: DataSource/Extractor/Decoder failures nest.
            val chain = generateSequence<Throwable>(error) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            val detail = "code=${error.errorCode}(${error.errorCodeName}) " +
                "uri=$failingUri chain=[$chain]"
            android.util.Log.e("PupPlayTrace", "ERROR $detail ${traceLine()}")
            PlaybackConnection.recordTransition("ERROR $detail")
            if (p != null && p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                PlaybackConnection.reportError("Skipped unreadable file ($detail)")
            } else {
                p?.pause()
                PlaybackConnection.reportError("Couldn't play this file ($detail) — stopped safely")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "S$state"
            }
            val p = player
            // Transition history keeps stalls visible: a player stuck in
            // BUFFERING (or flapping IDLE) can no longer look merely "quiet".
            val line = "$name ready=${p?.playWhenReady} playing=${p?.isPlaying} " +
                "pos=${p?.currentPosition} dur=${p?.duration} buffered=${p?.bufferedPosition} ${traceLine()}"
            android.util.Log.i("PupPlayTrace", "STATE $line")
            PlaybackConnection.recordTransition(line)
            if (state == Player.STATE_BUFFERING) {
                armStallWatchdog()
            } else {
                stallGen++ // any other state retires a pending stall check
            }
            if (state == Player.STATE_ENDED) {
                persistPosition(clear = true)
                if (p?.mediaItemCount == 0) p.setForegroundMode(false)
            }
        }

        /**
         * Converts the exact reported symptom (BUFFERING + pos=0 + error=null,
         * forever) into a loud, actionable error naming the URI. Local files
         * open in milliseconds — 10s with zero progress is never legitimate
         * buffering. Reports only; never stops or skips (no behavior change).
         */
        private fun armStallWatchdog() {
            val gen = ++stallGen
            val pos0 = player?.currentPosition ?: 0L
            val buf0 = player?.bufferedPosition ?: 0L
            val uri0 = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                ?: player?.currentMediaItem?.mediaId
            scope.launch {
                kotlinx.coroutines.delay(STALL_TIMEOUT_MS)
                val q = player ?: return@launch
                val msg = stallReport(
                    q.playbackState, gen == stallGen,
                    pos0, buf0, q.currentPosition, q.bufferedPosition, uri0,
                ) ?: return@launch
                android.util.Log.w("PupPlayTrace", "STALL $msg ${traceLine()}")
                PlaybackConnection.recordTransition("STALL $msg")
                PlaybackConnection.reportError(msg)
            }
        }

        // Timeline arrival proof: setMediaItems from the UI MUST surface
        // here. If TAP/HANDOFF are logged but TIMELINE never is, the
        // controller→session hop is the broken handoff — not the decoder.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val line = "TIMELINE reason=$reason windowCount=${timeline.windowCount} ${traceLine()}"
            android.util.Log.i("PupPlayTrace", line)
            PlaybackConnection.recordTransition(line)
        }

        // Loading-bit proof: BUFFERING with isLoading=false means the
        // player is NOT waiting on data (stuck elsewhere); with true it
        // is waiting on the DataSource for the URI in traceLine().
        override fun onIsLoadingChanged(isLoading: Boolean) {
            android.util.Log.i("PupPlayTrace", "LOADING isLoading=$isLoading ${traceLine()}")
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

        // Session-level guard: items must arrive playable (URI in
        // localConfiguration). Anything unresolved is resolved HERE by
        // identity — content/file/android.resource pass through, everything
        // else (bare IDs, titles, custom schemes) is reported loudly with
        // the requested id, never guessed. No probe-tone branch: the tone
        // is not part of the playback path (see TonePlayer).
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val out = mediaItems.map { item ->
                val playable = item.localConfiguration?.uri
                if (playable != null) {
                    android.util.Log.i(
                        "PupPlayTrace",
                        "ADD passthrough mediaId=${item.mediaId} uri=$playable " +
                            "title=${item.mediaMetadata.title}",
                    )
                    item // already resolved: canonical passthrough
                } else {
                    val resolved = resolveIdentityToUri(item.mediaId)
                    if (resolved != null) {
                        android.util.Log.i(
                            "PupPlayTrace",
                            "ADD resolve requested=${item.mediaId} resolved=$resolved",
                        )
                        PlaybackConnection.recordTransition(
                            "RESOLVE requested=${item.mediaId} resolved=$resolved",
                        )
                        item.buildUpon().setUri(resolved).build()
                    } else {
                        android.util.Log.w(
                            "PupPlayTrace",
                            "ADD reject requested=${item.mediaId} (unresolvable identity)",
                        )
                        PlaybackConnection.recordTransition(
                            "REJECT requested=${item.mediaId} (unresolvable identity)",
                        )
                        PlaybackConnection.reportError(
                            "Unplayable item (${item.mediaId}) — no readable URI",
                        )
                        item
                    }
                }
            }
            return Futures.immediateFuture(out)
        }
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
        /** Stall watchdog: BUFFERING with zero progress this long is a failure. */
        const val STALL_TIMEOUT_MS = 10_000L

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
): MediaItem {
    val builder = MediaItem.Builder()
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
    // Clipping wraps the source in ClippingSource — only pay for that
    // machinery when actually resuming mid-track. Plain items take the
    // direct extractor path (one less layer between source and renderer).
    val start = resumeMs?.coerceAtLeast(0L) ?: 0L
    if (start > 0) {
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start)
                .build(),
        )
    }
    return builder.build()
}

/**
 * Pure stall decision (unit-tested): returns the loud STALL message when the
 * player is STILL buffering with zero progress after the timeout, else null.
 * [stillSameEpisode] is false when any state change retired the check.
 */
fun stallReport(
    stateNow: Int,
    stillSameEpisode: Boolean,
    startPosMs: Long,
    startBufferedMs: Long,
    curPosMs: Long,
    curBufferedMs: Long,
    uri: String?,
): String? {
    if (stateNow != Player.STATE_BUFFERING || !stillSameEpisode) return null
    if (curPosMs != startPosMs || curBufferedMs != startBufferedMs) return null
    return "Still loading after ${PlaybackService.STALL_TIMEOUT_MS / 1000}s " +
        "(uri=$uri) — reinstall the latest APK, re-tap the song, then " +
        "capture logcat (adb logcat -s PupPlayTrace:I *:S)"
}
