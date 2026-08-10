package com.vizvag.shieldvideo.data.youtube

import android.net.Uri
import android.util.Log

/** Apply BotGuard poToken to googlevideo URLs (403 without a valid videoId-bound pot=). */
object YoutubePoTokenUrls {
    private const val TAG = "YoutubePoTokenUrls"

    fun isGoogleVideo(url: String): Boolean =
        url.contains("googlevideo.com", ignoreCase = true)

    fun hasPoToken(url: String): Boolean =
        url.contains("pot=") || url.contains("pot%3D", ignoreCase = true)

    fun needsPoToken(url: String): Boolean =
        isGoogleVideo(url) && !hasPoToken(url)

    /** Set or replace `pot=` on googlevideo URLs. */
    fun applyStreamingPoToken(url: String, poToken: String): String {
        if (poToken.isBlank() || !isGoogleVideo(url)) return url
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        val names = uri.queryParameterNames
        for (name in names) {
            if (name.equals("pot", ignoreCase = true)) continue
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
        builder.appendQueryParameter("pot", poToken)
        return builder.build().toString()
    }

    fun enhancePlayback(playback: YoutubePlayback, poToken: String?): YoutubePlayback {
        val pot = poToken?.takeIf { it.isNotBlank() } ?: return playback
        return when (playback) {
            is YoutubePlayback.Progressive -> playback.copy(
                url = applyStreamingPoToken(playback.url, pot),
            )
            is YoutubePlayback.Dash -> playback.copy(
                url = applyStreamingPoToken(playback.url, pot),
            )
            is YoutubePlayback.Hls -> playback.copy(
                url = applyStreamingPoToken(playback.url, pot),
            )
            is YoutubePlayback.SeparateTracks -> playback.copy(
                videoUrl = applyStreamingPoToken(playback.videoUrl, pot),
                audioUrl = applyStreamingPoToken(playback.audioUrl, pot),
            )
        }
    }

    fun enhanceStreamInfo(info: YoutubeStreamInfo, poToken: String?): YoutubeStreamInfo {
        val pot = poToken?.takeIf { it.isNotBlank() } ?: return info
        val out = info.copy(
            playback = enhancePlayback(info.playback, pot),
            playbackFallbacks = info.playbackFallbacks.map { enhancePlayback(it, pot) },
            qualities = info.qualities.map { q ->
                q.copy(playback = enhancePlayback(q.playback, pot) as YoutubePlayback.SeparateTracks)
            },
        )
        Log.i(
            TAG,
            "applied pot to ${info.id} primaryHasPot=${playbackHasPot(out.playback)} " +
                "fallbacks=${out.playbackFallbacks.size} qualities=${out.qualities.size}",
        )
        return out
    }

    fun playbackHasPot(playback: YoutubePlayback): Boolean = when (playback) {
        is YoutubePlayback.Progressive -> hasPoToken(playback.url)
        is YoutubePlayback.Dash -> hasPoToken(playback.url)
        is YoutubePlayback.Hls -> hasPoToken(playback.url) || !isGoogleVideo(playback.url)
        is YoutubePlayback.SeparateTracks ->
            hasPoToken(playback.videoUrl) && hasPoToken(playback.audioUrl)
    }
}
