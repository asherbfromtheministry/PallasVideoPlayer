package com.vizvag.shieldvideo.playback.remote

import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single app-wide poll of the connected room's `/v1/status`.
 * Polls only while the app is in the foreground ([onForeground]/[onBackground]).
 */
object RemoteStatusPoller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<RemoteStatus?>(null)
    val status: StateFlow<RemoteStatus?> = _status.asStateFlow()

    private var pollJob: Job? = null
    private var foreground = false

    fun onForeground() {
        foreground = true
        startPollingIfNeeded()
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
                    val video = _status.value?.mode == RemotePlaybackMode.NasVideo
                    delay(if (video) 1_500L else 3_000L)
                }
            }
        }
    }
}
