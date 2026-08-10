package com.vizvag.shieldvideo.data.youtube

/** Browse/search/history row for YouTube. */
data class YoutubeVideoItem(
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val views: Long = 0,
    val uploadedDate: String = "",
    /** Epoch ms for sorting; 0 when unknown. */
    val uploadedEpochMs: Long = 0,
    /** Channel id (`UC…`) when known — used for long-press channel browse. */
    val channelId: String = "",
    /** Max available stream height label (`1080p`, `4K`, …) when known. */
    val resolutionLabel: String? = null,
)

enum class YoutubeFeedSort(val label: String) {
    Newest("Newest"),
    Popular("Popular"),
    Title("A–Z"),
}

data class YoutubeStreamInfo(
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val description: String,
    val livestream: Boolean,
    val related: List<YoutubeVideoItem>,
    val playback: YoutubePlayback,
    /** Alternate sources tried if [playback] fails in ExoPlayer. */
    val playbackFallbacks: List<YoutubePlayback> = emptyList(),
    val channelId: String = "",
    /** Tallest video format advertised for this video (0 if unknown). */
    val maxHeight: Int = 0,
    val views: Long = 0,
    val uploadedDate: String = "",
    /** Discrete selectable video heights (SeparateTracks), highest first. */
    val qualities: List<YoutubeQualityOption> = emptyList(),
    /** User-Agent that must be used when fetching [playback] URLs (matches Innertube client). */
    val playbackUserAgent: String = YoutubeDefaults.PLAYBACK_USER_AGENT,
)

/** One selectable YouTube video height for manual quality forcing. */
data class YoutubeQualityOption(
    val height: Int,
    val label: String,
    val playback: YoutubePlayback.SeparateTracks,
)

sealed class YoutubePlayback {
    data class Progressive(val url: String, val mimeType: String?) : YoutubePlayback()
    data class Dash(val url: String) : YoutubePlayback()
    data class Hls(val url: String) : YoutubePlayback()
    data class SeparateTracks(
        val videoUrl: String,
        val audioUrl: String,
        val videoMime: String?,
        val audioMime: String?,
    ) : YoutubePlayback()
}

object YoutubeDefaults {
    /** ExoPlayer / googlevideo.com default when streams come from Piped or ANDROID Innertube. */
    const val PLAYBACK_USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"

    /**
     * Working public Piped API (as of mid-2026). Official kavin instance is often down —
     * change anytime in Settings → YouTube.
     */
    const val DEFAULT_PIPED_API_URL = "https://api.piped.private.coffee"
    private const val LEGACY_DEFAULT_PIPED_API_URL = "https://pipedapi.kavin.rocks"

    /** Tried after Innertube when the configured Piped instance cannot resolve a stream. */
    val STREAM_FALLBACK_APIS = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.ducks.party",
        "https://pipedapi.smnz.de",
    )

    fun normalizeApiUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.isBlank() -> DEFAULT_PIPED_API_URL
            trimmed.equals(LEGACY_DEFAULT_PIPED_API_URL, ignoreCase = true) -> DEFAULT_PIPED_API_URL
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun streamApiCandidates(preferredRaw: String): List<String> {
        val preferred = normalizeApiUrl(preferredRaw)
        return (listOf(preferred) + STREAM_FALLBACK_APIS.map { normalizeApiUrl(it) })
            .distinctBy { it.lowercase() }
    }

    fun channelIdFromUrl(urlOrId: String): String? {
        val raw = urlOrId.trim()
        if (raw.isBlank()) return null
        if (raw.matches(Regex("^UC[\\w-]{22}$"))) return raw
        Regex("""/(?:channel|c)/([\w-]+)""").find(raw)?.groupValues?.getOrNull(1)?.let {
            if (it.startsWith("UC")) return it
        }
        Regex("""channel/([\w-]+)""").find(raw)?.groupValues?.getOrNull(1)?.let {
            if (it.startsWith("UC")) return it
        }
        return null
    }

    fun videoIdFromUrl(urlOrId: String): String? {
        val raw = urlOrId.trim()
        if (raw.isBlank()) return null
        if (raw.matches(Regex("^[\\w-]{11}$"))) return raw
        val patterns = listOf(
            Regex("""[?&]v=([\w-]{11})"""),
            Regex("""youtu\.be/([\w-]{11})"""),
            Regex("""/shorts/([\w-]{11})"""),
            Regex("""/embed/([\w-]{11})"""),
            Regex("""/watch\?v=([\w-]{11})"""),
        )
        patterns.forEach { re ->
            re.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        }
        // Piped-style path: /watch?v=ID
        Regex("""/watch\?v=([\w-]{11})""").find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }
}
