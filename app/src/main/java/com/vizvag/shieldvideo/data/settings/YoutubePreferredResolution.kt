package com.vizvag.shieldvideo.data.settings

/**
 * Preferred max YouTube playback height.
 * [Auto] picks the highest available; other values cap at that rung when higher exists.
 */
enum class YoutubePreferredResolution(val label: String, val maxHeight: Int) {
    Auto("AUTO", 0),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    P1440("1440p", 1440),
    P2160("4K", 2160);

    companion object {
        fun fromStorage(value: String?): YoutubePreferredResolution =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Auto
    }
}
