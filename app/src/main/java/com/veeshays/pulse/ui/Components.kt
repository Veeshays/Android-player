package com.veeshays.pulse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NextPlan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.veeshays.pulse.PulseApp
import com.veeshays.pulse.eq.PulseEq
import com.veeshays.pulse.library.Artwork
import com.veeshays.pulse.playback.PlaybackConnection
import com.veeshays.pulse.playback.QueueEntry
import com.veeshays.pulse.playback.persistQueueSnapshot
import com.veeshays.pulse.playback.progressFraction
import com.veeshays.pulse.playback.queueEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.veeshays.pulse.models.Track

/**
 * Shared touch-friendly components. PC mapping:
 *  - nowbar/minibar → MiniPlayer (persistent, all tabs)
 *  - desktop context menu → ContextSheet (bottom sheet, same actions)
 *  - queue list → QueueSheet (swipe-dismiss rows, drag-reorder handle,
 *    explicit tap-to-play so reorder never misfires)
 *  - transport → 56–72dp targets, contentDescriptions for TalkBack
 */
@Composable
fun TransportRow(
    playing: Boolean,
    shuffle: Boolean,
    repeat: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    compact: Boolean = false,
) {
    val btn = if (compact) 48.dp else 56.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onShuffle,
            modifier = Modifier
                .size(btn)
                .semantics { contentDescription = "Shuffle, ${if (shuffle) "on" else "off"}" },
        ) { Icon(Icons.Filled.Shuffle, null, tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(
            onClick = onPrev,
            modifier = Modifier
                .size(btn + 8.dp)
                .semantics { contentDescription = "Previous track" },
        ) { Icon(Icons.Filled.SkipPrevious, null, modifier = Modifier.size(32.dp)) }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(btn + 16.dp)
                .semantics { contentDescription = if (playing) "Pause" else "Play" },
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(btn + 8.dp)
                .semantics { contentDescription = "Next track" },
        ) { Icon(Icons.Filled.SkipNext, null, modifier = Modifier.size(32.dp)) }
        IconButton(
            onClick = onRepeat,
            modifier = Modifier
                .size(btn)
                .semantics { contentDescription = "Repeat mode $repeat" },
        ) {
            Icon(
                when (repeat) {
                    2 -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                null,
                tint = if (repeat == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    // Ticks must never fight an active drag: freeze on external updates
    // while scrubbing, commit once on release. Thin 4dp visual track is the
    // Material3 default (thumb 20dp, 48dp touch floor kept).
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(positionMs.toFloat()) }
    LaunchedEffect(positionMs) {
        if (!scrubbing) scrub = positionMs.toFloat()
    }
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = scrub.coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = { scrub = it; scrubbing = true },
            onValueChangeFinished = { scrubbing = false; onSeek(scrub.toLong()) },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Seek, ${fmt(scrub.toLong())} of ${fmt(durationMs)}" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(scrub.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(fmt(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun fmt(ms: Long): String {
    if (ms < 0) return "—"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Desktop card language, restrained for mobile: translucent panel fill +
 * hairline accent border + 16dp radius. No blur (GPU-cheap), no heavy
 * elevation. Dark/light both resolve from the active Pulse palette.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    )
    val border = androidx.compose.foundation.BorderStroke(
        1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
    if (onClick != null) {
        androidx.compose.material3.Card(
            onClick = onClick, modifier = modifier,
            shape = shape, colors = colors, border = border,
            content = { content() },
        )
    } else {
        Card(
            modifier = modifier, shape = shape, colors = colors, border = border,
            content = { content() },
        )
    }
}

/**
 * Single observation point for the service-owned player. Screens read
 * [playing]/[positionMs]/[durationMs] and issue commands on [controller].
 * Position ticks run only while audible (500ms); the listener is removed
 * with the composition — no leaks, no background polling.
 */
data class PlayerUiState(
    val controller: MediaController?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val mediaId: String?,
    val shuffle: Boolean,
    val repeat: Int, // 0 off · 1 all · 2 one (PC parity)
    // Live metadata from the player's current item (notification truth).
    val title: String?,
    val artist: String?,
    val album: String?,
    val artUri: android.net.Uri?,
)

@Composable
fun rememberPlayerState(tickMs: Long = 500L): PlayerUiState {
    val controller by PlaybackConnection.controller.collectAsState()
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var album by remember { mutableStateOf<String?>(null) }
    var artUri by remember { mutableStateOf<android.net.Uri?>(null) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() {
            playing = c.isPlaying
            positionMs = c.currentPosition.coerceAtLeast(0L)
            durationMs = c.duration.coerceAtLeast(0L)
            val item = c.currentMediaItem
            mediaId = item?.mediaId
            shuffle = c.shuffleModeEnabled
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> 1
                Player.REPEAT_MODE_ONE -> 2
                else -> 0
            }
            item?.mediaMetadata?.let { m ->
                title = m.title?.toString()
                artist = m.artist?.toString()
                album = m.albumTitle?.toString()
                artUri = m.artworkUri
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) = sync()
            override fun onPlaybackStateChanged(s: Int) = sync()
            override fun onMediaItemTransition(item: MediaItem?, r: Int) = sync()
            override fun onShuffleModeEnabledChanged(enabled: Boolean) = sync()
            override fun onRepeatModeChanged(mode: Int) = sync()
        }
        c.addListener(listener)
        sync()
        onDispose { c.removeListener(listener) }
    }
    LaunchedEffect(controller, playing) {
        while (playing) {
            val c = controller
            if (c == null) break
            positionMs = c.currentPosition.coerceAtLeast(0L)
            durationMs = c.duration.coerceAtLeast(0L)
            delay(tickMs)
        }
    }
    return PlayerUiState(controller, playing, positionMs, durationMs, mediaId, shuffle, repeat, title, artist, album, artUri)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    playing: Boolean,
    rating: Int,
    fav: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFav: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = "${track.title} by ${track.artist}" },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVersatility,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Album else Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    track.title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (rating > 0) Text("★$rating", style = MaterialTheme.typography.labelMedium)
            IconButton(
                onClick = onToggleFav,
                modifier = Modifier.semantics { contentDescription = if (fav) "Unfavorite" else "Favorite" },
            ) { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) }
        }
    }
}

