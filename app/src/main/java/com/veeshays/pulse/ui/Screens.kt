@file:OptIn(ExperimentalFoundationApi::class, androidx.media3.common.util.UnstableApi::class)

package com.veeshays.pulse.ui

// NOTE: group headers are plain items, not stickyHeader(): the resolved
// Compose-foundation classpath in this project does not expose that symbol
// (duplicate foundation artifacts on the compile classpath), so sticky
// headers are deliberately avoided rather than fought.

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons


import androidx.compose.material.icons.filled.Pause

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp



import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters

import com.veeshays.pulse.PulseApp
import com.veeshays.pulse.library.MediaLibrary
import com.veeshays.pulse.library.diffLibrary
import com.veeshays.pulse.lyrics.LyricOutcome
import com.veeshays.pulse.lyrics.LyricResult
import com.veeshays.pulse.lyrics.LyricStore
import com.veeshays.pulse.lyrics.currentLine
import com.veeshays.pulse.lyrics.fetchLyrics
import com.veeshays.pulse.models.Track
import com.veeshays.pulse.playback.TIME_UNSET
import com.veeshays.pulse.playback.DiagnosticsCard
import com.veeshays.pulse.playback.PlaybackConnection
import com.veeshays.pulse.playback.buildMediaItems
import com.veeshays.pulse.playback.probeReadable
import com.veeshays.pulse.playback.startIndexOf
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// PLAYER — desktop Player pane as a stacked touch layout.
// Cover → title/artist/specs → karaoke-safe lyric peek → Sun-backed artwork
// wash → seek → transport → volume/flow/sleep → queue peek → EQ shortcut.
// ---------------------------------------------------------------------------

@Composable
fun PlayerScreen(ps: PlayerUiState, onOpenQueue: () -> Unit, onOpenSheet: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    val efficient = state.perfMode == Brand.Perf.EFFICIENT
    val accent = MaterialTheme.colorScheme.primary
    var lyricsOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Sun identity wash behind everything (static in Efficient/nap mode).
        SunEffect(accent = accent, modifier = Modifier.fillMaxSize(), stained = state.glass == "STAINED", animated = !efficient)
        Column(Modifier.fillMaxSize()) {
            // Scrollable identity zone: artwork → title/metadata.
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    PlayerHero(ps = ps)
                }
            }
            // FIXED bottom player area — progress always lives here with the
            // transport, never floating mid-screen (§3 hierarchy).
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SeekRow(
                    positionMs = ps.positionMs,
                    durationMs = ps.durationMs.coerceAtLeast(1L),
                    onSeek = { ps.controller?.seekTo(it) },
                )
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenQueue) {
                        val n = ps.controller?.mediaItemCount ?: state.queue.keys.size
                        Text(if (n > 0) "Queue ($n)" else "Queue")
                    }
                    TextButton(onClick = { lyricsOpen = true }) { Text("Lyrics") }
                    TextButton(onClick = { app.store.update { it.copy(flow = !it.flow) } }) {
                        Text(if (state.flow) "Flow ∞ on" else "Flow ∞ off")
                    }
                }
            }
            if (lyricsOpen) LyricsSheet(ps = ps, onDismiss = { lyricsOpen = false })
        }
    }
}

