package com.vizvag.shieldvideo.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * YouTube browse/search via [Piped](https://github.com/TeamPiped/Piped); playback via
 * YouTube Innertube (device IP) with Piped as fallback. Subscription feed prefers a
 * linked YouTube TV account (SmartTube-style device code); Piped login remains optional.
 */
class YoutubeRepository(
    private val resolveBaseUrl: () -> String = { YoutubeDefaults.DEFAULT_PIPED_API_URL },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class AuthResult(val token: String, val username: String)

    suspend fun login(username: String, password: String): AuthResult =
        authPost("/login", username, password)

    suspend fun register(username: String, password: String): AuthResult =
        authPost("/register", username, password)

    /**
     * Real YouTube subscription feed via authenticated TV InnerTube (`FEsubscriptions`).
     */
    suspend fun youtubeAccountFeed(accessToken: String): List<YoutubeVideoItem> =
        withContext(Dispatchers.IO) {
            val token = accessToken.trim()
            require(token.isNotBlank()) { "Not linked to YouTube" }
            val payload = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "TVHTML5")
                            .put("clientVersion", TV_CLIENT_VERSION)
                            .put("hl", "en")
                            .put("gl", "US")
                            .put("platform", "TV")
                            .put("userAgent", TV_BROWSE_UA),
                    ),
                )
                .put("browseId", "FEsubscriptions")
                .toString()
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .header("User-Agent", TV_BROWSE_UA)
                .header("Authorization", "Bearer $token")
                .header("X-YouTube-Client-Name", "7")
                .header("X-YouTube-Client-Version", TV_CLIENT_VERSION)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonMedia))
                .build()
            val body = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw YoutubeApiException(
                        "Subscription feed failed (${response.code}): ${text.take(200)}"
                    )
                }
                text
            }
            val items = parseInnertubeBrowseVideos(body)
            if (items.isEmpty()) {
                throw YoutubeApiException(
                    "YouTube returned an empty subscription feed. Open YouTube once on your phone, then Refresh."
                )
            }
            items
        }

    /** Subscription feed for a logged-in Piped account (legacy / optional). */
    suspend fun feed(authToken: String): List<YoutubeVideoItem> =
        withContext(Dispatchers.IO) {
            val token = authToken.trim()
            require(token.isNotBlank()) { "Not logged in" }
            val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val url = "$base/feed?authToken=${enc(token)}"
            val body = getBody(url)
                ?: throw YoutubeApiException("Could not load feed. Check Piped login and API URL.")
            parseFeedBody(body)
        }

    suspend fun subscriptions(authToken: String): List<YoutubeSubscription> =
        withContext(Dispatchers.IO) {
            val token = authToken.trim()
            require(token.isNotBlank()) { "Not logged in" }
            val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val body = getBody(
                url = "$base/subscriptions",
                authToken = token,
            ) ?: throw YoutubeApiException("Could not load subscriptions.")
            parseSubscriptions(body)
        }

    /**
     * Upload channel IDs to the logged-in Piped account (same as Piped website Import).
     * @return number of channels accepted
     */
    suspend fun importSubscriptions(
        authToken: String,
        channelIds: List<String>,
        override: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        val token = authToken.trim()
        require(token.isNotBlank()) { "Log in to Piped first" }
        val ids = channelIds.map { it.trim() }.filter { it.length == 24 }.distinct()
        if (ids.isEmpty()) throw YoutubeApiException("No channel IDs found in the file")
        val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
        val url = "$base/import?override=$override"
        val payload = JSONArray(ids).toString()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Authorization", token)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val body = client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
        val err = root?.optString("error").orEmpty().ifBlank {
            root?.optString("message").orEmpty()
        }
        if (root != null && err.isNotBlank() && root.optString("message") != "ok") {
            throw YoutubeApiException(err)
        }
        if (root?.optString("message") != "ok" && body.isNotBlank() && !body.contains("ok")) {
            // Some instances return empty 200 on success
            if (root?.has("error") == true) throw YoutubeApiException(err.ifBlank { "Import failed" })
        }
        ids.size
    }

    suspend fun trending(region: String = "US"): List<YoutubeVideoItem> =
        withContext(Dispatchers.IO) {
            val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val url = "$base/trending?region=${enc(region)}"
            val arr = getJsonArray(url)
                ?: throw YoutubeApiException("Instance unreachable or returned no trending data. Try another Piped API URL in Settings → YouTube.")
            parseStreamItems(arr)
        }

    suspend fun search(query: String): List<YoutubeVideoItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) return@withContext emptyList()
            YoutubeDefaults.videoIdFromUrl(q)?.let { id ->
                runCatching { listOf(streams(id).toItem()) }.getOrNull()?.let { return@withContext it }
            }
            val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val url = "$base/search?q=${enc(q)}&filter=videos"
            val root = getJsonObject(url)
                ?: throw YoutubeApiException("Instance unreachable. Check Settings → YouTube Piped API URL.")
            val items = root.optJSONArray("items") ?: JSONArray()
            parseStreamItems(items).ifEmpty {
                // Some instances return a bare array (legacy).
                getJsonArray(url)?.let { parseStreamItems(it) }.orEmpty()
            }
        }

    suspend fun streams(videoId: String): YoutubeStreamInfo =
        withContext(Dispatchers.IO) {
            val id = YoutubeDefaults.videoIdFromUrl(videoId) ?: videoId.trim()
            require(id.isNotBlank()) { "Missing video id" }
            if (!id.matches(Regex("^[\\w-]{11}$"))) {
                throw YoutubeApiException("Invalid video id")
            }
            // Resolve from the TV itself via YouTube Innertube. Piped servers are often
            // bot-blocked (LOGIN_REQUIRED) even when feed/search still work.
            val innertube = fetchStreamsViaInnertube(id)
            if (innertube is StreamsFetch.Ok) return@withContext innertube.info

            val preferred = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val bases = YoutubeDefaults.streamApiCandidates(preferred)
            var lastError = (innertube as? StreamsFetch.Err)?.message ?: "Could not load video"
            for (base in bases) {
                when (val result = fetchStreamsFromInstance(base, id)) {
                    is StreamsFetch.Ok -> return@withContext result.info
                    is StreamsFetch.Err -> {
                        // Keep the most actionable error (bot-block / playability) over DNS noise.
                        if (isActionableStreamError(result.message) ||
                            !isActionableStreamError(lastError)
                        ) {
                            lastError = result.message
                        }
                    }
                }
            }
            throw YoutubeApiException(lastError)
        }

    private fun isActionableStreamError(message: String): Boolean {
        val m = message.lowercase()
        return "bot" in m || "login" in m || "sign in" in m || "unavailable" in m ||
            "age" in m || "private" in m || "playable" in m || "copyright" in m
    }

    private sealed class StreamsFetch {
        data class Ok(val info: YoutubeStreamInfo) : StreamsFetch()
        data class Err(val message: String) : StreamsFetch()
    }

    /**
     * YouTube InnerTube ANDROID player — runs from the device IP (not a Piped datacenter),
     * so it usually avoids the anonymous "confirm you're not a bot" block.
     */
    private fun fetchStreamsViaInnertube(id: String): StreamsFetch {
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "ANDROID")
                        .put("clientVersion", INNERTUBE_CLIENT_VERSION)
                        .put("androidSdkVersion", 30)
                        .put("hl", "en")
                        .put("gl", "US")
                        .put(
                            "userAgent",
                            "com.google.android.youtube/$INNERTUBE_CLIENT_VERSION (Linux; U; Android 14) gzip",
                        ),
                ),
            )
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
        val request = Request.Builder()
            .url("https://youtubei.googleapis.com/youtubei/v1/player?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .header(
                "User-Agent",
                "com.google.android.youtube/$INNERTUBE_CLIENT_VERSION (Linux; U; Android 14) gzip",
            )
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrElse {
            return StreamsFetch.Err(it.message ?: "Innertube request failed")
        }
        if (body.isBlank()) return StreamsFetch.Err("Empty Innertube response")
        val root = runCatching { JSONObject(body) }.getOrElse {
            return StreamsFetch.Err("Bad Innertube JSON")
        }
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        if (status.isNotBlank() && !status.equals("OK", ignoreCase = true)) {
            val reason = playability?.optString("reason").orEmpty()
                .ifBlank { playability?.optJSONObject("errorScreen")?.toString().orEmpty() }
                .ifBlank { status }
            return StreamsFetch.Err(reason.take(220))
        }
        val streaming = root.optJSONObject("streamingData")
            ?: return StreamsFetch.Err("No streamingData from YouTube")
        val details = root.optJSONObject("videoDetails")
        val playbackOptions = pickPlaybackFromInnertube(streaming)
        val playback = playbackOptions.firstOrNull()
            ?: return StreamsFetch.Err("No playable formats from YouTube")
        val micro = root.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
        val thumb = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.optJSONObject(0)?.optString("url").orEmpty()
            .ifBlank {
                micro?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?.optJSONObject(0)?.optString("url").orEmpty()
            }
        return StreamsFetch.Ok(
            YoutubeStreamInfo(
                id = id,
                title = details?.optString("title").orEmpty().ifBlank { "YouTube" },
                uploader = details?.optString("author").orEmpty(),
                thumbnailUrl = thumb,
                durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
                description = details?.optString("shortDescription").orEmpty(),
                livestream = details?.optBoolean("isLiveContent") == true,
                related = emptyList(),
                playback = playback,
                playbackFallbacks = playbackOptions.drop(1),
            )
        )
    }

    private fun pickPlaybackFromInnertube(streaming: JSONObject): List<YoutubePlayback> {
        data class Cand(
            val url: String,
            val height: Int,
            val bitrate: Int,
            val mime: String,
            val hasVideo: Boolean,
            val hasAudio: Boolean,
        )

        fun parseList(arr: JSONArray?): List<Cand> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                    // Skip ciphered URLs (need JS decipher; ANDROID client usually gives plain urls).
                    if (o.has("signatureCipher") || o.has("cipher")) continue
                    val mime = o.optString("mimeType")
                    val height = o.optInt("height")
                    val bitrate = o.optInt("bitrate").takeIf { it > 0 } ?: o.optInt("averageBitrate")
                    add(
                        Cand(
                            url = url,
                            height = height,
                            bitrate = bitrate,
                            mime = mime,
                            hasVideo = mime.startsWith("video/"),
                            hasAudio = mime.startsWith("audio/") ||
                                (mime.startsWith("video/") && mime.contains("mp4a")),
                        )
                    )
                }
            }
        }

        val progressive = parseList(streaming.optJSONArray("formats"))
        val adaptive = parseList(streaming.optJSONArray("adaptiveFormats"))
        val options = mutableListOf<YoutubePlayback>()

        fun isAvc(m: String) = m.contains("avc1") || m.contains("avc")
        fun isAac(m: String) = m.contains("mp4a") || m.startsWith("audio/mp4")

        // Prefer separate adaptive tracks (often 1080p+) — muxed progressive is usually 360p.
        val videos = adaptive.filter { it.hasVideo && it.height in 360..2160 }
            .ifEmpty { adaptive.filter { it.hasVideo } }
        val audios = adaptive.filter { it.hasAudio && !it.hasVideo }

        val bestVideo = videos.maxWithOrNull(
            compareBy<Cand> { it.height }
                .thenBy { if (isAvc(it.mime)) 1 else 0 }
                .thenBy { it.bitrate },
        )
        val bestAudio = audios.maxWithOrNull(
            compareBy<Cand> { if (isAac(it.mime)) 1 else 0 }.thenBy { it.bitrate },
        )
        if (bestVideo != null && bestAudio != null) {
            options += YoutubePlayback.SeparateTracks(
                videoUrl = bestVideo.url,
                audioUrl = bestAudio.url,
                videoMime = bestVideo.mime.substringBefore(';'),
                audioMime = bestAudio.mime.substringBefore(';'),
            )
        }

        streaming.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let {
            options += YoutubePlayback.Hls(it)
        }
        streaming.optString("dashManifestUrl").takeIf { it.isNotBlank() }?.let {
            options += YoutubePlayback.Dash(it)
        }

        // Low-res muxed progressive last (fallback only).
        progressive
            .filter { it.hasVideo && it.hasAudio }
            .maxWithOrNull(compareBy<Cand> { it.height }.thenBy { it.bitrate })
            ?.let { options += YoutubePlayback.Progressive(it.url, it.mime.substringBefore(';')) }

        return options.distinct()
    }

    private fun fetchStreamsFromInstance(base: String, id: String): StreamsFetch {
        // Path segment must stay unencoded (Piped rejects over-encoded ids on some instances).
        val url = "$base/streams/$id"
        val (code, body) = executeGet(url) ?: return StreamsFetch.Err("No response from $base")
        if (body.isBlank()) {
            return StreamsFetch.Err("Empty response from $base (HTTP $code)")
        }
        val root = runCatching { JSONObject(body) }.getOrElse {
            return StreamsFetch.Err("Bad JSON from $base (HTTP $code)")
        }
        val apiError = root.optString("error").ifBlank { root.optString("message") }
        if (code !in 200..299 || (apiError.isNotBlank() && !root.has("title"))) {
            val msg = apiError.ifBlank { "HTTP $code from $base" }
            return StreamsFetch.Err(msg.take(220))
        }
        val title = root.optString("title").ifBlank { "YouTube" }
        val uploader = root.optString("uploader").ifBlank {
            root.optString("uploaderName")
        }
        val playbackOptions = pickPlaybackOptions(root, base)
        val playback = playbackOptions.firstOrNull()
            ?: return StreamsFetch.Err("No playable formats on $base")
        return StreamsFetch.Ok(
            YoutubeStreamInfo(
                id = id,
                title = title,
                uploader = uploader,
                thumbnailUrl = root.optString("thumbnailUrl"),
                durationSec = root.optLong("duration"),
                description = root.optString("description"),
                livestream = root.optBoolean("livestream"),
                related = parseStreamItems(root.optJSONArray("relatedStreams")),
                playback = playback,
                playbackFallbacks = playbackOptions.drop(1),
            )
        )
    }

    private fun YoutubeStreamInfo.toItem() = YoutubeVideoItem(
        id = id,
        title = title,
        uploader = uploader,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
    )

    /**
     * Ordered playback candidates. Prefer Piped DASH/HLS (stable) over googlevideo progressive
     * URLs that often 403 without browser cookies.
     */
    private fun pickPlaybackOptions(root: JSONObject, apiBase: String): List<YoutubePlayback> {
        fun absUrl(raw: String): String {
            val t = raw.trim()
            return when {
                t.startsWith("http://") || t.startsWith("https://") -> t
                t.startsWith("/") -> "$apiBase$t"
                else -> t
            }
        }
        val dash = root.optString("dash").takeIf { it.isNotBlank() && it != "null" }?.let(::absUrl)
        val hls = root.optString("hls").takeIf { it.isNotBlank() && it != "null" }?.let(::absUrl)
        val videoStreams = root.optJSONArray("videoStreams") ?: JSONArray()
        val audioStreams = root.optJSONArray("audioStreams") ?: JSONArray()

        data class Cand(
            val url: String,
            val height: Int,
            val bitrate: Int,
            val mime: String?,
            val videoOnly: Boolean,
            val codec: String,
        )

        fun codecOf(o: JSONObject): String =
            o.optString("codec").ifBlank { o.optString("format") }.lowercase()

        val videos = buildList {
            for (i in 0 until videoStreams.length()) {
                val o = videoStreams.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(
                    Cand(
                        url = url,
                        height = o.optInt("height"),
                        bitrate = o.optInt("bitrate"),
                        mime = o.optString("mimeType").takeIf { it.isNotBlank() },
                        videoOnly = o.optBoolean("videoOnly", true),
                        codec = codecOf(o),
                    )
                )
            }
        }
        val audios = buildList {
            for (i in 0 until audioStreams.length()) {
                val o = audioStreams.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(
                    Cand(
                        url = url,
                        height = 0,
                        bitrate = o.optInt("bitrate"),
                        mime = o.optString("mimeType").takeIf { it.isNotBlank() },
                        videoOnly = false,
                        codec = codecOf(o),
                    )
                )
            }
        }

        fun preferAvc(c: Cand): Int = when {
            c.codec.contains("avc") || c.mime?.contains("avc") == true -> 2
            c.codec.contains("vp9") || c.mime?.contains("vp9") == true -> 1
            c.codec.contains("av01") || c.mime?.contains("av01") == true -> 0
            else -> 1
        }

        fun preferAac(c: Cand): Int = when {
            c.codec.contains("mp4a") || c.mime?.contains("mp4") == true -> 2
            c.codec.contains("opus") || c.mime?.contains("webm") == true -> 1
            else -> 1
        }

        val options = mutableListOf<YoutubePlayback>()
        if (root.optBoolean("livestream") && hls != null) {
            options += YoutubePlayback.Hls(hls)
        }
        if (dash != null) options += YoutubePlayback.Dash(dash)
        if (hls != null && !root.optBoolean("livestream")) options += YoutubePlayback.Hls(hls)

        // Some Piped instances put HLS/MP4 mirrors in videoStreams (mime application/x-mpegurl).
        videos.filter {
            it.mime?.contains("mpegurl", ignoreCase = true) == true ||
                it.mime?.contains("m3u8", ignoreCase = true) == true
        }.forEach { options += YoutubePlayback.Hls(it.url) }

        // Separate adaptive tracks — prefer ≤1080p AVC + AAC for Shield hardware decode.
        val videoOnly = videos.filter { it.videoOnly && it.height in 1..1080 }
            .ifEmpty { videos.filter { it.videoOnly } }
        val bestVideo = videoOnly.maxWithOrNull(
            compareBy<Cand> { preferAvc(it) }.thenBy { it.height }.thenBy { it.bitrate }
        )
        val bestAudio = audios.maxWithOrNull(
            compareBy<Cand> { preferAac(it) }.thenBy { it.bitrate }
        )
        if (bestVideo != null && bestAudio != null) {
            options += YoutubePlayback.SeparateTracks(
                videoUrl = bestVideo.url,
                audioUrl = bestAudio.url,
                videoMime = bestVideo.mime,
                audioMime = bestAudio.mime,
            )
        }

        // Combined progressive / mirror MP4s (odycdn etc.) — videoOnly often omitted.
        videos.filter { !it.videoOnly || it.mime?.startsWith("video/mp4") == true }
            .filter { it.mime?.contains("mpegurl", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Cand> { preferAvc(it) }.thenBy { it.height }.thenBy { it.bitrate })
            ?.let { options += YoutubePlayback.Progressive(it.url, it.mime) }

        videos.maxWithOrNull(compareBy<Cand> { it.height }.thenBy { it.bitrate })
            ?.let { v ->
                if (v.mime?.contains("mpegurl", ignoreCase = true) == true) {
                    val hlsOpt = YoutubePlayback.Hls(v.url)
                    if (hlsOpt !in options) options += hlsOpt
                } else {
                    val progressive = YoutubePlayback.Progressive(v.url, v.mime)
                    if (progressive !in options) options += progressive
                }
            }

        return options.distinct()
    }

    private fun parseStreamItems(arr: JSONArray?): List<YoutubeVideoItem> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.cleanString("type").ifBlank { o.cleanString("itemType") }
                if (type.isNotBlank() &&
                    !type.equals("stream", ignoreCase = true) &&
                    !type.equals("video", ignoreCase = true)
                ) {
                    continue
                }
                val url = o.cleanString("url").ifBlank { o.cleanString("id") }
                val id = YoutubeDefaults.videoIdFromUrl(url)
                    ?: o.cleanString("id").takeIf { it.matches(Regex("^[\\w-]{11}$")) }
                    ?: continue
                val title = o.cleanString("title").takeIf { it.isNotBlank() } ?: continue
                val uploadedEpochMs = parseUploadedEpochMs(o)
                val uploadedLabel = o.cleanString("uploadedDate").ifBlank {
                    formatUploadedEpoch(uploadedEpochMs)
                }
                val views = o.optLong("views").takeIf { it >= 0 } ?: 0L
                add(
                    YoutubeVideoItem(
                        id = id,
                        title = title,
                        uploader = o.cleanString("uploaderName").ifBlank {
                            o.cleanString("uploader")
                        },
                        thumbnailUrl = o.cleanString("thumbnail").ifBlank {
                            o.cleanString("thumbnailUrl")
                        },
                        durationSec = o.optLong("duration").coerceAtLeast(0L),
                        views = views,
                        uploadedDate = uploadedLabel,
                        uploadedEpochMs = uploadedEpochMs,
                    )
                )
            }
        }
    }

    /** JSONObject.optString returns the literal "null" for JSON null — never show that. */
    private fun JSONObject.cleanString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        return if (value.equals("null", ignoreCase = true)) "" else value.trim()
    }

    private fun parseUploadedEpochMs(o: JSONObject): Long {
        val candidates = listOf("uploaded", "uploadDate", "uploadedDate")
        for (key in candidates) {
            if (!o.has(key) || o.isNull(key)) continue
            // Numeric epoch (ms or sec); skip string relative dates.
            val raw = when (val v = o.opt(key)) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull() ?: continue
                else -> continue
            }
            if (raw <= 0L) continue
            return if (raw < 10_000_000_000L) raw * 1000L else raw
        }
        return 0L
    }

    private fun parseFeedBody(body: String): List<YoutubeVideoItem> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val err = root.optString("error").ifBlank { root.optString("message") }
            if (err.isNotBlank()) throw YoutubeApiException(err)
            root.optJSONArray("relatedStreams")?.let { return parseStreamItems(it) }
            root.optJSONArray("items")?.let { return parseStreamItems(it) }
        }
        return parseStreamItems(JSONArray(trimmed))
    }

    /** Walk TV/WEB InnerTube browse JSON for video tiles. */
    private fun parseInnertubeBrowseVideos(body: String): List<YoutubeVideoItem> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val found = LinkedHashMap<String, YoutubeVideoItem>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val videoId = node.optString("videoId").trim()
                        .ifBlank {
                            node.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty().trim()
                        }
                        .ifBlank {
                            node.optJSONObject("onTap")
                                ?.optJSONObject("innertubeCommand")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty().trim()
                        }
                    if (videoId.matches(Regex("^[\\w-]{11}$")) && !found.containsKey(videoId)) {
                        val title = extractText(node.opt("title"))
                            .ifBlank { extractText(node.opt("headline")) }
                            .ifBlank { extractText(node.opt("primaryText")) }
                            .ifBlank { extractText(node.optJSONObject("metadata")?.opt("title")) }
                        if (title.isNotBlank()) {
                            val uploader = extractText(node.opt("shortBylineText"))
                                .ifBlank { extractText(node.opt("longBylineText")) }
                                .ifBlank { extractText(node.opt("ownerText")) }
                                .ifBlank { extractText(node.opt("subtitle")) }
                            val thumb = firstThumbnailUrl(node)
                            val durationSec = parseDurationSeconds(
                                extractText(node.opt("lengthText"))
                                    .ifBlank { extractText(node.opt("thumbnailOverlays")) }
                            )
                            val published = extractText(node.opt("publishedTimeText"))
                                .ifBlank { extractText(node.opt("subtitle")) }
                            found[videoId] = YoutubeVideoItem(
                                id = videoId,
                                title = title,
                                uploader = uploader,
                                thumbnailUrl = thumb.ifBlank {
                                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                },
                                durationSec = durationSec,
                                views = 0L,
                                uploadedDate = published,
                                uploadedEpochMs = 0L,
                            )
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
        }
        walk(root)
        return found.values.toList()
    }

    private fun extractText(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value.trim()
            is JSONObject -> {
                value.optString("simpleText").trim().ifBlank {
                    val runs = value.optJSONArray("runs")
                    if (runs != null) {
                        buildString {
                            for (i in 0 until runs.length()) {
                                append(runs.optJSONObject(i)?.optString("text").orEmpty())
                            }
                        }.trim()
                    } else {
                        ""
                    }
                }
            }
            is JSONArray -> {
                buildString {
                    for (i in 0 until value.length()) {
                        val part = extractText(value.opt(i))
                        if (part.isNotBlank()) {
                            if (isNotEmpty()) append(' ')
                            append(part)
                        }
                    }
                }.trim()
            }
            else -> ""
        }
    }

    private fun firstThumbnailUrl(node: JSONObject): String {
        val candidates = listOf(
            node.optJSONObject("thumbnail"),
            node.optJSONObject("thumbnails"),
            node.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail"),
        )
        for (thumb in candidates) {
            val arr = thumb?.optJSONArray("thumbnails") ?: continue
            for (i in arr.length() - 1 downTo 0) {
                val url = arr.optJSONObject(i)?.optString("url").orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        return ""
    }

    private fun parseDurationSeconds(raw: String): Long {
        val text = raw.trim()
        if (text.isBlank()) return 0L
        val m = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})""").find(text) ?: return 0L
        val hours = m.groupValues[1].toLongOrNull() ?: 0L
        val minutes = m.groupValues[2].toLongOrNull() ?: 0L
        val seconds = m.groupValues[3].toLongOrNull() ?: 0L
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun parseSubscriptions(body: String): List<YoutubeSubscription> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val err = root.optString("error").ifBlank { root.optString("message") }
            if (err.isNotBlank()) throw YoutubeApiException(err)
        }
        val arr = JSONArray(trimmed)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                val id = YoutubeDefaults.channelIdFromUrl(url)
                    ?: o.optString("id").takeIf { it.isNotBlank() }
                    ?: continue
                add(
                    YoutubeSubscription(
                        channelId = id,
                        name = o.optString("name").ifBlank { o.optString("uploader") },
                        avatarUrl = o.optString("avatar").ifBlank { o.optString("thumbnail") },
                    )
                )
            }
        }
    }

    private suspend fun authPost(path: String, username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            val user = username.trim()
            val pass = password
            if (user.isBlank() || pass.isBlank()) {
                throw YoutubeApiException("Enter username and password")
            }
            val base = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            val payload = JSONObject()
                .put("username", user)
                .put("password", pass)
                .toString()
            val request = Request.Builder()
                .url("$base$path")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(jsonMedia))
                .build()
            val body = client.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
            val root = runCatching { JSONObject(body) }.getOrElse {
                throw YoutubeApiException("Unexpected login response")
            }
            val err = root.optString("error").ifBlank { root.optString("message") }
            val token = root.optString("token").trim()
            if (token.isBlank()) {
                throw YoutubeApiException(err.ifBlank { "Login failed" })
            }
            AuthResult(token = token, username = user)
        }

    private fun getJsonObject(url: String): JSONObject? {
        val body = getBody(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getJsonArray(url: String): JSONArray? {
        val body = getBody(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun getBody(url: String, authToken: String? = null): String? {
        val (code, body) = executeGet(url, authToken) ?: return null
        if (code !in 200..299) return null
        return body
    }

    /** Returns HTTP code + body even for error responses (needed for Piped stream errors). */
    private fun executeGet(url: String, authToken: String? = null): Pair<Int, String>? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val builder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", authToken)
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }.getOrNull()
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Public YouTube Android client key used by open clients (not a secret user credential). */
        private const val INNERTUBE_API_KEY = "AIzaSyA8eiZmM1FaDVzRvBK1dDe3-6lQ_g0"
        private const val INNERTUBE_CLIENT_VERSION = "20.10.38"
        private const val TV_CLIENT_VERSION = "7.20250219.19.00"
        private const val TV_BROWSE_UA =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

        /** Format Piped `uploaded` epoch (ms or sec) for UI. */
        fun formatUploadedEpoch(raw: Long): String {
            if (raw <= 0L) return ""
            val ms = if (raw < 10_000_000_000L) raw * 1000L else raw
            val then = java.time.Instant.ofEpochMilli(ms)
            val now = java.time.Instant.now()
            val days = java.time.Duration.between(then, now).toDays()
            return when {
                days < 0 -> ""
                days == 0L -> "Today"
                days == 1L -> "Yesterday"
                days < 7L -> "$days days ago"
                days < 30L -> "${days / 7} weeks ago"
                days < 365L -> "${days / 30} months ago"
                else -> {
                    val date = java.time.LocalDateTime.ofInstant(
                        then,
                        java.time.ZoneId.systemDefault(),
                    )
                    date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
                }
            }
        }

        fun formatViewCount(views: Long): String {
            if (views <= 0L) return ""
            return when {
                views >= 1_000_000_000L -> String.format("%.1fB views", views / 1_000_000_000.0)
                views >= 1_000_000L -> String.format("%.1fM views", views / 1_000_000.0)
                views >= 1_000L -> String.format("%.1fK views", views / 1_000.0)
                else -> "$views views"
            }.replace(".0", "")
        }

        /** Parse Google Takeout `subscriptions.csv` (Channel Id,Channel Url,Channel title). */
        fun parseTakeoutSubscriptionsCsv(text: String): List<String> {
            val ids = LinkedHashSet<String>()
            text.lineSequence().drop(1).forEach { line ->
                val raw = line.trim()
                if (raw.isEmpty()) return@forEach
                val id = raw.substringBefore(',').trim().trim('"')
                if (id.length == 24) ids.add(id)
            }
            return ids.toList()
        }
    }
}

data class YoutubeSubscription(
    val channelId: String,
    val name: String,
    val avatarUrl: String = "",
)

class YoutubeApiException(message: String) : Exception(message)
