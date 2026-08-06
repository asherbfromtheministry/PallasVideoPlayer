package com.vizvag.shieldvideo.playback.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** LAN client — no shared token; same Wi‑Fi is enough. */
class RemoteControlClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 60, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Podcast catalog / refresh can parse many feeds — allow a long read. */
    private val podcastHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    suspend fun status(device: RemoteDevice): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${device.baseUrl}/v1/status")
                .get()
                .withControllerHeaders()
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("status ${resp.code}")
                resp.body?.string().orEmpty()
            }
            if (body.isBlank()) error("empty status")
            RemoteStatus.fromJson(JSONObject(body))
        }
    }

    suspend fun transport(
        device: RemoteDevice,
        action: TransportAction,
        positionMs: Long = 0,
    ): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("action", action.name.lowercase())
                .put("positionMs", positionMs)
            postJson("${device.baseUrl}/v1/transport", payload)
        }
    }

    suspend fun play(device: RemoteDevice, body: JSONObject): Result<RemoteStatus> =
        withContext(Dispatchers.IO) {
            runCatching { postJson("${device.baseUrl}/v1/play", body) }
        }

    suspend fun playMusic(
        device: RemoteDevice,
        tracks: List<MusicTrackRef>,
        startIndex: Int = 0,
    ): Result<RemoteStatus> {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("nasPath", t.nasPath)
                    .put("title", t.title)
                    .put("artistName", t.artistName)
                    .put("albumTitle", t.albumTitle)
                    .put("durationMs", t.durationMs),
            )
        }
        return play(
            device,
            JSONObject()
                .put("type", "music")
                .put("tracks", arr)
                .put("startIndex", startIndex),
        )
    }

    suspend fun playNasVideo(
        device: RemoteDevice,
        share: String,
        path: String,
        title: String = "",
        positionMs: Long? = null,
        host: String? = null,
    ): Result<RemoteStatus> {
        val body = JSONObject()
            .put("type", "nas")
            .put("share", share)
            .put("path", path)
            .put("title", title)
        if (positionMs != null && positionMs > 0) body.put("positionMs", positionMs)
        if (!host.isNullOrBlank()) body.put("host", host)
        return play(device, body)
    }

    suspend fun playLiveTv(device: RemoteDevice, channelId: String): Result<RemoteStatus> =
        play(device, JSONObject().put("type", "iptv").put("channelId", channelId))

    suspend fun playRadio(device: RemoteDevice, stationId: String): Result<RemoteStatus> =
        play(device, JSONObject().put("type", "radio").put("stationId", stationId))

    suspend fun playYouTube(device: RemoteDevice, videoId: String): Result<RemoteStatus> =
        play(device, JSONObject().put("type", "youtube").put("videoId", videoId))

    suspend fun navigate(device: RemoteDevice, route: String): Result<RemoteStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                postJson(
                    "${device.baseUrl}/v1/navigate",
                    JSONObject().put("route", route),
                )
            }
        }

    suspend fun podcastSubscriptions(device: RemoteDevice): Result<List<JSONObject>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${device.baseUrl}/v1/podcasts")
                    .get()
                    .withControllerHeaders()
                    .build()
                val body = podcastHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("podcasts ${resp.code}")
                    resp.body?.string().orEmpty()
                }
                val arr = JSONObject(body).optJSONArray("subscriptions") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(it) }
                    }
                }
            }
        }

    /** Force the room TV to re-fetch all podcast RSS feeds, then return updated subscriptions. */
    suspend fun refreshPodcastFeeds(device: RemoteDevice): Result<Pair<Int, List<JSONObject>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${device.baseUrl}/v1/podcasts/refresh")
                    .post("{}".toRequestBody(jsonMedia))
                    .withControllerHeaders()
                    .build()
                val text = podcastHttp.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                        error(err?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}")
                    }
                    body
                }
                val obj = JSONObject(text)
                val updated = obj.optInt("updated", 0)
                val arr = obj.optJSONArray("subscriptions") ?: JSONArray()
                val subs = buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(it) }
                    }
                }
                updated to subs
            }
        }

    suspend fun podcastEpisodes(
        device: RemoteDevice,
        showId: String? = null,
    ): Result<List<JSONObject>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildString {
                append("${device.baseUrl}/v1/podcasts/episodes")
                if (!showId.isNullOrBlank()) {
                    append("?showId=")
                    append(java.net.URLEncoder.encode(showId, Charsets.UTF_8.name()))
                }
            }
            val req = Request.Builder().url(url).get().withControllerHeaders().build()
            val body = podcastHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("podcast episodes ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val arr = JSONObject(body).optJSONArray("episodes") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(it) }
                }
            }
        }
    }

    suspend fun playPodcast(
        device: RemoteDevice,
        showId: String,
        episodeGuid: String,
        audioUrl: String = "",
        episodeTitle: String = "",
        showTitle: String = "",
        imageUrl: String = "",
        durationSec: Long = 0L,
        positionMs: Long = 0L,
    ): Result<RemoteStatus> = play(
        device,
        JSONObject()
            .put("type", "podcast")
            .put("showId", showId)
            .put("episodeGuid", episodeGuid)
            .put("audioUrl", audioUrl)
            .put("episodeTitle", episodeTitle)
            .put("showTitle", showTitle)
            .put("imageUrl", imageUrl)
            .put("durationSec", durationSec)
            .put("positionMs", positionMs),
    )

    suspend fun musicQueue(
        device: RemoteDevice,
        action: MusicQueueAction,
        tracks: List<MusicTrackRef> = emptyList(),
        index: Int = -1,
        from: Int = -1,
        to: Int = -1,
    ): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("nasPath", t.nasPath)
                        .put("title", t.title)
                        .put("artistName", t.artistName)
                        .put("albumTitle", t.albumTitle)
                        .put("durationMs", t.durationMs),
                )
            }
            val payload = JSONObject()
                .put("action", action.name.lowercase())
                .put("tracks", arr)
                .put("index", index)
                .put("from", from)
                .put("to", to)
            postJson("${device.baseUrl}/v1/music/queue", payload)
        }
    }

    private fun postJson(url: String, payload: JSONObject): RemoteStatus {
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMedia))
            .withControllerHeaders()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                error(err?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}")
            }
            if (text.isBlank()) error("empty response")
            return RemoteStatus.fromJson(JSONObject(text))
        }
    }

    private fun Request.Builder.withControllerHeaders(): Request.Builder {
        if (!RemoteTargetStore.isControllingRemote()) return this
        return header(RemoteControllerIdentity.HEADER_ID, RemoteControllerIdentity.id())
            .header(RemoteControllerIdentity.HEADER_NAME, RemoteControllerIdentity.displayName())
    }
}
