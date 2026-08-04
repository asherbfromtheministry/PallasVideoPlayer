package com.vizvag.shieldvideo.playback.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sticky navigation intents so remote play can bring the right screen forward.
 * Kept until the UI consumes it while RESUMED — avoids dropping the route while
 * Pallas is paused under VLC or restarting after stop.
 */
object RemoteNavRequests {
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    fun requestRoute(route: String) {
        val trimmed = route.trim()
        if (trimmed.isEmpty()) return
        _pendingRoute.value = trimmed
    }

    fun consume(route: String) {
        if (_pendingRoute.value == route) {
            _pendingRoute.value = null
        }
    }

    /** @deprecated Prefer [pendingRoute] + [consume]. */
    fun takeRoute(): String? {
        val value = _pendingRoute.value
        _pendingRoute.value = null
        return value
    }
}
