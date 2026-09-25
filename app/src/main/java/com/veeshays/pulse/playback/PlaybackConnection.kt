package com.veeshays.pulse.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single shared MediaController connection for all UI surfaces.
 * The Activity connects once; screens observe [controller] and issue
 * play/pause/seek/queue commands. Playback itself always lives in
 * PlaybackService — this holder never owns audio, so Back/destroy
 * can never stop playback.
 */
object PlaybackConnection {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller

    private var future: ListenableFuture<MediaController>? = null

    fun connect(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener(
            { _controller.value = runCatching { f.get() }.getOrNull() },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        _controller.value = null
        runCatching { future?.get()?.release() }
        future?.cancel(true)
        future = null
    }
}
