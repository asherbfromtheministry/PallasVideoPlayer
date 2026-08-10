package com.vizvag.shieldvideo.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide full-screen blackout (Black button + sleep timer fallback). */
class BlackoutController {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun enter() {
        _active.value = true
    }

    fun exit() {
        _active.value = false
    }

    fun isActive(): Boolean = _active.value
}
