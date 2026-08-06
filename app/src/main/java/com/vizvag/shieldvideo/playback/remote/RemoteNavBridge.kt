package com.vizvag.shieldvideo.playback.remote

import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * When controlling a room, push this device's UI route to the TV so both stay in sync.
 */
object RemoteNavBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushRouteIfControlling(route: String?) {
        val trimmed = route?.trim().orEmpty()
        if (!RemoteUiRouteStore.isMirrorable(trimmed)) return
        if (trimmed == "remote") return
        val device = RemoteTargetStore.current() ?: return
        scope.launch {
            ShieldVideoApp.instance.remoteClient.navigate(device, trimmed)
                .onSuccess {
                    RemoteStatusPoller.publish(it)
                    RemoteStatusPoller.kick()
                }
        }
    }
}
