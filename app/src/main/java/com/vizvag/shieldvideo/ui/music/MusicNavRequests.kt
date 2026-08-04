package com.vizvag.shieldvideo.ui.music

/**
 * One-shot intents to open Music **Search** with a query
 * (e.g. from Radio now-playing metadata, or cross-screen handoff).
 */
object MusicNavRequests {
    @Volatile
    private var pendingSearch: String? = null

    fun requestSearch(query: String) {
        val name = query.trim()
        if (name.isEmpty()) return
        pendingSearch = name
    }

    /** @deprecated Prefer [requestSearch] — kept for call-site clarity. */
    fun requestArtist(artist: String) = requestSearch(artist)

    /** @deprecated Prefer [requestSearch] — kept for call-site clarity. */
    fun requestTrack(track: String) = requestSearch(track)

    fun takeSearch(): String? {
        val value = pendingSearch
        pendingSearch = null
        return value
    }

    fun takeArtist(): String? = takeSearch()

    fun takeTrack(): String? = takeSearch()
}
