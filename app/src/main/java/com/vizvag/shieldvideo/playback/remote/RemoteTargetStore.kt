package com.vizvag.shieldvideo.playback.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which device this app instance is controlling.
 * Null / blank deviceId means local playback.
 */
object RemoteTargetStore {
    private val _target = MutableStateFlow<RemoteDevice?>(null)
    val target: StateFlow<RemoteDevice?> = _target.asStateFlow()

    fun setTarget(device: RemoteDevice?) {
        _target.value = device
    }

    fun clear() = setTarget(null)

    fun current(): RemoteDevice? = _target.value

    fun isControllingRemote(): Boolean = _target.value != null
}
