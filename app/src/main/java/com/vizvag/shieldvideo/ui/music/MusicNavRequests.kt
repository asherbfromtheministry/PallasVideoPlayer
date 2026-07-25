package com.vizvag.shieldvideo.ui.music

/**
 * One-shot intents to open Music Browse filtered by artist or track
 * (e.g. from Radio now-playing metadata).
 */
object MusicNavRequests {
    @Volatile
    private var pendingArtist: String? = null

    @Volatile
    private var pendingTrack: String? = null

    fun requestArtist(artist: String) {
        val name = artist.trim()
        if (name.isEmpty()) return
        pendingArtist = name
        pendingTrack = null
    }

    fun requestTrack(track: String) {
        val name = track.trim()
        if (name.isEmpty()) return
        pendingTrack = name
        pendingArtist = null
    }

    fun takeArtist(): String? {
        val value = pendingArtist
        pendingArtist = null
        return value
    }

    fun takeTrack(): String? {
        val value = pendingTrack
        pendingTrack = null
        return value
    }
}
