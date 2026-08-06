package com.vizvag.shieldvideo.playback.remote

/**
 * Current Compose nav route on the player TV, published into [RemoteStatus.uiRoute]
 * so remotes can open the same screen.
 */
object RemoteUiRouteStore {
    @Volatile
    private var route: String = "home"

    val mirrorableRoutes = setOf(
        "home",
        "music",
        "radio",
        "iptv",
        "youtube",
        "podcasts",
        "browser",
        "settings",
        "multiview",
    )

    fun isMirrorable(route: String?): Boolean {
        val trimmed = route?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed in mirrorableRoutes
    }

    fun set(route: String?) {
        val trimmed = route?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "remote") return
        if (trimmed in mirrorableRoutes) {
            this.route = trimmed
        }
    }

    fun current(): String = route
}