@Composable
private fun PlayerHero(ps: PlayerUiState) {
    val app = LocalContext.current.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
    val key = ps.mediaId ?: state.queue.keys.getOrNull(state.queue.index)
    val title = ps.title?.ifBlank { null }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Artwork with a soft accent glow (desktop hero treatment, one static layer).
            Box(
                modifier = Modifier.shadow(
                    16.dp, RoundedCornerShape(16.dp),
                    ambientColor = MaterialTheme.colorScheme.primary,
                    spotColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                ArtThumb(ps.artUri, 96.dp, "Cover art")
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    // Never render a URI tail ("8890") as a title: without live
                    // metadata there is nothing truthful to show.
                    title ?: if (key == null) Brand.Copy.NOTHING_PLAYING else "Unknown title",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ps.artist?.ifBlank { null }
                        ?: ps.album?.ifBlank { null }
                        ?: Brand.Copy.ADD_TO_START,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                RatingBar(
                    rating = if (key != null) state.ratings[key] ?: 0 else 0,
                    onRate = { stars ->
                        if (key != null) {
                            app.store.update { s ->
                                s.copy(ratings = if (stars == 0) s.ratings - key else s.ratings + (key to stars))
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
fun LibraryScreen(ps: PlayerUiState, onOpenSheet: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PulseApp
    val state by app.store.state.collectAsState()
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
    val scope = rememberCoroutineScope()

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
            // Music-only library: video volume skipped (engine keeps support).
            val res = library.scan(scanMode, { rows -> fetchProgress = rows }, includeVideo = false)
            if (res != null) {
                val all = res.audio.sortedByDescending { it.dateAdded }
                val diff = diffLibrary(tracks, all)
                tracks = all
                if (wantNotice) {
                    // Full scan report: rows seen → mapped / skipped-unreadable /
                    // skipped-unsupported / truncated / added / removed / visible.
                    val parts = mutableListOf(
                        Brand.Copy.fetchResult(res.audio.size, res.video.size),
                    )
                    parts += "${res.rowsSeen} rows"
                    if (res.skippedUnreadable > 0) parts += "${res.skippedUnreadable} unreadable"
                    if (res.skippedUnsupported > 0) parts += "${res.skippedUnsupported} unsupported"
                    if (res.truncated) parts += "truncated at ${MediaLibrary.MAX_ITEMS}"
                    if (diff.added.isNotEmpty()) parts += "${diff.added.size} new"
                    if (diff.removed.isNotEmpty()) parts += "${diff.removed.size} gone"
                    parts += "${all.size} visible"
                    fetchNotice = parts.joinToString(" · ")
                }
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

    Column(
        Modifier
            .fillMaxSize()
            // Restrained depth: static vertical wash (free — no animation clock).
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface,
                    1f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            )
            .padding(12.dp),
    ) {
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf("Songs", "Artists", "Albums", "Recent", "Favorites").forEach { m ->
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
        val history = state.history

        // Queue-follows-view: the tapped list becomes the player playlist
        // (with resume points); the snapshot persists for cold start.
        // setMediaItems REPLACES the whole timeline, so whatever was active
        // before (including any stale item) is gone after this call.
        fun playTracks(list: List<Track>, start: Track) {
            scope.launch {
                // Documented MediaStore access model: prove the content URI
                // opens BEFORE it reaches Media3, so an unreadable file
                // reports explicitly instead of stalling in BUFFERING.
                // TEMP-TRACE (device-debug session): structured tap line for
                // logcat — title/URI/MIME/duration/album/readability. Remove
                // after the real-song device pass.
                val probe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    probeReadable(ctx.contentResolver, start.key)
                }
                val tapLine = "TAP title=${start.title} uri=${start.key} " +
                    "mime=${probe.mime} durMs=${start.durationMs} " +
                    "albumId=${start.albumId} readable=${probe.readable} " +
                    "bytes=${probe.bytesRead} err=${probe.error} " +
                    "queueSize=${list.size} startIdx=${startIndexOf(list, start.key)}"
                android.util.Log.i("PupPlayTrace", tapLine)
                PlaybackConnection.recordTransition(tapLine)
                if (!probe.readable) {
                    PlaybackConnection.reportError(
                        "File unreadable (${probe.error}) — it may have been deleted",
                    )
                    return@launch
                }
                val items = buildMediaItems(list, state.positions) { t ->
                    library.artworkUri(t)
                }
                ps.controller?.let { c ->
                runCatching {
                    c.setMediaItems(items, startIndexOf(list, start.key), TIME_UNSET)
                    c.prepare()
                    c.play()
                    android.util.Log.i(
                        "PupPlayTrace",
                        "HANDOFF items=${items.size} idx=${c.currentMediaItemIndex} " +
                            "mediaId=${c.currentMediaItem?.mediaId} " +
                            "uri=${c.currentMediaItem?.localConfiguration?.uri}",
                    )
                }.onFailure {
                    android.util.Log.w("PupPlayTrace", "HANDOFF failed", it)
                    PlaybackConnection.reportError(
                        "Couldn't start playback (${it.javaClass.simpleName}: ${it.message}) — tap again",
                    )
                }
                // Watchdog: a prepared player leaves IDLE almost immediately.
                // Still IDLE after 2.5s means genuinely stuck — report once.
                scope.launch {
                    kotlinx.coroutines.delay(2500)
                    if (c.playbackState == Player.STATE_IDLE) {
                        PlaybackConnection.reportError("Playback didn't start — check the file format")
                    }
                }
                } ?: run {
                // Never fail silently: (re)start the engine and say so.
                PlaybackConnection.connect(ctx)
                PlaybackConnection.reportError("Starting playback engine — tap once more")
                }
                app.store.update { s ->
                s.copy(queue = s.queue.copy(keys = list.map { x -> x.key }, index = list.indexOf(start)))
                }
            }
        }

        val searched = remember(tracks, query) {
            if (query.isBlank()) tracks
            else tracks.filter {
                it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
            }
        }
        val scoped = remember(searched, mode, favs, history) {
            when (mode) {
                "Favorites" -> searched.filter { it.key in favs }
                "Recent" -> history.asReversed().mapNotNull { k -> searched.find { it.key == k } }.distinct()
                else -> searched
            }
        }
        val ordered = remember(scoped, sort, favs, ratings) {
            when (sort) {
                "A–Z" -> scoped.sortedBy { it.title.lowercase() }
                "♥" -> scoped.sortedByDescending { if (it.key in favs) 1 else 0 }
                "★" -> scoped.sortedByDescending { ratings[it.key] ?: 0 }
                else -> scoped.sortedByDescending { it.dateAdded }
            }
        }
        // Artists/Albums render as sticky group headers with play-all.
        val groups: List<Pair<String, List<Track>>>? = remember(ordered, mode) {
            when (mode) {
                "Artists" -> ordered.groupBy { it.artist.ifBlank { "Unknown artist" } }
                    .toList().sortedBy { it.first.lowercase() }
                "Albums" -> ordered.groupBy { it.album.ifBlank { "Unknown album" } }
                    .toList().sortedBy { it.first.lowercase() }
                else -> null
            }
        }
        val flatCount = groups?.sumOf { it.second.size } ?: ordered.size
        val unit = "tracks"
        // Desktop hierarchy nod: letterspaced small-caps count line.
        Text(
            "$flatCount $unit".uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        @Composable
        fun queueRow(t: Track, list: List<Track>) {
            TrackRow(
                track = t,
                playing = ps.mediaId == t.key,
                rating = ratings[t.key] ?: 0,
                fav = t.key in favs,
                onTap = { playTracks(list, t) },
                onLongPress = { onOpenSheet(t.key) },
                onToggleFav = {
                    app.store.update { s ->
                        s.copy(favTracks = if (t.key in s.favTracks) s.favTracks - t.key else s.favTracks + t.key)
                    }
                },
            )
        }

        if (!scanning && ordered.isEmpty()) {
            // Sensible empty state per mode — Fetch stays one tap away above.
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        when {
                            tracks.isEmpty() -> "${Brand.Labels.PAW} Your library is empty"
                            query.isNotBlank() -> "No matches for “$query”"
                            mode == "Favorites" -> "No favorites yet — tap ♡ on anything"
                            mode == "Recent" -> "Nothing played yet"
                            else -> "Nothing here yet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (tracks.isEmpty()) Brand.Copy.LIBRARY_EMPTY
                        else "Try another search, or Fetch again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (groups != null) {
                groups.forEach { (name, members) ->
                    item(key = "h-$name") {
                        Surface(tonalElevation = 2.dp) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$name · ${members.size}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { if (members.isNotEmpty()) playTracks(members, members[0]) },
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = "Play all by $name"
                                    },
                                ) { Icon(Icons.Filled.PlayArrow, null) }
                            }
                        }
                    }
                    items(members.take(limit), key = { it.key }) { t -> queueRow(t, members) }
                }
            } else {
                items(ordered.take(limit), key = { it.key }) { t -> queueRow(t, ordered) }
            }
            if (ordered.size > limit) {
                item {
                    TextButton(
                        onClick = { limit += 100 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    ) { Text("Show more (${ordered.size - limit} left)") }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// LYRICS — dedicated sheet (LRCLIB lookup + local cache). Loading never
// blocks playback; missing lyrics and network failure get distinct states.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(ps: PlayerUiState, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val ctx = LocalContext.current
    val store = remember(ctx) { LyricStore(ctx.applicationContext) }
    val artist = ps.artist?.trim().orEmpty()
    val title = ps.title?.trim().orEmpty()
    var outcome by remember(ps.mediaId) { mutableStateOf<LyricOutcome?>(null) }
    var attempt by remember(ps.mediaId) { mutableIntStateOf(0) }

    LaunchedEffect(ps.mediaId, attempt) {
        if (artist.isBlank() || title.isBlank()) {
            outcome = LyricOutcome.NotFound
            return@LaunchedEffect
        }
        store.get(artist, title)?.let {
            outcome = LyricOutcome.Found(it, fromCache = true)
            return@LaunchedEffect
        }
        val found = fetchLyrics(artist, title, (ps.durationMs / 1000).coerceAtLeast(0L))
        if (found is LyricOutcome.Found) store.put(artist, title, found.result)
        outcome = found
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Lyrics", style = MaterialTheme.typography.titleLarge)
            Text(
                if (title.isBlank()) "Nothing playing" else "$title — ${artist.ifBlank { "Unknown artist" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            when (val o = outcome) {
                null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading lyrics" })
                    Spacer(Modifier.width(12.dp))
                    Text("Searching lyrics…")
                }
                is LyricOutcome.Found -> LyricBody(result = o.result, positionMs = ps.positionMs)
                LyricOutcome.NotFound -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text(
                        if (artist.isBlank() || title.isBlank()) "Play a track with artist + title metadata to look up lyrics."
                        else "No lyrics found for this track.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is LyricOutcome.Offline -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text(o.reason + " — playback is unaffected.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
            }
            Text(
                "Lyrics via LRCLIB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LyricBody(result: LyricResult, positionMs: Long) {
    if (result.synced.isNotEmpty()) {
        val cur = currentLine(result.synced, positionMs)
        val listState = rememberLazyListState()
        LaunchedEffect(cur) {
            if (cur >= 0) runCatching { listState.animateScrollToItem(maxOf(0, cur - 2)) }
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
            items(result.synced.size) { i ->
                val line = result.synced[i]
                Text(
                    line.text,
                    style = if (i == cur) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = if (i == cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .semantics { contentDescription = if (i == cur) "Current line: ${line.text}" else line.text },
                )
            }
        }
    } else {
        Text(
            result.plain.ifBlank { "No lyrics found for this track." },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------------------------------------------------------------------------
// EQ — dedicated destination; the Player stays focused on artwork →
// title → progress → transport. Same panel, same persisted state.
// ---------------------------------------------------------------------------

@Composable
fun EqScreen() {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { EqPanel() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// SETTINGS — theme/mode/finish/glass centralized; sleep; diagnostics; wipe.
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(ps: PlayerUiState) {
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
        item { DiagnosticsCard(ps = ps) }
    }
}
