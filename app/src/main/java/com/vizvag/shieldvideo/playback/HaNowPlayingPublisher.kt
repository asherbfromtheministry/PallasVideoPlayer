package com.vizvag.shieldvideo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Derive a sibling webhook id from the now-playing URL (e.g. `…/pallas_sleep`). */
fun haSiblingWebhookUrl(nowPlayingUrl: String, webhookId: String): String {
    val url = nowPlayingUrl.trim()
    if (url.isBlank() || webhookId.isBlank()) return ""
    return when {
        url.contains("/pallas_nowplaying") ->
            url.replace("/pallas_nowplaying", "/$webhookId")
        url.endsWith('/') -> "$url$webhookId"
        else -> "${url.substringBeforeLast('/')}/$webhookId"
    }
}

/** Derive the sleep-timer webhook from the now-playing URL (`…/pallas_sleep`). */
fun haSleepWebhookUrl(nowPlayingUrl: String): String =
    haSiblingWebhookUrl(nowPlayingUrl, "pallas_sleep")

/** Derive the radio-stations catalog webhook (`…/pallas_radio_stations`). */
fun haRadioStationsWebhookUrl(nowPlayingUrl: String): String =
    haSiblingWebhookUrl(nowPlayingUrl, "pallas_radio_stations")

/** Derive the recent-podcasts catalog webhook (`…/pallas_podcast_episodes`). */
fun haPodcastEpisodesWebhookUrl(nowPlayingUrl: String): String =
    haSiblingWebhookUrl(nowPlayingUrl, "pallas_podcast_episodes")

/** Pushes now-playing snapshots to a Home Assistant webhook. */
class HaNowPlayingPublisher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var lastPayload: String = ""

    fun publish(webhookUrl: String, session: NowPlaying, force: Boolean = false) {
        val url = webhookUrl.trim()
        if (url.isBlank()) return
        val body = session.toJson().toString()
        if (!force && body == lastPayload) return
        lastPayload = body
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { /* ignore body */ }
            }
        }
    }

    fun requestSleepStandby(sleepWebhookUrl: String, deviceId: String) {
        val url = sleepWebhookUrl.trim()
        val device = deviceId.trim().lowercase()
        if (url.isBlank() || device.isBlank()) return
        scope.launch {
            runCatching {
                val body = org.json.JSONObject()
                    .put("device", device)
                    .put("action", "standby")
                    .toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }

    fun clear(webhookUrl: String, deviceId: String) {
        val url = webhookUrl.trim()
        if (url.isBlank() || deviceId.isBlank()) return
        lastPayload = ""
        scope.launch {
            runCatching {
                val body = org.json.JSONObject()
                    .put("device", deviceId)
                    .put("uri", "")
                    .put("path", "")
                    .put("share", "")
                    .put("host", "")
                    .put("title", "")
                    .put("position_ms", 0)
                    .put("duration_ms", 0)
                    .put("cleared", true)
                    .toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }

    /**
     * Pushes the radio station catalog so HA can populate an input_select / voice scripts.
     * Body: `{ "device": "<id>", "stations": [ { "id", "name", "tagline" } ] }`
     * (stream URLs omitted — play via deep link or LAN `/v1/play`).
     */
    fun publishRadioStations(
        webhookUrl: String,
        deviceId: String,
        stations: List<Triple<String, String, String>>,
    ) {
        val url = webhookUrl.trim()
        val device = deviceId.trim().lowercase()
        if (url.isBlank() || device.isBlank()) return
        scope.launch {
            runCatching {
                val arr = org.json.JSONArray()
                stations.forEach { (id, name, tagline) ->
                    arr.put(
                        org.json.JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("tagline", tagline),
                    )
                }
                val body = org.json.JSONObject()
                    .put("device", device)
                    .put("stations", arr)
                    .toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }

    /**
     * Pushes recent podcast episodes so HA can populate an input_select.
     * Body: `{ "device": "<id>", "episodes": [ { "guid", "showId", "showTitle", "title", "label" } ] }`
     * (audio URLs omitted — play via deep link or LAN `/v1/play`).
     */
    fun publishPodcastEpisodes(
        webhookUrl: String,
        deviceId: String,
        episodes: List<Map<String, String>>,
    ) {
        val url = webhookUrl.trim()
        val device = deviceId.trim().lowercase()
        if (url.isBlank() || device.isBlank()) return
        scope.launch {
            runCatching {
                val arr = org.json.JSONArray()
                episodes.forEach { ep ->
                    arr.put(
                        org.json.JSONObject()
                            .put("guid", ep["guid"].orEmpty())
                            .put("showId", ep["showId"].orEmpty())
                            .put("showTitle", ep["showTitle"].orEmpty())
                            .put("title", ep["title"].orEmpty())
                            .put("label", ep["label"].orEmpty()),
                    )
                }
                val body = org.json.JSONObject()
                    .put("device", device)
                    .put("episodes", arr)
                    .toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }
}
