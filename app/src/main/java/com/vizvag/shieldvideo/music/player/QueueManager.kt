package com.vizvag.shieldvideo.music.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore("playback_queue")

class QueueManager constructor(
    private val context: Context,
) {
    private val gson = Gson()
    private val _queue = MutableStateFlow<List<TrackEntity>>(emptyList())
    val queue: StateFlow<List<TrackEntity>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    val currentTrack: TrackEntity?
        get() = _queue.value.getOrNull(_currentIndex.value)

    suspend fun loadPersisted() {
        val prefs = context.queueDataStore.data.first()
        val json = prefs[QUEUE_KEY] ?: return
        val type = object : TypeToken<List<TrackEntity>>() {}.type
        val tracks: List<TrackEntity> = gson.fromJson(json, type)
        _queue.value = tracks
        _currentIndex.value = prefs[CURRENT_INDEX_KEY] ?: -1
    }

    suspend fun persist() {
        context.queueDataStore.edit { prefs ->
            prefs[QUEUE_KEY] = gson.toJson(_queue.value)
            prefs[CURRENT_INDEX_KEY] = _currentIndex.value
        }
    }

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        _queue.value = tracks
        _currentIndex.value = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    }

    fun playNext(track: TrackEntity) {
        val list = _queue.value.toMutableList()
        val insertAt = (_currentIndex.value + 1).coerceAtMost(list.size)
        list.add(insertAt, track)
        _queue.value = list
    }

    fun addToEnd(track: TrackEntity) {
        _queue.update { it + track }
    }

    fun addAllToEnd(tracks: List<TrackEntity>) {
        _queue.update { it + tracks }
    }

    /** Replace a queued track (same id or nas path) with richer metadata. */
    fun updateTrackMetadata(updated: TrackEntity) {
        _queue.update { list ->
            list.map { existing ->
                when {
                    existing.id == updated.id -> updated
                    existing.nasPath.replace('\\', '/') ==
                        updated.nasPath.replace('\\', '/') -> updated
                    else -> existing
                }
            }
        }
    }

    fun removeAt(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _queue.value = list
        when {
            list.isEmpty() -> _currentIndex.value = -1
            index < _currentIndex.value -> _currentIndex.value -= 1
            index == _currentIndex.value -> _currentIndex.value = index.coerceAtMost(list.lastIndex)
        }
    }

    fun move(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
        val current = _currentIndex.value
        _currentIndex.value = when (current) {
            from -> to
            in (minOf(from, to) + 1)..maxOf(from, to) -> if (from < to) current - 1 else current + 1
            else -> current
        }
    }

    fun clear() {
        _queue.value = emptyList()
        _currentIndex.value = -1
    }

    /** Shuffle upcoming tracks; keep the current track at the front. */
    fun shuffleKeepingCurrent() {
        val list = _queue.value
        if (list.size < 2) return
        val cur = _currentIndex.value
        if (cur in list.indices) {
            val current = list[cur]
            val rest = list.filterIndexed { i, _ -> i != cur }.shuffled()
            _queue.value = listOf(current) + rest
            _currentIndex.value = 0
        } else {
            _queue.value = list.shuffled()
            _currentIndex.value = 0
        }
    }

    fun toggleShuffle() {
        _shuffle.update { !it }
    }

    fun cycleRepeat() {
        _repeat.update {
            when (it) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        }
    }

    fun nextIndex(): Int? {
        val list = _queue.value
        if (list.isEmpty()) return null
        return when (_repeat.value) {
            RepeatMode.ONE -> _currentIndex.value
            RepeatMode.ALL -> {
                if (_shuffle.value) {
                    if (list.size == 1) 0 else (list.indices - _currentIndex.value).random()
                } else {
                    (_currentIndex.value + 1) % list.size
                }
            }
            RepeatMode.OFF -> {
                val next = _currentIndex.value + 1
                if (next < list.size) next else null
            }
        }
    }

    fun previousIndex(): Int? {
        val list = _queue.value
        if (list.isEmpty()) return null
        val prev = _currentIndex.value - 1
        return if (prev >= 0) prev else if (_repeat.value == RepeatMode.ALL) list.lastIndex else null
    }

    fun advanceTo(index: Int) {
        if (index in _queue.value.indices) _currentIndex.value = index
    }

    companion object {
        private val QUEUE_KEY = stringPreferencesKey("queue_json")
        private val CURRENT_INDEX_KEY = intPreferencesKey("current_index")
    }
}

enum class RepeatMode { OFF, ALL, ONE }
