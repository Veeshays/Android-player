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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.veeshays.pulse.ui.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                LaunchedEffect(ps.mediaId) {
                    probe = probeCurrent(ctx, ps.mediaId)
                    probing = false
                }
                if (probe == null) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
            }
            // Self-test: known-good bundled WAV (copied once to a plain file)
            // through the SAME controller → service → ExoPlayer path as
            // library taps. Tone audible ⇒ engine OK and MediaStore URIs are
            // suspect; tone silent ⇒ engine broken.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val ctl = ps.controller
                    if (ctl == null) {
                        toneState = "engine not connected — tap Fetch/Library first"
                        PlaybackConnection.reportError("Test tone needs the playback engine")
                        return@Button
                    }
                    runCatching {
                        val uri = Uri.fromFile(toneFile(ctx))
                        val item = MediaItem.Builder()
                            .setMediaId("pupplay://probe-tone")
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Probe tone 440Hz")
                                    .setArtist("PupPlay self-test")
                                    .build(),
                            )
                            .build()
                        ctl.setMediaItems(listOf(item), 0, C.TIME_UNSET)
                        ctl.prepare()
                        ctl.play()
                        toneState = "tone sent — listening…"
                        scope.launch {
                            delay(3000)
                            toneState = if (ctl.isPlaying) {
                                "TONE PLAYING (pos ${ctl.currentPosition}ms) — engine OK, suspect media URIs"
                            } else {
                                "tone NOT playing (state=${ctl.playbackState}) — engine problem"
                            }
                        }
                    }.onFailure {
                        toneState = "tone failed: ${it.message}"
                        PlaybackConnection.reportError("Test tone failed")
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

/** Reads the current URI through the ContentResolver exactly as playback would. */
private suspend fun probeCurrent(ctx: Context, mediaId: String?): String =
    withContext(Dispatchers.IO) {
        if (mediaId == null) return@withContext "nothing loaded"
        try {
            val uri = Uri.parse(mediaId)
            val type = ctx.contentResolver.getType(uri) ?: "unknown type"
            ctx.contentResolver.openInputStream(uri)?.use { it.read() }
                ?: return@withContext "UNREADABLE ($type) — file may be gone"
            "readable ($type)"
        } catch (e: Exception) {
            "UNREADABLE (${e.javaClass.simpleName}) — file may be gone"
        }
    }

/**
 * Self-test tone as a bulletproof file:// URI: the bundled WAV is copied once
 * into cache (never user media, 441KB, deleted with app data). FileDataSource
 * is the simplest possible DataSource — no resource-table or provider layers
 * between the test and the player.
 */
fun toneFile(context: Context): java.io.File {
    val out = java.io.File(context.cacheDir, "probe_tone.wav")
    if (!out.exists() || out.length() == 0L) {
        context.resources.openRawResource(
            context.resources.getIdentifier("probe_tone", "raw", context.packageName),
        ).use { src ->
            out.outputStream().use { dst -> src.copyTo(dst) }
        }
    }
    return out
}
