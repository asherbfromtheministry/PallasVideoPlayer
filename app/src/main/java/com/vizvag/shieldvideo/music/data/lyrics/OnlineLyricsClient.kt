package com.vizvag.shieldvideo.music.data.lyrics

import android.util.Log
import com.vizvag.shieldvideo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches synced (or plain) lyrics from [LRCLIB](https://lrclib.net) — free, no API key.
 */
class OnlineLyricsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    suspend fun fetch(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val track = title.trim()
        val art = artist.trim()
        if (track.isBlank() || art.isBlank()) return@withContext emptyList()

        val durationSec = (durationMs / 1000L).coerceAtLeast(0L)
        val albumName = album?.trim().orEmpty()

        if (albumName.isNotEmpty() && durationSec > 0) {
            getExact(track, art, albumName, durationSec)?.let { return@withContext it }
        }
        searchBest(track, art, albumName, durationSec)
    }

    private fun getExact(
        track: String,
        artist: String,
        album: String,
        durationSec: Long,
    ): List<LyricLine>? {
        val url = buildString {
            append("https://lrclib.net/api/get?")
            append("track_name=").append(enc(track))
            append("&artist_name=").append(enc(artist))
            append("&album_name=").append(enc(album))
            append("&duration=").append(durationSec)
        }
        val body = httpGet(url) ?: return null
        if (body.isBlank()) return null
        return parseRecord(JSONObject(body), durationSec * 1000L)
    }

    private fun searchBest(
        track: String,
        artist: String,
        album: String,
        durationSec: Long,
    ): List<LyricLine> {
        val url = buildString {
            append("https://lrclib.net/api/search?")
            append("track_name=").append(enc(track))
            append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
        }
        val body = httpGet(url) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        if (arr.length() == 0) return emptyList()

        var best: Pair<Int, List<LyricLine>>? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("instrumental", false)) continue
            val lines = parseRecord(obj, durationSec * 1000L) ?: continue
            if (lines.isEmpty()) continue
            val score = scoreMatch(obj, track, artist, album, durationSec)
            if (best == null || score > best.first) {
                best = score to lines
            }
        }
        return best?.second.orEmpty()
    }

    private fun scoreMatch(
        obj: JSONObject,
        track: String,
        artist: String,
        album: String,
        durationSec: Long,
    ): Int {
        var score = 0
        val t = obj.optString("trackName")
        val a = obj.optString("artistName")
        val al = obj.optString("albumName")
        val dur = obj.optLong("duration", -1)
        if (t.equals(track, ignoreCase = true)) score += 40
        else if (normalize(t).contains(normalize(track)) || normalize(track).contains(normalize(t))) score += 20
        if (a.equals(artist, ignoreCase = true)) score += 30
        else if (normalize(a).contains(normalize(artist)) || normalize(artist).contains(normalize(a))) score += 15
        if (album.isNotBlank() && al.equals(album, ignoreCase = true)) score += 15
        if (durationSec > 0 && dur > 0) {
            val diff = kotlin.math.abs(dur - durationSec)
            when {
                diff <= 2 -> score += 20
                diff <= 5 -> score += 10
                diff <= 15 -> score += 3
                else -> score -= 10
            }
        }
        if (!obj.optString("syncedLyrics").isNullOrBlank()) score += 5
        return score
    }

    private fun parseRecord(obj: JSONObject, durationMs: Long): List<LyricLine>? {
        if (obj.optBoolean("instrumental", false)) return emptyList()
        val synced = obj.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }
        if (synced != null) {
            val parsed = LrcParser.parse(synced)
            if (parsed.isNotEmpty()) return parsed
        }
        val plain = obj.optString("plainLyrics").takeIf { !it.isNullOrBlank() } ?: return null
        return plainToTimedLines(plain, durationMs)
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> resp.body?.string()
                    404 -> null
                    429 -> {
                        Log.w(TAG, "LRCLIB rate limited")
                        null
                    }
                    else -> {
                        Log.w(TAG, "LRCLIB HTTP ${resp.code} for $url")
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LRCLIB request failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "PallasLyricsOnline"
        private val USER_AGENT =
            "PallasVideoPlayer/${BuildConfig.VERSION_NAME} (Android TV)"

        fun enc(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        fun normalize(value: String): String =
            value.lowercase().replace(Regex("""\s+"""), " ").trim()

        /** Spread plain (unsynced) lines across the track duration for panel scrolling. */
        fun plainToTimedLines(plain: String, durationMs: Long): List<LyricLine> {
            val lines = plain.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) return emptyList()
            val step = if (durationMs > 0 && lines.size > 1) {
                durationMs / lines.size
            } else {
                3_000L
            }
            return lines.mapIndexed { i, text -> LyricLine(i * step, text) }
        }
    }
}
