package com.veeshays.pulse.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    /** One-way diagnostics: service/player failures surface here, never silent. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val errors: SharedFlow<String> = _errors

    /** Latest error, retained for the diagnostics screen. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Player state transition history (newest last, capped): stalls stay visible. */
    private val _transitions = MutableStateFlow<List<String>>(emptyList())
    val transitions: StateFlow<List<String>> = _transitions

    fun recordTransition(entry: String) {
        _transitions.value = (_transitions.value + entry).takeLast(12)
    }

    fun reportError(text: String) {
        _lastError.value = text
        _errors.tryEmit(text)
    }

    fun clearError() {
        _lastError.value = null
    }

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
