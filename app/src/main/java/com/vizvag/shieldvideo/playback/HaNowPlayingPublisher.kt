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

/** Derive the sleep-timer webhook from the now-playing URL (`…/pallas_sleep`). */
fun haSleepWebhookUrl(nowPlayingUrl: String): String {
    val url = nowPlayingUrl.trim()
    if (url.isBlank()) return ""
    return when {
        url.contains("/pallas_nowplaying") ->
            url.replace("/pallas_nowplaying", "/pallas_sleep")
        url.endsWith('/') -> "${url}pallas_sleep"
        else -> "${url.substringBeforeLast('/')}/pallas_sleep"
    }
}

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
}
