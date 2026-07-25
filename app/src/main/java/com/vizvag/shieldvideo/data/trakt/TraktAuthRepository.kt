package com.vizvag.shieldvideo.data.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int
)

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String
)

class TraktAuthRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    suspend fun requestDeviceCode(clientId: String): Result<TraktDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("client_id", clientId).toString()
            val request = Request.Builder()
                .url("https://api.trakt.tv/oauth/device/code")
                .post(body.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Trakt device code failed (${response.code})")
                val json = JSONObject(text)
                TraktDeviceCode(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://trakt.tv/activate"),
                    intervalSeconds = json.optInt("interval", 5),
                    expiresInSeconds = json.optInt("expires_in", 600)
                )
            }
        }
    }

    suspend fun pollForToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int
    ): Result<TraktTokens> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
            var interval = intervalSeconds.coerceAtLeast(1)
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                val body = JSONObject()
                    .put("code", deviceCode)
                    .put("client_id", clientId)
                    .put("client_secret", clientSecret)
                    .toString()
                val request = Request.Builder()
                    .url("https://api.trakt.tv/oauth/device/token")
                    .post(body.toRequestBody(jsonMedia))
                    .header("Content-Type", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when (response.code) {
                        200 -> {
                            val json = JSONObject(text)
                            return@runCatching TraktTokens(
                                accessToken = json.getString("access_token"),
                                refreshToken = json.optString("refresh_token")
                            )
                        }
                        400 -> {
                            val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                            if (error == "slow_down") interval += 1
                            // pending / slow_down → keep polling
                        }
                        else -> error("Trakt auth failed (${response.code}): $text")
                    }
                }
            }
            error("Trakt link timed out")
        }
    }
}
