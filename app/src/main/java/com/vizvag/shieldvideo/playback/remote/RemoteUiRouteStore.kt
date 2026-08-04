package com.vizvag.shieldvideo.playback.remote

/**
 * Current Compose nav route on the player TV, published into [RemoteStatus.uiRoute]
 * so remotes can open the same screen.
 */
object RemoteUiRouteStore {
    @Volatile
    private var route: String = "home"

    private val mirrorable = setOf(
        "home",
        "music",
        "radio",
        "iptv",
        "youtube",
        "browser",
        "settings",
        "multiview",
    )

    fun set(route: String?) {
        val trimmed = route?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "remote") return
        if (trimmed in mirrorable) {
            this.route = trimmed
        }
    }

    fun current(): String = route
}
