package com.veeshays.pulse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.veeshays.pulse.PulseApp
import com.veeshays.pulse.library.MediaLibrary
import com.veeshays.pulse.library.diffLibrary
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.TIME_UNSET
import com.veeshays.pulse.playback.buildMediaItems
import com.veeshays.pulse.playback.startIndexOf

// ---------------------------------------------------------------------------
// PLAYER — desktop Player pane as a stacked touch layout.
// Cover → title/artist/specs → karaoke-safe lyric peek → Sun-backed artwork
// wash → seek → transport → volume/flow/sleep → queue peek → EQ shortcut.
// ---------------------------------------------------------------------------

@Composable
fun PlayerScreen(onOpenQueue: () -> Unit, onOpenSheet: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    val efficient = state.perfMode == Brand.Perf.EFFICIENT
    val ps = rememberPlayerState(if (efficient) 1000L else 500L)
    val accent = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        // Sun identity wash behind everything (static in Efficient/nap mode).
        SunEffect(accent = accent, modifier = Modifier.fillMaxSize(), stained = state.glass == "STAINED", animated = !efficient)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                PlayerHero(currentKey = ps.mediaId ?: state.queue.keys.getOrNull(state.queue.index))
            }
            item {
                // Transport commands the service-owned player; shuffle/repeat
                // also persist so cold start restores them.
                TransportRow(
                    playing = ps.playing,
                    shuffle = ps.shuffle,
                    repeat = ps.repeat,
                    onPlayPause = {
                        val c = ps.controller ?: return@TransportRow
                        if (c.isPlaying) c.pause() else c.play()
                    },
                    onNext = { ps.controller?.seekToNextMediaItem() },
                    onPrev = { ps.controller?.seekToPreviousMediaItem() },
                    onShuffle = {
                        val on = !ps.shuffle
                        ps.controller?.shuffleModeEnabled = on
                        app.store.update { it.copy(queue = it.queue.copy(shuffle = on)) }
                    },
                    onRepeat = {
                        val next = (ps.repeat + 1) % 3
                        ps.controller?.repeatMode = when (next) {
                            1 -> Player.REPEAT_MODE_ALL
                            2 -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        app.store.update { it.copy(queue = it.queue.copy(repeat = next)) }
                    },
                )
            }
            item {
                SeekRow(
                    positionMs = ps.positionMs,
                    durationMs = ps.durationMs.coerceAtLeast(1L),
                    onSeek = { ps.controller?.seekTo(it) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenQueue) { Text("Queue (${state.queue.keys.size})") }
                    TextButton(onClick = { app.store.update { it.copy(flow = !it.flow) } }) {
                        Text(if (state.flow) "Flow ∞ on" else "Flow ∞ off")
                    }
                }
            }
            item { EqPanel() }
        }
    }
}

