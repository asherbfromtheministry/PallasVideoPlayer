package com.vizvag.shieldvideo.data.hue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HueLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val reachable: Boolean,
)

data class HueLightState(
    val on: Boolean,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
)

/**
 * Philips Hue Bridge local API (CLIP v1) over LAN HTTP.
 * Pair once (bridge button), then control selected lights.
 */
class HueBridgeClient(
    client: OkHttpClient? = null,
) {
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun pair(bridgeIp: String, deviceName: String = "pallas#shield"): Result<String> =
        withContext(Dispatchers.IO) {
            val ip = normalizeIp(bridgeIp) ?: return@withContext Result.failure(
                IllegalArgumentException("Enter the Hue bridge IP"),
            )
            runCatching {
                val body = JSONObject().put("devicetype", deviceName).toString()
                val request = Request.Builder()
                    .url("http://$ip/api")
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("Bridge HTTP ${response.code}")
                    }
                    parsePairResponse(text)
                }
            }
        }

    suspend fun listLights(bridgeIp: String, username: String): Result<List<HueLight>> =
        withContext(Dispatchers.IO) {
            val ip = normalizeIp(bridgeIp) ?: return@withContext Result.failure(
                IllegalArgumentException("Enter the Hue bridge IP"),
            )
            val user = username.trim()
            if (user.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Pair the bridge first"))
            }
            runCatching {
                val request = Request.Builder()
                    .url("http://$ip/api/$user/lights")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Bridge HTTP ${response.code}")
                    parseLights(text)
                }
            }
        }

    suspend fun getLightState(
        bridgeIp: String,
        username: String,
        lightId: String,
    ): Result<HueLightState> = withContext(Dispatchers.IO) {
        val ip = normalizeIp(bridgeIp) ?: return@withContext Result.failure(
            IllegalArgumentException("Enter the Hue bridge IP"),
        )
        runCatching {
            val request = Request.Builder()
                .url("http://$ip/api/${username.trim()}/lights/${lightId.trim()}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Bridge HTTP ${response.code}")
                val state = JSONObject(text).optJSONObject("state")
                    ?: error("No light state")
                HueLightState(
                    on = state.optBoolean("on", false),
                    bri = state.optInt("bri", -1).takeIf { it >= 0 },
                    hue = state.optInt("hue", -1).takeIf { it >= 0 },
                    sat = state.optInt("sat", -1).takeIf { it >= 0 },
                )
            }
        }
    }

    /** Fire-and-forget friendly; returns false on network/HTTP failure. */
    fun setLightState(
        bridgeIp: String,
        username: String,
        lightId: String,
        on: Boolean,
        bri: Int? = null,
        hue: Int? = null,
        sat: Int? = null,
        transitionTime: Int = 1,
    ): Boolean {
        val ip = normalizeIp(bridgeIp) ?: return false
        val user = username.trim()
        val id = lightId.trim()
        if (user.isBlank() || id.isBlank()) return false
        return runCatching {
            val payload = JSONObject().put("on", on)
            if (bri != null) payload.put("bri", bri.coerceIn(1, 254))
            if (hue != null) payload.put("hue", hue.coerceIn(0, 65535))
            if (sat != null) payload.put("sat", sat.coerceIn(0, 254))
            payload.put("transitiontime", transitionTime.coerceAtLeast(0))
            val request = Request.Builder()
                .url("http://$ip/api/$user/lights/$id/state")
                .put(payload.toString().toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        fun normalizeIp(raw: String): String? {
            val trimmed = raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .trim()
            return trimmed.takeIf { it.isNotBlank() }
        }

        private fun parsePairResponse(text: String): String {
            val arr = JSONArray(text)
            if (arr.length() == 0) error("Empty bridge response")
            val first = arr.getJSONObject(0)
            val success = first.optJSONObject("success")
            if (success != null) {
                val username = success.optString("username").trim()
                if (username.isBlank()) error("Bridge returned empty username")
                return username
            }
            val error = first.optJSONObject("error")
            val type = error?.optInt("type", -1) ?: -1
            val description = error?.optString("description").orEmpty()
            if (type == 101) {
                error("Press the link button on the Hue bridge, then Pair again")
            }
            error(description.ifBlank { "Pairing failed" })
        }

        private fun parseLights(text: String): List<HueLight> {
            val root = JSONObject(text)
            if (root.has("error") || (root.length() == 1 && root.keys().asSequence().firstOrNull() == "0")) {
                // Auth error often arrives as a JSON array, but guard object form too.
            }
            if (text.trimStart().startsWith("[")) {
                val arr = JSONArray(text)
                val err = arr.optJSONObject(0)?.optJSONObject("error")
                if (err != null) {
                    error(err.optString("description").ifBlank { "Bridge auth failed — re-pair" })
                }
            }
            val lights = mutableListOf<HueLight>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = root.optJSONObject(id) ?: continue
                val state = obj.optJSONObject("state")
                lights += HueLight(
                    id = id,
                    name = obj.optString("name", "Light $id"),
                    on = state?.optBoolean("on", false) == true,
                    reachable = state?.optBoolean("reachable", true) != false,
                )
            }
            return lights.sortedBy { it.name.lowercase() }
        }
    }
}
