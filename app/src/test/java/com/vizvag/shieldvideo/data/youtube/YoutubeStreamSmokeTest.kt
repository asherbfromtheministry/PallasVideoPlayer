package com.vizvag.shieldvideo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live smoke test: resolve a known public video via Innertube and verify stream URLs respond.
 * Skips when offline.
 */
class YoutubeStreamSmokeTest {
    @Test
    fun innertubeSeparateTracksPlayable() = runBlocking {
        val repo = YoutubeRepository()
        val info = runCatching { repo.streams("dQw4w9WgXcQ") }.getOrElse { e ->
            org.junit.Assume.assumeNoException("Innertube unreachable", e)
            error("unreachable")
        }
        val playback = info.playback
        assertTrue(
            "Expected SeparateTracks or Progressive primary, got $playback",
            playback is YoutubePlayback.SeparateTracks || playback is YoutubePlayback.Progressive,
        )
        assertTrue("maxHeight should be > 0", info.maxHeight > 0)
        assertFalse("qualities should be listed", info.qualities.isEmpty())

        val probeUrl = when (playback) {
            is YoutubePlayback.SeparateTracks -> playback.videoUrl
            is YoutubePlayback.Progressive -> playback.url
            is YoutubePlayback.Dash -> playback.url
            is YoutubePlayback.Hls -> playback.url
        }
        assertNotNull(probeUrl)
        val code = runCatching { headStatus(probeUrl, info.playbackUserAgent) }.getOrDefault(-1)
        assertTrue("Stream URL should HTTP 200/206/302, got $code for ${probeUrl.take(80)}", code in 200..399)
    }

    private fun headStatus(url: String, userAgent: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Origin", "https://www.youtube.com")
        }
        return conn.responseCode.also { conn.disconnect() }
    }
}
