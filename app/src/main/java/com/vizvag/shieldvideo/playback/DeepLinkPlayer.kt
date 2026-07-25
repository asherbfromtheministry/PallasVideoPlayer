package com.vizvag.shieldvideo.playback

import android.content.Intent
import android.net.Uri

/**
 * Home Assistant / Android TV Remote deep links.
 *
 * - pallas://play?uri=<encoded>&title=&position=
 * - pallas://play?share=&path=&host=&title=&position=  (preferred; target builds local URL)
 * - pallas://stop
 */
object DeepLinkPlayer {
    private const val HOST_PLAY = "play"
    private const val HOST_STOP = "stop"
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
