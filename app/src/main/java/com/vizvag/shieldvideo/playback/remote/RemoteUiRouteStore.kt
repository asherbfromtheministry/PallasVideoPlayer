package com.vizvag.shieldvideo.playback.remote

/**
 * Current Compose nav route on the player TV, published into [RemoteStatus.uiRoute]
 * so remotes can open the same screen.
 */
object RemoteUiRouteStore {
    @Volatile
    private var route: String = "home"

    val mirrorableRoutes: Set<String>
        get() = buildSet {
            add("home")
            add("music")
            add("radio")
            add("iptv")
            if (com.vizvag.shieldvideo.FeatureFlags.youtube) add("youtube")
            add("podcasts")
            add("browser")
            add("settings")
            add("multiview")
        }

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
