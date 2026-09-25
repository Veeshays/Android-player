package com.veeshays.pulse.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.veeshays.pulse.models.PersistedState
import com.veeshays.pulse.ui.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = Brand.PREFS_NAME)
private val STATE_KEY = stringPreferencesKey("state")

/**
 * Single-writer persisted state. Mirrors PC `pulse-v1` semantics (caps,
 * schema stamp, path-keyed maps) but fixes the PC P1 flaw: PC rebuilds and
 * re-serializes the whole track cache on EVERY save (O(n) jank at 1k+).
 * Here saves are DEBOUNCED (400ms) and only the small state JSON is written —
 * the media index itself lives in MediaStore, never in prefs.
 */
class PulseStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PersistedState())
    val state: StateFlow<PersistedState> = _state

    private var saveJob: kotlinx.coroutines.Job? = null

    suspend fun load() {
        val raw = context.dataStore.data.map { it[STATE_KEY] }.first()
        if (raw != null) {
            runCatching { _state.value = json.decodeFromString<PersistedState>(raw) }
                .onFailure { _state.value = PersistedState() } // corrupt prefs → clean boot, never crash
        }
    }

    /** Debounced write: rotation/backgrounding/position-ticks don't block UI. */
    fun update(transform: (PersistedState) -> PersistedState) {
        _state.value = enforceCaps(transform(_state.value))
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            runCatching {
                context.dataStore.edit { it[STATE_KEY] = json.encodeToString(_state.value) }
            }
        }
    }

    /** Immediate write for lifecycle edges (onPause/onTaskRemoved). */
    fun flush() {
        saveJob?.cancel()
        scope.launch {
            runCatching {
                context.dataStore.edit { it[STATE_KEY] = json.encodeToString(_state.value) }
            }
        }
    }

    private fun enforceCaps(s: PersistedState): PersistedState {
        fun <K, V> cap(m: Map<K, V>, n: Int) =
            if (m.size <= n) m else m.entries.toList().takeLast(n).associate { it.key to it.value }
        return s.copy(
            positions = cap(s.positions, 200),
            ratings = cap(s.ratings, 500),
            playCounts = cap(s.playCounts, 500),
            history = s.history.takeLast(100),
            tagOverrides = cap(s.tagOverrides, 300),
            // Unknown perf values (older/newer schemas) fall back to Normal.
            perfMode = if (s.perfMode == "EFFICIENT") "EFFICIENT" else "NORMAL",
        )
    }
}