// Alignment shim that compiles on older Compose (CenterVertically alias).
private val Alignment.Companion.CenterVersatility
    get() = CenterVertically

@Composable
fun RatingBar(rating: Int, onRate: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { i ->
            IconButton(
                onClick = { onRate(if (i == rating) 0 else i) },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Rate $i stars" },
            ) {
                Icon(
                    if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    null,
                    tint = if (i <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(ps: PlayerUiState, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PulseApp
    val entries = remember(ps.controller, ps.mediaId, ps.durationMs) { queueEntries(ps.controller) }
    val currentIdx = ps.controller?.currentMediaItemIndex ?: -1
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (entries.isEmpty()) "Queue" else "Queue (${entries.size})",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick = {
                    ps.controller?.let { c ->
                        c.stop()
                        c.clearMediaItems()
                        persistQueueSnapshot(app.store, c)
                    }
                },
                enabled = entries.isNotEmpty(),
            ) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text(
                "Queue is empty — pick anything from Library.",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(entries.indices.toList(), key = { entries[it].key }) { idx ->
                    val e = entries[idx]
                    QueueRow(
                        entry = e,
                        current = idx == currentIdx,
                        playing = idx == currentIdx && ps.playing,
                        onTap = {
                            ps.controller?.let { c ->
                                c.seekTo(idx, 0)
                                c.play()
                                persistQueueSnapshot(app.store, c)
                            }
                        },
                        onRemove = {
                            ps.controller?.let { c ->
                                c.removeMediaItem(idx)
                                persistQueueSnapshot(app.store, c)
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(entry: QueueEntry, current: Boolean, playing: Boolean, onTap: () -> Unit, onRemove: () -> Unit) {
    // Swipe-to-remove; the delete button is the non-gesture alternative.
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { it != SwipeToDismissBoxValue.Settled },
    )
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled && !dismissed) {
            dismissed = true
            onRemove()
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", modifier = Modifier.padding(end = 16.dp))
            }
        },
        content = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTap)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { contentDescription = "${entry.title} by ${entry.artist}${if (current) ", current" else ""}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (playing) Icons.Filled.Equalizer else Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        entry.artist.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove ${entry.title}" },
                ) { Icon(Icons.Filled.Delete, null) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(key: String, ps: PlayerUiState, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    val entries = remember(ps.controller, ps.mediaId, ps.durationMs) { queueEntries(ps.controller) }
    val idx = entries.indexOfFirst { it.key == key }
    val cur = ps.controller?.currentMediaItemIndex ?: -1
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(key.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = {
                    ps.controller?.let { c ->
                        if (idx >= 0) {
                            c.seekTo(idx, 0)
                            c.play()
                            persistQueueSnapshot(app.store, c)
                        }
                    }
                    onDismiss()
                },
                enabled = idx >= 0,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Play")
                }
            }
            TextButton(
                onClick = {
                    ps.controller?.let { c ->
                        if (idx >= 0) {
                            c.moveMediaItem(idx, (cur + 1).coerceIn(0, c.mediaItemCount))
                            persistQueueSnapshot(app.store, c)
                        }
                    }
                    onDismiss()
                },
                enabled = idx >= 0 && idx != cur,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NextPlan, null); Text("Play next")
                }
            }
            TextButton(
                onClick = {
                    ps.controller?.let { c ->
                        if (idx >= 0) {
                            c.removeMediaItem(idx)
                            persistQueueSnapshot(app.store, c)
                        }
                    }
                    onDismiss()
                },
                enabled = idx >= 0,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, null); Text("Remove from queue")
                }
            }
            TextButton(onClick = {
                app.store.update { s ->
                    s.copy(favTracks = if (key in s.favTracks) s.favTracks - key else s.favTracks + key)
                }
                onDismiss()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (key in state.favTracks) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        null,
                    )
                    Text(if (key in state.favTracks) "Unfavorite" else "Favorite")
                }
            }
            RatingBar(
                rating = state.ratings[key] ?: 0,
                onRate = { stars ->
                    app.store.update { s ->
                        s.copy(ratings = if (stars == 0) s.ratings - key else s.ratings + (key to stars))
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Artwork thumb with icon fallback showing through on load failure. */
@Composable
fun ArtThumb(artUri: android.net.Uri?, size: androidx.compose.ui.unit.Dp, desc: String?) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(size * 0.5f))
        if (artUri != null) {
            var bmp by remember(artUri) { mutableStateOf(Artwork.cached(artUri.toString())) }
            LaunchedEffect(artUri) {
                if (bmp == null) {
                    bmp = withContext(Dispatchers.IO) {
                        Artwork.load(ctx.contentResolver, artUri)
                    }
                }
            }
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = desc,
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(9.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
    }
}

/** Slim persistent bar for non-Player tabs: progress + identity + play/next. */
@Composable
fun MiniPlayer(ps: PlayerUiState, onOpenPlayer: () -> Unit) {
    val c = ps.controller ?: return
    if (ps.mediaId == null) return
    val progress = progressFraction(ps.positionMs, ps.durationMs)
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.semantics { contentDescription = "Now playing mini player" },
    ) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtThumb(ps.artUri, 44.dp, "Cover art")
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        ps.title ?: Brand.Copy.NOTHING_PLAYING,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        ps.artist ?: Brand.Copy.ADD_TO_START,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { if (c.isPlaying) c.pause() else c.play() },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = if (ps.playing) "Pause" else "Play"
                    },
                ) {
                    Icon(
                        if (ps.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(
                    onClick = { c.seekToNextMediaItem() },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Next track" },
                ) { Icon(Icons.Filled.SkipNext, null) }
            }
        }
    }
}

@Composable
fun EqPanel() {
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    var chameleon by remember(state.eqChameleon) { mutableStateOf(state.eqChameleon) }
    val gains = remember(state.eqPreset) { PulseEq.FACTORY_8[state.eqPreset] ?: FloatArray(8) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Equalizer — in-player only", style = MaterialTheme.typography.titleMedium)
        PulseEq.FACTORY_8.keys.forEach { name ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { app.store.update { it.copy(eqPreset = name) } }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    Modifier.weight(1f),
                    color = if (name == state.eqPreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (name == state.eqPreset) Text("●", color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Chameleon (follow genre)")
            Switch(
                checked = chameleon,
                onCheckedChange = {
                    chameleon = it
                    app.store.update { s -> s.copy(eqChameleon = it) }
                },
            )
        }
        Text(
            "Bands 60–8k Hz · ${gains.joinToString(" / ") { "${it.toInt()}dB" }}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
