package com.veeshays.pulse.playback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.veeshays.pulse.ui.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback diagnostics: answers "why is nothing audible?" on-device without
 * logcat. Probes the exact failure layers in order — connection, timeline,
 * URI readability, output volume — plus the retained last error. One tap
 * copies the whole report for bug reports.
 */
@Composable
fun DiagnosticsCard(ps: PlayerUiState) {
    val ctx = LocalContext.current
    val lastError by PlaybackConnection.lastError.collectAsState()
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var toneState by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val c = ps.controller
    val transitions by PlaybackConnection.transitions.collectAsState()
    val stateName = when (c?.playbackState) {        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "no controller"
    }
    fun audioLine(): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val mode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            "music volume $vol/$max · ringer $mode"
        } catch (_: Exception) {
            "volume unknown"
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Playback diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            DiagRow("Engine", if (c == null) "not connected" else "connected")
            DiagRow("Controller", c?.hashCode()?.toString() ?: "null")
            DiagRow("Player", "$stateName · playWhenReady=${c?.playWhenReady ?: "-"}")
            DiagRow(
                "Queue",
                if (c == null) "-" else "${c.mediaItemCount} items · current ${c.currentMediaItemIndex}",
            )
            DiagRow(
                "Current",
                ps.mediaId?.substringAfterLast('/')?.take(48) ?: "none",
            )
            DiagRow(
                "Playable URI",
                ps.controller?.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)
                    ?: "NONE — resolution broken",
            )
            DiagRow(
                "Position",
                if (c == null) "-" else "${ps.positionMs / 1000}s / ${maxOf(0L, ps.durationMs) / 1000}s",
            )
            DiagRow("Output", audioLine())
            lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text("Last error: $it", style = MaterialTheme.typography.bodySmall)
            }
            probe?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (transitions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Transitions (newest last):", style = MaterialTheme.typography.labelMedium)
                transitions.takeLast(5).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { probe = null; probing = true },
                    enabled = !probing && ps.mediaId != null,
                ) { Text(if (probing) "Probing…" else "Probe current file") }
                TextButton(onClick = {
                    val report = buildString {
                        appendLine("PupPlay diagnostics")
                        appendLine("engine=${if (c == null) "null" else "ok"} state=$stateName")
                        appendLine("playWhenReady=${c?.playWhenReady} playing=${c?.isPlaying}")
                        appendLine("timeline=${c?.mediaItemCount} idx=${c?.currentMediaItemIndex}")
                        appendLine("media=${ps.mediaId}")
                        appendLine("pos=${ps.positionMs} dur=${ps.durationMs}")
                        appendLine(audioLine())
                        appendLine("error=$lastError")
                        appendLine("probe=$probe")
                        appendLine("transitions=${transitions.takeLast(5)}")
                    }
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PupPlay diagnostics", report))
                        copied = true
                    } catch (_: Exception) {
                    }
                }) { Text(if (copied) "Copied" else "Copy report") }
            }
            if (probing) {
                // Probe the PLAYABLE uri (controller item), never the mediaId
                // label — parsing a label as a URI always lies FileNotFound.
                val playable = ps.controller?.currentMediaItem?.localConfiguration?.uri?.toString()
                LaunchedEffect(ps.mediaId, probing) {
                    probe = probeCurrent(ctx, playable)
                    probing = false
                }
                if (probe == null) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
            }
            // Self-test tone: INDEPENDENT of ExoPlayer (AudioTrack sine via
            // TonePlayer). Proves the device output path only; it can neither
            // pass nor fail library playback, and library playback never
            // depends on it. Verified separately, ears-on.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    toneState = "playing tone…"
                    scope.launch {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val out = am?.let { TonePlayer.outputLine(it) } ?: "volume unknown"
                        val res = TonePlayer.play()
                        toneState = "$res · $out"
                        if (res.startsWith("tone failed")) {
                            PlaybackConnection.reportError("Self-test tone failed: $res")
                        }
                    }
                }) { Text("Play test tone") }
            }
            toneState?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Probe a PLAYABLE uri string (never a mediaId label) through the resolver. */
private suspend fun probeCurrent(ctx: Context, playableUri: String?): String =
    withContext(Dispatchers.IO) {
        if (playableUri.isNullOrBlank()) return@withContext "no playable URI (mediaId=${playableUri})"
        try {
            val uri = Uri.parse(playableUri)
            val type = ctx.contentResolver.getType(uri) ?: "unknown type"
            ctx.contentResolver.openInputStream(uri)?.use { it.read() }
                ?: return@withContext "UNREADABLE uri=$playableUri type=$type — file may be gone"
            "readable uri=$playableUri type=$type"
        } catch (e: Exception) {
            "UNREADABLE uri=$playableUri (${e.javaClass.simpleName}: ${e.message}) — file may be gone"
        }
    }