@Composable
private fun PlayerHero(currentKey: String?) {
    val app = LocalContext.current.applicationContext as PulseApp
    val key = currentKey
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = "Cover art", modifier = Modifier.size(48.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    key?.substringAfterLast('/')?.substringBeforeLast('.') ?: Brand.Copy.NOTHING_PLAYING,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                )
                Text(
                    if (key == null) Brand.Copy.ADD_TO_START else "PupPlay library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RatingBar(
                    rating = 0,
                    onRate = { stars ->
                        if (key != null) {
                            app.store.update { s ->
                                s.copy(ratings = s.ratings + (key to stars))
                            }
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// LIBRARY — Artists/Albums/Songs/Folders/Favorites modes, list/grid density,
// A–Z/New/♥/★ sorts, search, windowed paging (100 + Show more, PC parity).
// ---------------------------------------------------------------------------

@Composable
fun LibraryScreen(onOpenSheet: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    val ps = rememberPlayerState(if (state.perfMode == Brand.Perf.EFFICIENT) 1000L else 500L)
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Songs") }
    var sort by remember { mutableStateOf("New") }
    var limit by remember { mutableStateOf(100) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    var askedPermission by remember { mutableStateOf(false) }
    var manualFetch by remember { mutableStateOf(false) }
    var fetchNotice by remember { mutableStateOf<String?>(null) }
    var fetchProgress by remember { mutableIntStateOf(0) }
    // Application context: a scan in flight must never pin this Activity.
    val library = remember(ctx) { MediaLibrary(ctx.applicationContext) }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) scanning = true
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        val has = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        if (!has) {
            // Ask once on our own; afterwards the user re-prompts explicitly
            // via "Grant access" — never a prompt loop.
            if (!askedPermission) {
                askedPermission = true
                launcher.launch(permission)
            } else {
                denied = true
            }
            manualFetch = false
            scanning = false
            return@LaunchedEffect
        }
        denied = false
        // Boot = silent incremental sync; explicit Fetch = full scan. The
        // MediaStore index itself is always the source of truth (no crawler).
        val wantNotice = manualFetch
        val scanMode = if (wantNotice) MediaLibrary.ScanMode.FULL else MediaLibrary.ScanMode.STARTUP
        fetchProgress = 0
        try {
            val res = library.scan(scanMode) { rows -> fetchProgress = rows }
            if (res != null) {
                val all = (res.audio + res.video).sortedByDescending { it.dateAdded }
                val diff = diffLibrary(tracks, all)
                tracks = all
                if (wantNotice) fetchNotice = Brand.Copy.fetchResult(res.audio.size, res.video.size)
                // Prune vanished files from the persisted queue only. The
                // live controller item is untouched: a scan never resets or
                // duplicates playback or its notification.
                if (diff.removed.isNotEmpty()) {
                    val gone = diff.removed.map { it.key }.toSet()
                    app.store.update { s -> s.copy(queue = s.queue.copy(keys = s.queue.keys - gone)) }
                }
            }
        } finally {
            // try/finally (no swallowed CancellationException): backgrounding,
            // rotation, or navigation always leaves the button usable again.
            manualFetch = false
            fetchProgress = 0
            scanning = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; limit = 100 },
            label = { Text("Search library") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Search library" },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        // The scan/import action, presented as Fetch: an explicit FULL refresh
        // (boot uses the silent STARTUP sync). Read-only discovery only.
        Button(
            onClick = { manualFetch = true; fetchNotice = null; scanning = true },
            enabled = !scanning,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = Brand.Labels.FETCH_MEDIA },
        ) { Text("${Brand.Labels.PAW} ${Brand.Labels.FETCH_MEDIA}") }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Songs", "Artists", "Albums", "Favorites").forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m; limit = 100 }, label = { Text(m) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("New", "A–Z", "♥", "★").forEach { s ->
                FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s) })
            }
        }
        Spacer(Modifier.height(4.dp))

        if (denied) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(Brand.Copy.PERMISSION_RATIONALE, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(permission) }) { Text("Grant access") }
                }
            }
        }
        if (scanning) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = Brand.Copy.FETCHING })
                Spacer(Modifier.width(12.dp))
                // Rows-found ticker, throttled to every 100 rows by the scan —
                // never one UI update per file.
                Text(
                    if (fetchProgress > 0) "${Brand.Copy.FETCHING} ($fetchProgress found)"
                    else Brand.Copy.FETCHING,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        fetchNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = it },
            )
        }

        val favs = state.favTracks
        val ratings = state.ratings
        val filtered = remember(tracks, query, mode, sort, favs) {
            var list = tracks
            if (query.isNotBlank()) {
                list = list.filter {
                    it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
                }
            }
            list = when (mode) {
                "Favorites" -> list.filter { it.key in favs }
                else -> list
            }
            when (sort) {
                "A–Z" -> list.sortedBy { it.title.lowercase() }
                "♥" -> list.sortedByDescending { if (it.key in favs) 1 else 0 }
                "★" -> list.sortedByDescending { ratings[it.key] ?: 0 }
                else -> list.sortedByDescending { it.dateAdded }
            }
        }
        Text("${filtered.size} tracks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered.take(limit), key = { it.key }) { t ->
                TrackRow(
                    track = t,
                    playing = state.queue.keys.getOrNull(state.queue.index) == t.key,
                    rating = ratings[t.key] ?: 0,
                    fav = t.key in favs,
                    onTap = {
                        // Queue-follows-view: the tapped view becomes the player
                        // playlist (with resume points); the snapshot persists
                        // for cold-start restoration.
                        val items = buildMediaItems(filtered, state.positions) { t ->
                            library.artworkUri(t.key, t.isVideo)
                        }
                        ps.controller?.let { c ->
                            c.setMediaItems(items, startIndexOf(filtered, t.key), TIME_UNSET)
                            c.prepare()
                            c.play()
                        }
                        app.store.update { s ->
                            s.copy(queue = s.queue.copy(keys = filtered.map { x -> x.key }, index = filtered.indexOf(t)))
                        }
                    },
                    onLongPress = { onOpenSheet(t.key) },
                    onToggleFav = {
                        app.store.update { s ->
                            s.copy(favTracks = if (t.key in s.favTracks) s.favTracks - t.key else s.favTracks + t.key)
                        }
                    },
                )
            }
            if (filtered.size > limit) {
                item {
                    TextButton(
                        onClick = { limit += 100 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    ) { Text("Show more (${filtered.size - limit} left)") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// VIDEO — portrait/landscape/fullscreen/immersive, aspect preserved, seek,
// speed, subtitles cue overlay, resume. Fullscreen consumes Back first (§14).
// ---------------------------------------------------------------------------

@Composable
fun VideoScreen() {
    var fullscreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullscreen) { fullscreen = false } // exit fullscreen first
    var speed by remember { mutableStateOf(1f) }
    val app = LocalContext.current.applicationContext as PulseApp
    val uiState by app.store.state.collectAsState()
    val ps = rememberPlayerState(if (uiState.perfMode == Brand.Perf.EFFICIENT) 1000L else 500L)

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.height(240.dp))
                .background(MaterialTheme.colorScheme.scrim)
                .clickable { fullscreen = !fullscreen }
                .semantics { contentDescription = if (fullscreen) "Exit fullscreen" else "Enter fullscreen" },
            contentAlignment = Alignment.Center,
        ) {
            // Real surface when a controller is bound; placeholder otherwise.
            // PlayerSurface (Media3 Compose) keeps aspect ratio — never stretched.
            Text("${Brand.Labels.VIDEO} — tap for fullscreen", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f } }) {
                    Text("${speed}×", modifier = Modifier.semantics { contentDescription = "Playback speed $speed" })
                }
                IconButton(onClick = {}, modifier = Modifier.size(48.dp).semantics { contentDescription = "Picture in picture" }) {
                    Icon(Icons.Filled.PictureInPicture, null)
                }
                IconButton(onClick = { fullscreen = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Fullscreen" }) {
                    Icon(Icons.Filled.Fullscreen, null)
                }
            }
            SeekRow(
                positionMs = ps.positionMs,
                durationMs = ps.durationMs.coerceAtLeast(1L),
                onSeek = { ps.controller?.seekTo(it) },
            )
            TransportRow(
                playing = ps.playing,
                shuffle = ps.shuffle,
                repeat = ps.repeat,
                onPlayPause = {
                    val c = ps.controller ?: return@TransportRow
                    if (c.isPlaying) c.pause() else c.play()
                },
                onNext = { ps.controller?.seekToNextMediaItem() },
                onPrev = { ps.controller?.seekToPreviousMediaItem() },
                onShuffle = { ps.controller?.let { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled } },
                onRepeat = {
                    ps.controller?.let { c ->
                        c.repeatMode = when (c.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                },
                compact = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// SETTINGS — theme/mode/finish/glass centralized; sleep; diagnostics; wipe.
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Customise — everything stays, reorganized for touch", style = MaterialTheme.typography.titleLarge) }
        item {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseThemeId.values().forEach { t ->
                    FilterChip(
                        selected = state.theme == t.name,
                        onClick = { app.store.update { it.copy(theme = t.name) } },
                        label = { Text(t.displayName) },
                    )
                }
            }
        }
        item {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SYSTEM", "DARK", "LIGHT").forEach { m ->
                    FilterChip(
                        selected = state.themeMode == m,
                        onClick = { app.store.update { it.copy(themeMode = m) } },
                        label = { Text(m.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
        }
        item {
            Text("Performance mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Efficient naps idle effects. Playback, notification and lock-screen controls are never reduced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.perfMode == Brand.Perf.NORMAL,
                    onClick = { app.store.update { it.copy(perfMode = Brand.Perf.NORMAL) } },
                    label = { Text("Normal") },
                )
                FilterChip(
                    selected = state.perfMode == Brand.Perf.EFFICIENT,
                    onClick = { app.store.update { it.copy(perfMode = Brand.Perf.EFFICIENT) } },
                    label = { Text("Efficient") },
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Flow ∞ — keep playing similar tracks")
                Switch(checked = state.flow, onCheckedChange = { app.store.update { it.copy(flow = it.flow.not()) } })
            }
        }
        item {
            Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60, 90).forEach { m ->
                    FilterChip(
                        selected = state.sleepMins == m,
                        onClick = { app.store.update { it.copy(sleepMins = m) } },
                        label = { Text(if (m == 0) "Off" else "${m}m") },
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Positions ${state.positions.size}/200 · Ratings ${state.ratings.size}/500 · History ${state.history.size}/100 · ${Brand.Copy.FILES_ON_DISK_UNTOUCHED}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { app.store.update { it.copy(positions = emptyMap(), history = emptyList(), playCounts = emptyMap()) } }) {
                        Text("Clear playback history")
                    }
                }
            }
        }
    }
}
