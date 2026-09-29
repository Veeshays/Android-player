package com.veeshays.pulse.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.veeshays.pulse.PulseApp
import com.veeshays.pulse.playback.PlaybackConnection
import com.veeshays.pulse.ui.SunEffect

/**
 * Single-activity shell. PC mapping (same product → mobile layout):
 *  - desktop sidebar → bottom navigation + modal NavigationDrawer (library nav)
 *  - desktop tabs → bottom-nav destinations (Player/Library/Video/Lyrics/EQ/Settings
 *    collapse to 4 bottom items; Lyrics/EQ live inside Player + Settings hub)
 *  - desktop context menus → bottom sheets (Components.kt)
 *  - desktop keyboard shortcuts → touch actions + Back handling below
 *
 * Back contract (§14): fullscreen video → exit fullscreen; open sheet →
 * dismiss; queue open → close; nested nav → pop; root → background (NEVER
 * kill playback — service owns it, Back only affects UI).
 * State restoration (§15): DataStore queue/position/screen re-applied on
 * cold start; stale keys (file gone) pruned, never restored blindly.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // One UI edge-to-edge; Scaffold insets keep controls clear
        PlaybackConnection.connect(this)
        setContent {
            val app = application as PulseApp
            val state by app.store.state.collectAsState()
            PulseTheme(
                theme = PulseThemeId.fallback(state.theme),
                mode = runCatching { ThemeMode.valueOf(state.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
            ) {
                PulseScaffold(savedInstanceState?.getString("screen"))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", currentScreen)
    }

    override fun onPause() {
        super.onPause()
        (application as PulseApp).store.flush() // rotation/background safe
    }

    override fun onDestroy() {
        PlaybackConnection.release()
        super.onDestroy()
    }

    companion object {
        @Volatile var currentScreen: String? = null
    }
}

private enum class Dest(val label: String) {
    Player(Brand.Labels.MUSIC), Library("Library"), Eq("EQ"), Settings("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PulseScaffold(startScreen: String?) {
    var dest by remember { mutableStateOf(runCatching { Dest.valueOf(startScreen ?: "Player") }.getOrDefault(Dest.Player)) }
    var queueOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val app = LocalContext.current.applicationContext as PulseApp
    val prefs by app.store.state.collectAsState()
    // One shared observation point: every surface reads the same player truth.
    val ps = rememberPlayerState(if (prefs.perfMode == Brand.Perf.EFFICIENT) 1000L else 500L)
    val snackbar = remember { SnackbarHostState() }
    // Playback failures surface app-wide (service, tap, connection) — never silent.
    LaunchedEffect(Unit) {
        PlaybackConnection.errors.collect { snackbar.showSnackbar(it) }
    }
    fun goPlayer() {
        dest = Dest.Player
        MainActivity.currentScreen = Dest.Player.name
    }

    // Back handling is owned by each screen via BackHandler (fullscreen → sheet
    // → queue → nested → bg). Root Back falls through to system backgrounding.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                // Paw = PupPlay app mark (TalkBack names the app); the title
                // names the current section (PopTune/Library/PupScreen/...).
                navigationIcon = {
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .semantics { contentDescription = "PupPlay" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Brand.Labels.PAW, style = MaterialTheme.typography.titleLarge)
                    }
                },
                title = {
                    Text(
                        when (dest) {
                            Dest.Player -> Brand.Labels.MUSIC
                            Dest.Library -> "Library"
                            Dest.Eq -> "EQ"
                            Dest.Settings -> "Settings"
                        },
                    )
                },
            )
        },
        bottomBar = {
            Column {
                // Slim persistent player on non-Player tabs; the Player tab
                // owns its full fixed chrome. Navigation never moves.
                if (dest != Dest.Player) MiniPlayer(ps, ::goPlayer)
                NavigationBar {
                    listOf(
                        Dest.Player to Icons.Filled.PlayCircle,
                        Dest.Library to Icons.Filled.LibraryMusic,
                        Dest.Eq to Icons.Filled.Tune,
                        Dest.Settings to Icons.Filled.Settings,
                    ).forEach { (d, icon) ->
                        NavigationBarItem(
                            selected = dest == d,
                            onClick = {
                                dest = d
                                MainActivity.currentScreen = d.name
                            },
                            icon = {
                                Icon(
                                    icon,
                                    contentDescription = d.label,
                                )
                            },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when (dest) {
                Dest.Player -> PlayerScreen(
                    ps = ps,
                    onOpenQueue = { queueOpen = true },
                    onOpenSheet = { sheet = it },
                )
                Dest.Library -> LibraryScreen(ps = ps, onOpenSheet = { sheet = it })
                Dest.Eq -> EqScreen()
                Dest.Settings -> SettingsScreen(ps = ps)
            }
            if (queueOpen) QueueSheet(ps = ps, onDismiss = { queueOpen = false })
            sheet?.let { ContextSheet(key = it, ps = ps, onDismiss = { sheet = null }) }
        }
    }
}

// Re-exported for tests wearing a Compose hat.
@Composable
fun PersonPlaceholder() {
    Icon(Icons.Filled.Person, contentDescription = null)
}
