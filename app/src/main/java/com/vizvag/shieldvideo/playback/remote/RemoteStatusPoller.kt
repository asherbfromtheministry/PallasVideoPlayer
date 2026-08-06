package com.vizvag.shieldvideo.playback.remote

import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single app-wide poll of the connected room's `/v1/status`.
 * Polls only while the app is in the foreground ([onForeground]/[onBackground]).
 * Keep the interval short — LAN status is tiny and UI must feel live.
 */
object RemoteStatusPoller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<RemoteStatus?>(null)
    val status: StateFlow<RemoteStatus?> = _status.asStateFlow()

    private val wake = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var pollJob: Job? = null
    private var foreground = false

    fun onForeground() {
        foreground = true
        startPollingIfNeeded()
        kick()
    }

    fun onBackground() {
        foreground = false
        pollJob?.cancel()
        pollJob = null
    }

    /** Optimistic update after transport / play commands. */
    fun publish(status: RemoteStatus?) {
        _status.value = status
    }

    /** Fetch status immediately (do not wait for the next tick). */
    fun kick() {
        wake.tryEmit(Unit)
    }

    private fun startPollingIfNeeded() {
        if (!foreground) return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            RemoteTargetStore.target.collectLatest { device ->
                if (device == null) {
                    _status.value = null
                    return@collectLatest
                }
                val client = ShieldVideoApp.instance.remoteClient
                while (foreground &&
                    RemoteTargetStore.current()?.host == device.host &&
                    RemoteTargetStore.current()?.port == device.port
                ) {
                    client.status(device)
                        .onSuccess { _status.value = it }
                    // ~2.5 Hz on LAN — snappy UI without hammering the TV.
                    withTimeoutOrNull(400L) { wake.first() }
                }
            }
        }
    }
}
