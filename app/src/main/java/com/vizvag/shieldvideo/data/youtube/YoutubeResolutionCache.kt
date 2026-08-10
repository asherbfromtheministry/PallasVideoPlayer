package com.vizvag.shieldvideo.data.youtube

import android.content.Context

/** Persists max available YouTube resolution per video id (local only). */
class YoutubeResolutionCache(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_resolution_cache", Context.MODE_PRIVATE)

    fun heightFor(videoId: String): Int {
        if (videoId.isBlank()) return 0
        return prefs.getInt(videoId, 0).coerceAtLeast(0)
    }

    fun labelFor(videoId: String): String? = labelForHeight(heightFor(videoId))

    fun put(videoId: String, height: Int) {
        if (videoId.isBlank() || height <= 0) return
        val existing = heightFor(videoId)
        if (height <= existing) return
        prefs.edit().putInt(videoId, height).apply()
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        // Drop arbitrary extras — SharedPreferences has no LRU; keep newest puts by clearing oldest keys.
        val overflow = all.size - MAX_ENTRIES
        val keys = all.keys.take(overflow)
        prefs.edit().apply {
            keys.forEach { remove(it) }
            apply()
        }
    }

    companion object {
        private const val MAX_ENTRIES = 800

        fun labelForHeight(height: Int): String? = when {
            height >= 2000 -> "4K"
            height >= 1400 -> "1440p"
            height >= 1040 -> "1080p"
            height >= 700 -> "720p"
            height >= 480 -> "480p"
            height > 0 -> "${height}p"
            else -> null
        }
    }
}
