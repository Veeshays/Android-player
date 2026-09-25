package com.veeshays.pulse.playback

import com.veeshays.pulse.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Queue-follows-view, ported from PC app.js (`visibleTracks`/`select`/`step`).
 * Every library view plays as a unit; queue IS the view snapshot plus
 * play-next/shuffle edits. PC semantics preserved:
 *  - shuffle / repeat off→all→one / Flow ∞ (same artist → folder fallback)
 *  - history (100) + playCounts (500) + resume bookmarks (pos>8s, remaining>15s)
 *  - corrupt-file auto-skip with streak guard (3 consecutive → stop, not loop)
 *  - add/remove/reorder/play-next/clear, persistent via PulseStore snapshot
 * Touch safety: reorder/remove never auto-play (explicit tap-to-play only).
 */
class QueueManager {

    data class UiState(
        val keys: List<String> = emptyList(),
        val index: Int = -1,
        val shuffle: Boolean = false,
        val repeat: Int = 0,
        val sleepMins: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Played order when shuffle is on (shuffled key list); identity = keys. */
    private var playOrder: List<String> = emptyList()
    private var errStreak = 0

    val currentKey: String? get() = _ui.value.let { s -> s.keys.getOrNull(s.index) }

    /** Play a view as a unit (PC select(): trackNo-sorted view → queue). */
    fun playView(tracks: List<Track>, startKey: String?) {
        val keys = tracks.map { it.key }
        val idx = keys.indexOf(startKey).takeIf { it >= 0 } ?: 0
        _ui.value = _ui.value.copy(keys = keys, index = if (keys.isEmpty()) -1 else idx)
        rebuildOrder()
        errStreak = 0
    }

    fun addToQueue(key: String) {
        _ui.value = _ui.value.copy(keys = _ui.value.keys + key)
        rebuildOrder()
    }

    fun playNext(key: String) {
        val s = _ui.value
        val at = (s.index + 1).coerceIn(0, s.keys.size)
        _ui.value = s.copy(keys = s.keys.toMutableList().also { it.add(at, key) })
        rebuildOrder()
    }

    fun removeAt(at: Int) {
        val s = _ui.value
        if (at !in s.keys.indices) return
        val keys = s.keys.toMutableList().also { it.removeAt(at) }
        var idx = s.index
        if (at < idx) idx--
        if (at == idx) idx = idx.coerceAtMost(keys.lastIndex) // removing current: stay in range, no auto-play
        _ui.value = s.copy(keys = keys, index = if (keys.isEmpty()) -1 else idx.coerceAtLeast(0))
        rebuildOrder()
    }

    fun move(from: Int, to: Int) {
        val s = _ui.value
        if (from !in s.keys.indices || to !in s.keys.indices) return
        val keys = s.keys.toMutableList()
        val cur = keys.getOrNull(s.index)
        keys.add(to, keys.removeAt(from))
        _ui.value = s.copy(keys = keys, index = keys.indexOf(cur).takeIf { it >= 0 } ?: 0)
        rebuildOrder()
    }

    fun clear() {
        _ui.value = _ui.value.copy(keys = emptyList(), index = -1)
        playOrder = emptyList()
    }

    fun toggleShuffle(): Boolean {
        val cur = currentKey
        val on = !_ui.value.shuffle
        _ui.value = _ui.value.copy(shuffle = on)
        rebuildOrder()
        // Keep the current item stable across the toggle (PC behavior).
        if (cur != null) {
            val idx = if (on) playOrder.indexOf(cur) else _ui.value.keys.indexOf(cur)
            if (idx >= 0) _ui.value = _ui.value.copy(index = idx)
        }
        return on
    }

    fun cycleRepeat(): Int {
        val r = (_ui.value.repeat + 1) % 3
        _ui.value = _ui.value.copy(repeat = r)
        return r
    }

    /** Advance. Returns next key or null (repeat-one handled by player seek-to-0). */
    fun step(by: Int, indexOf: (String) -> Int = { k -> _ui.value.keys.indexOf(k) }): String? {
        val s = _ui.value
        if (s.keys.isEmpty()) return null
        if (s.repeat == 2 && by == 1 && errStreak == 0) return s.keys.getOrNull(s.index)
        val order = if (s.shuffle) playOrder else s.keys
        var pos = if (s.shuffle) order.indexOf(s.keys.getOrNull(s.index)) else s.index
        if (pos < 0) pos = 0
        var n = pos + by
        if (n < 0) n = if (s.repeat == 1) order.lastIndex else 0
        if (n > order.lastIndex) {
            if (s.repeat == 1) n = 0
            else return null // queue ended (Flow ∞ handled by caller with library context)
        }
        val key = order[n]
        val realIdx = indexOf(key).takeIf { it >= 0 } ?: n
        _ui.value = s.copy(index = realIdx)
        return key
    }

    /** Error streak guard: 3 consecutive failures → caller stops, surfaces one message. */
    fun reportError(): Boolean {
        errStreak++
        return errStreak < 3
    }

    fun reportOk() {
        errStreak = 0
    }

    /** Resume guards — PC constants: start 8s, 15s from end (proportional floors for short media). */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean =
        shouldResumeAt(positionMs, durationMs)

    companion object {
        /** Pure guard shared by the queue and the playlist builder (unit-tested). */
        fun shouldResumeAt(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0) return positionMs > 8_000
            val start = minOf(8_000L, (durationMs * 0.15).toLong())
            val end = minOf(15_000L, (durationMs * 0.25).toLong())
            return positionMs > start && durationMs - positionMs > end
        }
    }

    private fun rebuildOrder() {
        playOrder = if (_ui.value.shuffle) _ui.value.keys.shuffled() else _ui.value.keys
    }
}
