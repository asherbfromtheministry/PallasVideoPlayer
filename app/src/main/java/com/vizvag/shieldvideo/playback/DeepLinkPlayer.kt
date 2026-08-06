package com.vizvag.shieldvideo.playback

import android.content.Intent
import android.net.Uri

/**
 * Home Assistant / Android TV Remote deep links.
 *
 * - pallas://play?uri=<encoded>&title=&position=
 * - pallas://play?share=&path=&host=&title=&position=  (preferred; target builds local URL)
 * - pallas://radio?stationId=<id>  (or ?name=<station name>)
 * - pallas://podcast?guid=<guid>&showId=<id>  (or ?label=<Show · Episode>)
 * - pallas://podcast?show=<show name>  (latest episode of that subscription)
 * - pallas://podcast?refresh=1
 * - pallas://podcast?skip=-15  (or ?skip=15) — relative seek in seconds
 * - pallas://stop
 */
object DeepLinkPlayer {
    private const val HOST_PLAY = "play"
    private const val HOST_STOP = "stop"
    private const val HOST_RADIO = "radio"
    private const val HOST_PODCAST = "podcast"
    private val SCHEMES = setOf("pallas", "pallasvideo")

    fun isPlayIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return data.scheme?.lowercase() in SCHEMES &&
            data.host.equals(HOST_PLAY, ignoreCase = true)
    }

    fun isStopIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return data.scheme?.lowercase() in SCHEMES &&
            data.host.equals(HOST_STOP, ignoreCase = true)
    }

    fun isRadioIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return data.scheme?.lowercase() in SCHEMES &&
            data.host.equals(HOST_RADIO, ignoreCase = true)
    }

    fun isPodcastIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return data.scheme?.lowercase() in SCHEMES &&
            data.host.equals(HOST_PODCAST, ignoreCase = true)
    }

    fun radioStationIdFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("stationId")?.trim()?.takeIf { it.isNotBlank() }

    fun radioStationNameFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("name")?.trim()?.takeIf { it.isNotBlank() }

    fun podcastGuidFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("guid")?.trim()?.takeIf { it.isNotBlank() }

    fun podcastShowIdFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("showId")?.trim()?.takeIf { it.isNotBlank() }

    fun podcastLabelFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("label")?.trim()?.takeIf { it.isNotBlank() }

    /** Spoken / display show title — plays the latest episode of that subscription. */
    fun podcastShowNameFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("show")?.trim()?.takeIf { it.isNotBlank() }

    fun podcastRefreshFrom(intent: Intent): Boolean {
        val raw = intent.data?.getQueryParameter("refresh")?.trim()?.lowercase().orEmpty()
        return raw == "1" || raw == "true" || raw == "yes"
    }

    /** Relative seek in seconds (e.g. -15 / +15). */
    fun podcastSkipSecondsFrom(intent: Intent): Long? {
        val raw = intent.data?.getQueryParameter("skip")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.toLongOrNull()
    }

    fun shareFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("share")?.trim()?.takeIf { it.isNotBlank() }

    fun pathFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("path")?.trim()?.takeIf { it.isNotBlank() }

    fun hostFrom(intent: Intent): String? =
        intent.data?.getQueryParameter("host")?.trim()?.takeIf { it.isNotBlank() }

    fun mediaUriFrom(intent: Intent): Uri? {
        val data = intent.data ?: return null
        if (!isPlayIntent(intent)) return null
        val raw = data.getQueryParameter("uri")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { Uri.parse(raw) }.getOrNull()?.takeIf {
            it.scheme?.lowercase() in setOf("smb", "http", "https", "rtsp", "rtp")
        }
    }

    fun positionMsFrom(intent: Intent): Long? {
        val raw = intent.data?.getQueryParameter("position")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.toLongOrNull()?.takeIf { it > 5_000L }
    }

    fun titleFrom(intent: Intent): String {
        val fromQuery = intent.data?.getQueryParameter("title")?.trim().orEmpty()
        if (fromQuery.isNotBlank()) return fromQuery
        val path = pathFrom(intent)
        if (!path.isNullOrBlank()) {
            return path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Video" }
        }
        val uri = mediaUriFrom(intent) ?: return "Video"
        return uri.lastPathSegment?.substringBeforeLast('.')?.replace('%', ' ')?.ifBlank { null }
            ?: "Video"
    }

    fun relativePathHint(uri: Uri): String =
        uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "video.mp4"
}
