package com.vizvag.shieldvideo.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class YoutubeTvDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

data class YoutubeTvTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
)

data class YoutubeTvClientCredentials(
    val clientId: String,
    val clientSecret: String,
)

/**
 * YouTube TV device OAuth (same family as SmartTube): scrape TV client id/secret from
 * youtube.com/tv JS, show a code at youtube.com/activate / google.com/device, then
 * store refresh token for authenticated InnerTube (subscription feed).
 */
class YoutubeTvAuthRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var cachedCredentials: YoutubeTvClientCredentials? = null

    suspend fun requestDeviceCode(deviceId: String): Result<YoutubeTvDeviceCode> =
        withContext(Dispatchers.IO) {
            runCatching {
                val creds = fetchClientCredentials()
                val body = JSONObject()
                    .put("client_id", creds.clientId)
                    .put("device_id", deviceId.ifBlank { UUID.randomUUID().toString() })
                    .put("model_name", MODEL_NAME)
                    .put("scope", APP_SCOPE)
                    .toString()
                val request = Request.Builder()
                    .url(DEVICE_CODE_URL)
                    .header("User-Agent", TV_USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("YouTube device code failed (${response.code}): ${text.take(180)}")
                    }
                    val json = JSONObject(text)
                    YoutubeTvDeviceCode(
                        deviceCode = json.getString("device_code"),
                        userCode = json.getString("user_code"),
                        verificationUrl = json.optString("verification_url", DEFAULT_VERIFY_URL)
                            .ifBlank { DEFAULT_VERIFY_URL },
                        intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
                        expiresInSeconds = json.optInt("expires_in", 1800).coerceAtLeast(60),
                    )
                }
            }
        }

    suspend fun pollForToken(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
    ): Result<YoutubeTvTokens> = withContext(Dispatchers.IO) {
        runCatching {
            val creds = fetchClientCredentials()
            val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
            var interval = intervalSeconds.coerceAtLeast(1)
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                val body = JSONObject()
                    .put("code", deviceCode)
                    .put("client_id", creds.clientId)
                    .put("client_secret", creds.clientSecret)
                    .put("grant_type", GRANT_TYPE_DEVICE)
                    .toString()
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .header("User-Agent", TV_USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    when {
                        response.isSuccessful && json != null &&
                            json.optString("refresh_token").isNotBlank() -> {
                            return@runCatching YoutubeTvTokens(
                                accessToken = json.getString("access_token"),
                                refreshToken = json.getString("refresh_token"),
                                expiresInSeconds = json.optInt("expires_in", 3600),
                            )
                        }
                        response.isSuccessful && json != null &&
                            json.optString("access_token").isNotBlank() -> {
                            // Rare: access without refresh on first poll — keep waiting for refresh.
                        }
                        else -> {
                            val err = json?.optString("error").orEmpty()
                            when (err) {
                                "authorization_pending", "slow_down" -> {
                                    if (err == "slow_down") interval += 1
                                }
                                "access_denied", "expired_token" ->
                                    error("YouTube link cancelled or expired")
                                "" -> { /* keep polling */ }
                                else -> error("YouTube auth: $err")
                            }
                        }
                    }
                }
            }
            error("YouTube link timed out — try again")
        }
    }

    suspend fun refreshAccessToken(refreshToken: String): Result<YoutubeTvTokens> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = refreshToken.trim()
                require(token.isNotBlank()) { "Not linked to YouTube" }
                val creds = fetchClientCredentials()
                val body = JSONObject()
                    .put("refresh_token", token)
                    .put("client_id", creds.clientId)
                    .put("client_secret", creds.clientSecret)
                    .put("grant_type", GRANT_TYPE_REFRESH)
                    .toString()
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .header("User-Agent", TV_USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("YouTube token refresh failed (${response.code}): ${text.take(180)}")
                    }
                    val json = JSONObject(text)
                    YoutubeTvTokens(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token").ifBlank { token },
                        expiresInSeconds = json.optInt("expires_in", 3600),
                    )
                }
            }
        }

    /**
     * Load TV OAuth client id/secret from youtube.com/tv base JS (first match only —
     * later matches are wrong and cause 401).
     */
    suspend fun fetchClientCredentials(): YoutubeTvClientCredentials =
        withContext(Dispatchers.IO) {
            cachedCredentials?.let { return@withContext it }
            val tvHtml = getText("https://www.youtube.com/tv")
            val clientPath = CLIENT_URL_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(tvHtml)?.groupValues?.getOrNull(1)
            } ?: error("Could not find YouTube TV client script — try again later")
            val clientUrl = tidyUrl(clientPath)
            val js = getText(clientUrl)
            val match = CLIENT_PAIR_REGEX.find(js)
                ?: error("Could not parse YouTube TV OAuth client from script")
            val creds = YoutubeTvClientCredentials(
                clientId = match.groupValues[1],
                clientSecret = match.groupValues[2],
            )
            cachedCredentials = creds
            creds
        }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TV_USER_AGENT)
            .header("Accept-Language", "en-US")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful || text.isBlank()) {
                error("HTTP ${response.code} fetching $url")
            }
            text
        }
    }

    private fun tidyUrl(pathOrUrl: String): String {
        val t = pathOrUrl.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "https://www.youtube.com$t"
            else -> "https://www.youtube.com/$t"
        }
    }

    companion object {
        private const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        private const val DEFAULT_VERIFY_URL = "https://www.youtube.com/activate"
        private const val MODEL_NAME = "ytlr::"
        private const val APP_SCOPE =
            "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val GRANT_TYPE_DEVICE = "http://oauth.net/grant_type/device/1.0"
        private const val GRANT_TYPE_REFRESH = "refresh_token"

        private val CLIENT_URL_PATTERNS = listOf(
            Regex("""id="base-js"\s+src="([^"]+)""""),
            Regex("""\.src\s*=\s*'(.*?m=base)'"""),
            Regex("""\.src\s*=\s*'(.*?)';\s*.\.id\s*=\s*'base-js'"""),
        )

        private val CLIENT_PAIR_REGEX = Regex(
            """clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[^:]+:"(\w+)""""
        )

        fun newDeviceId(): String = UUID.randomUUID().toString()
    }
}
