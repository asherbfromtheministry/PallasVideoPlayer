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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun status(device: RemoteDevice): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${device.baseUrl}/v1/status")
                .get()
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
}
