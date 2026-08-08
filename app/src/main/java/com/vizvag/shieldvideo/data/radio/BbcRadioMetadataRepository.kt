package com.vizvag.shieldvideo.data.radio

import com.vizvag.shieldvideo.music.data.metadata.AlbumArtLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches live BBC Sounds metadata (track / programme info).
 * The HLS audio feed does not carry titles; this uses the same RMS API as bbc.co.uk/sounds.
 * Works outside the UK — use [experience=international] first, then domestic as fallback.
 *
 * Note: many 6 Music (and some other) segments return BBC’s generic placeholder image
 * (`p0bqcdzf`). That is treated as missing so we can fall back to programme art or
 * Deezer / iTunes track art.
 */
class BbcRadioMetadataRepository(
    private val artLookup: AlbumArtLookup = AlbumArtLookup(),
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchNowPlaying(serviceId: String): RadioNowPlaying = withContext(Dispatchers.IO) {
        val show = EXPERIENCES.firstNotNullOfOrNull { fetchShow(serviceId, it) }
        val music = EXPERIENCES.firstNotNullOfOrNull { fetchMusic(serviceId, it) }
        when {
            music != null -> {
                var image = music.imageUrl ?: show?.imageUrl
                if (image.isNullOrBlank() &&
                    music.artist.isNotBlank() &&
                    music.track.isNotBlank()
                ) {
                    image = runCatching {
                        artLookup.resolveTrackArtUrl(music.artist, music.track)
                    }.getOrNull()
                }
                music.copy(
                    showTitle = show?.title,
                    showEpisode = show?.episode,
                    imageUrl = image,
                )
            }
            show != null -> show
            else -> RadioNowPlaying.Unavailable
        }
    }

    private fun fetchMusic(serviceId: String, experience: String): RadioNowPlaying.Music? {
        val url =
            "$BASE/services/$serviceId/segments/latest?experience=$experience&offset=0&limit=4"
        val root = getJson(url) ?: return null
        val data = root.optJSONArray("data") ?: return null
        if (data.length() == 0) return null

        val nowIdx = (0 until data.length()).firstOrNull { i ->
            data.optJSONObject(i)?.optJSONObject("offset")?.optBoolean("now_playing") == true
        } ?: 0
        val now = data.optJSONObject(nowIdx) ?: return null
        if (now.optString("segment_type") != "music") return null

        val titles = now.optJSONObject("titles") ?: return null
        val artist = titles.optString("primary").trim()
        val track = titles.optString("secondary").trim()
        if (artist.isBlank() && track.isBlank()) return null

        val offset = now.optJSONObject("offset")
        val status = offset?.optString("label")?.trim().orEmpty().ifBlank { "Now Playing" }

        val recent = buildList {
            for (i in 0 until data.length()) {
                if (i == nowIdx) continue
                val item = data.optJSONObject(i) ?: continue
                if (item.optString("segment_type") != "music") continue
                val t = item.optJSONObject("titles") ?: continue
                val a = t.optString("primary").trim()
                val s = t.optString("secondary").trim()
                if (a.isBlank() && s.isBlank()) continue
                add(
                    RadioTrackHistory(
                        artist = a,
                        track = s,
                        status = item.optJSONObject("offset")?.optString("label").orEmpty()
                    )
                )
            }
        }

        return RadioNowPlaying.Music(
            artist = artist,
            track = track,
            status = status,
            imageUrl = bbcImage(now.optString("image_url")),
            recent = recent.take(3)
        )
    }

    private fun fetchShow(serviceId: String, experience: String): RadioNowPlaying.Show? {
        // Prefer /broadcasts/poll (current Sounds API); keep sub-services as a fallback.
        val paths = listOf(
            "$BASE/broadcasts/poll/$serviceId?experience=$experience&offset=0&limit=1",
            "$BASE/broadcasts/sub-services/poll/$serviceId?experience=$experience&offset=0&limit=1",
        )
        val root = paths.firstNotNullOfOrNull { getJson(it) } ?: return null
        val data = root.optJSONArray("data") ?: return null
        val item = data.optJSONObject(0) ?: return null

        val titles = item.optJSONObject("titles") ?: return null
        val title = titles.optString("primary").trim()
        if (title.isBlank()) return null

        val episode = titles.optString("secondary").trim().ifBlank { null }
        val synopsis = item.optJSONObject("synopses")
            ?.optString("short")
            ?.trim()
            ?.ifBlank { null }
            ?: item.optJSONObject("synopses")
                ?.optString("medium")
                ?.trim()
                ?.ifBlank { null }

        val startMs = parseIsoMs(item.optString("start"))
        val endMs = parseIsoMs(item.optString("end"))

        return RadioNowPlaying.Show(
            title = title,
            episode = episode,
            synopsis = synopsis,
            imageUrl = bbcImage(item.optString("image_url")),
            startMs = startMs,
            endMs = endMs
        )
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@runCatching null
            JSONObject(body)
        }
    }.getOrNull()

    private fun bbcImage(template: String?): String? {
        if (template.isNullOrBlank()) return null
        // BBC Sounds default when a music segment has no cover — tiny generic tile.
        if (PLACEHOLDER_IMAGE_IDS.any { id -> template.contains("/$id.") || template.contains("/$id/") }) {
            return null
        }
        // BBC ichef requires WxH recipes (e.g. 480x480). A bare "480" returns 403.
        if (template.contains("{recipe}")) {
            return template.replace("{recipe}", "480x480")
        }
        return template
    }

    private fun parseIsoMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrNull()
    }

    companion object {
        private const val BASE = "https://rms.api.bbc.co.uk/v2"
        private const val USER_AGENT = "PallasVideoPlayer/1.0 (Android TV)"
        private val EXPERIENCES = listOf("international", "domestic")
        /** Known ichef PIDs used as empty / generic segment artwork. */
        private val PLACEHOLDER_IMAGE_IDS = listOf("p0bqcdzf")
    }
}
