package com.vizvag.shieldvideo.playback.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteControllerSession(
    val id: String,
    val name: String,
    val host: String,
    val lastSeenMs: Long,
)

/**
 * Controllers currently driving this TV over LAN.
 * Presence is refreshed by requests that carry [RemoteControllerIdentity] headers
 * (status poll / transport / play). Stale entries drop after [STALE_AFTER_MS].
 */
object RemoteControllerSessions {
    private const val STALE_AFTER_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sessions = MutableStateFlow<List<RemoteControllerSession>>(emptyList())
    val sessions: StateFlow<List<RemoteControllerSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(400L)
                prune()
            }
        }
    }

    fun touch(id: String, name: String, host: String) {
        val trimmedId = id.trim()
        if (trimmedId.isBlank()) return
        val label = name.trim().ifBlank { trimmedId }.take(40)
        val now = System.currentTimeMillis()
        _sessions.update { current ->
            val without = current.filterNot { it.id == trimmedId }
            (without + RemoteControllerSession(
                id = trimmedId,
                name = label,
                host = host.trim(),
                lastSeenMs = now,
            )).sortedBy { it.name.lowercase() }
        }
    }

    fun clear() {
        _sessions.value = emptyList()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        _sessions.update { list -> list.filter { it.lastSeenMs >= cutoff } }
    }
}
