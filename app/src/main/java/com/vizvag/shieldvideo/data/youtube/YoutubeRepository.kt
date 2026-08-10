package com.vizvag.shieldvideo.data.youtube

import com.vizvag.shieldvideo.ShieldVideoApp
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
 * YouTube search via [Piped](https://github.com/TeamPiped/Piped); playback via
 * YouTube Innertube (device IP) with Piped as stream fallback. Subscription feed uses a
 * linked YouTube TV account (SmartTube-style device code).
 *
 * Stream resolution follows SmartTube/NewPipe practice: try TVHTML5 / iOS / Android
 * InnerTube clients and prefer DASH so ExoPlayer can pick high-res tracks.
 */
class YoutubeRepository(
    private val resolveBaseUrl: () -> String = { YoutubeDefaults.DEFAULT_PIPED_API_URL },
    private val cacheDir: () -> java.io.File = {
        java.io.File(System.getProperty("java.io.tmpdir"), "pallas_yt")
    },
    private val resolveAccessToken: () -> String? = { null },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Subscription feed via authenticated TV InnerTube (`FEsubscriptions`).
     */
    suspend fun youtubeAccountFeed(accessToken: String): List<YoutubeVideoItem> =
        youtubeBrowseFeed(
            accessToken = accessToken,
            browseId = "FEsubscriptions",
            emptyHint = "YouTube returned an empty subscription feed. Open youtube.com once on your phone, then Refresh.",
            maxItems = 100,
            maxContinuationPages = 5,
        )

    /**
     * Personalized home / “Recommended” shelf (SmartTube Home) via `FEwhat_to_watch`.
     * Follows browse continuations — the first page is often only one short shelf.
     */
    suspend fun youtubeRecommendedFeed(accessToken: String): List<YoutubeVideoItem> =
        youtubeBrowseFeed(
            accessToken = accessToken,
            browseId = "FEwhat_to_watch",
            emptyHint = "YouTube returned an empty recommended feed. Watch something on YouTube, then Refresh.",
            allowEmpty = true,
            maxItems = 120,
            maxContinuationPages = 8,
        )

    /** Channel uploads / home shelf for a `UC…` channel id. */
    suspend fun youtubeChannelFeed(accessToken: String, channelId: String): List<YoutubeVideoItem> {
        val id = YoutubeDefaults.channelIdFromUrl(channelId)
            ?: channelId.trim().takeIf { it.matches(Regex("^UC[\\w-]{22}$")) }
            ?: throw YoutubeApiException("Missing channel id")
        return youtubeBrowseFeed(
            accessToken = accessToken,
            browseId = id,
            emptyHint = "No videos found for this channel.",
            allowEmpty = true,
            maxItems = 80,
            maxContinuationPages = 4,
        )
    }

    private suspend fun youtubeBrowseFeed(
        accessToken: String,
        browseId: String,
        emptyHint: String,
        allowEmpty: Boolean = false,
        maxItems: Int = 80,
        maxContinuationPages: Int = 4,
    ): List<YoutubeVideoItem> = withContext(Dispatchers.IO) {
        val token = accessToken.trim()
        require(token.isNotBlank()) { "Not linked to YouTube" }
        val found = LinkedHashMap<String, YoutubeVideoItem>()
        fun merge(body: String) {
            for (item in parseInnertubeBrowseVideos(body)) {
                val existing = found[item.id]
                if (existing == null) {
                    found[item.id] = item
                } else {
                    found[item.id] = existing.copy(
                        title = preferText(existing.title, item.title, existing.id),
                        uploader = preferText(existing.uploader, item.uploader),
                        thumbnailUrl = preferText(existing.thumbnailUrl, item.thumbnailUrl),
                        durationSec = when {
                            item.durationSec > 0L -> item.durationSec
                            existing.durationSec > 0L -> existing.durationSec
                            item.durationSec < 0L -> item.durationSec
                            else -> existing.durationSec
                        },
                        views = maxOf(existing.views, item.views),
                        uploadedDate = preferText(existing.uploadedDate, item.uploadedDate),
                        uploadedEpochMs = maxOf(existing.uploadedEpochMs, item.uploadedEpochMs),
                        channelId = preferText(existing.channelId, item.channelId),
                    )
                }
            }
        }

        var body = fetchTvBrowse(token, browseId = browseId, continuation = null)
        merge(body)
        val seenTokens = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        fun enqueueContinuations(raw: String) {
            for (t in extractContinuationTokens(raw)) {
                if (seenTokens.add(t)) queue.addLast(t)
            }
        }
        enqueueContinuations(body)
        var pages = 0
        while (found.size < maxItems && queue.isNotEmpty() && pages < maxContinuationPages) {
            val cont = queue.removeFirst()
            val nextBody = runCatching {
                fetchTvBrowse(token, browseId = null, continuation = cont)
            }.getOrNull() ?: break
            body = nextBody
            merge(body)
            enqueueContinuations(body)
            pages++
        }

        val items = found.values.toList()
        if (items.isEmpty() && !allowEmpty) {
            val hint = when {
                body.contains("\"SIGN_IN\"", ignoreCase = true) ||
                    (
                        body.contains("login", ignoreCase = true) &&
                            body.contains("required", ignoreCase = true)
                        ) ->
                    "YouTube says sign-in is required — unlink and Link YouTube again in Settings."
                body.contains("tvBrowseRenderer") || body.contains("tileRenderer") ||
                    body.contains("richItemRenderer") || body.contains("videoRenderer") ->
                    "Could not read videos from YouTube’s response. Try Refresh."
                else -> emptyHint
            }
            throw YoutubeApiException(hint)
        }
        items.take(maxItems)
    }

    private fun fetchTvBrowse(
        accessToken: String,
        browseId: String?,
        continuation: String?,
    ): String {
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
                        .put("osName", "Tizen")
                        .put("osVersion", "5.0")
                        .put("deviceMake", "Samsung")
                        .put("deviceModel", "SmartTV")
                        .put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
                        .put("screenPixelDensity", 1)
                        .put("userAgent", TV_BROWSE_UA),
                ),
            )
        if (!continuation.isNullOrBlank()) {
            payload.put("continuation", continuation)
        } else {
            payload.put("browseId", browseId.orEmpty())
        }
        // googleapis host + TV key matches SmartTube/Lincoln; www.youtube.com often
        // returns a sparse shelf for the same token.
        val request = Request.Builder()
            .url("https://www.googleapis.com/youtubei/v1/browse?key=$TV_API_KEY&prettyPrint=false")
            .header("User-Agent", TV_BROWSE_UA)
            .header("Authorization", "Bearer $accessToken")
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", TV_CLIENT_VERSION)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw YoutubeApiException(
                    "YouTube browse failed (${browseId ?: "continuation"}, ${response.code}): ${text.take(200)}"
                )
            }
            text
        }
    }

    /** Tokens from `continuationItemRenderer` / continuation commands (browse pagination). */
    private fun extractContinuationTokens(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val tokens = LinkedHashSet<String>()
        fun consider(token: String?) {
            val t = token?.trim().orEmpty()
            if (t.length >= 40) tokens.add(t)
        }
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val cir = node.optJSONObject("continuationItemRenderer")
                    if (cir != null) {
                        consider(
                            cir.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token"),
                        )
                        consider(
                            cir.optJSONObject("button")
                                ?.optJSONObject("buttonRenderer")
                                ?.optJSONObject("command")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token"),
                        )
                    }
                    consider(
                        node.optJSONObject("continuationCommand")?.optString("token"),
                    )
                    consider(
                        node.optJSONObject("nextContinuationData")?.optString("continuation"),
                    )
                    consider(
                        node.optJSONObject("reloadContinuationData")?.optString("continuation"),
                    )
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
        return tokens.toList()
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

            var lastError = "Could not load video"

            // ANDROID_VR first — no poToken required for googlevideo (when not bot-blocked).
            when (val vr = fetchStreamsWithClient(id, InnertubeClient.AndroidVr)) {
                is StreamsFetch.Ok -> {
                    if (playbackWorksOnDevice(vr.info)) {
                        android.util.Log.i("YoutubeStreams", "using ANDROID_VR for $id")
                        return@withContext vr.info
                    }
                    lastError = "ANDROID_VR streams blocked at playback"
                }
                is StreamsFetch.Err -> lastError = vr.message
            }

            // Embedded player — no pot; works for most public / embeddable videos.
            when (val emb = fetchStreamsWithClient(id, InnertubeClient.WebEmbedded)) {
                is StreamsFetch.Ok -> {
                    if (playbackWorksOnDevice(emb.info)) {
                        android.util.Log.i("YoutubeStreams", "using WEB_EMBEDDED for $id")
                        return@withContext emb.info
                    }
                    lastError = "WEB_EMBEDDED streams blocked at playback"
                }
                is StreamsFetch.Err -> lastError = emb.message
            }

            // NewPipe + videoId-bound poToken (SmartTube / current YouTube).
            runCatching {
                YoutubeNewPipeInit.awaitPoTokenReady(timeoutMs = 90_000L)
                val raw = YoutubeNewPipeStreams.fetch(id)
                val pot = streamingPoTokenFor(id)
                YoutubePoTokenUrls.enhanceStreamInfo(raw, pot)
            }.onSuccess { info ->
                if (playbackWorksOnDevice(info)) {
                    android.util.Log.i(
                        "YoutubeStreams",
                        "using NewPipe+pot for $id hasPot=${YoutubePoTokenUrls.playbackHasPot(info.playback)}",
                    )
                    return@withContext info
                }
                lastError = "NewPipe streams blocked at playback"
            }.onFailure { e ->
                lastError = e.message ?: lastError
                android.util.Log.e("YoutubeStreams", "NewPipe failed for $id: $lastError")
            }

            // Authenticated TV Innertube when linked.
            val accessToken = resolveAccessToken()
            if (!accessToken.isNullOrBlank()) {
                when (val tv = fetchStreamsWithClient(id, InnertubeClient.Tv(accessToken))) {
                    is StreamsFetch.Ok -> {
                        if (playbackWorksOnDevice(tv.info)) return@withContext tv.info
                        lastError = "TV account streams blocked (HTTP 403)"
                    }
                    is StreamsFetch.Err -> lastError = tv.message
                }
            }

            val innertube = fetchStreamsViaInnertube(id)
            if (innertube is StreamsFetch.Ok && playbackWorksOnDevice(innertube.info)) {
                return@withContext innertube.info
            }
            if (innertube is StreamsFetch.Err) lastError = innertube.message

            val preferred = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
            for (base in YoutubeDefaults.streamApiCandidates(preferred)) {
                when (val result = fetchStreamsFromInstance(base, id)) {
                    is StreamsFetch.Ok -> {
                        if (playbackWorksOnDevice(result.info)) return@withContext result.info
                        lastError = "Piped streams blocked at playback"
                    }
                    is StreamsFetch.Err -> {
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

    /** GVS poToken for innertube googlevideo URLs (403 without `pot=`). */
    private fun streamingPoTokenFor(videoId: String): String? =
        YoutubeNewPipeInit.streamingPoTokenForVideo(
            ShieldVideoApp.instance.applicationContext,
            videoId,
        )

    /** Probe primary playback URLs on-device (Range fetch). */
    private fun playbackWorksOnDevice(info: YoutubeStreamInfo): Boolean {
        val ua = info.playbackUserAgent.ifBlank { YoutubeDefaults.PLAYBACK_USER_AGENT }
        return probePlayback(info.playback, ua)
    }

    /** Tallest advertised video height for [videoId], or 0 if unknown / unavailable. */
    suspend fun maxVideoHeight(videoId: String): Int = withContext(Dispatchers.IO) {
        val id = YoutubeDefaults.videoIdFromUrl(videoId) ?: videoId.trim()
        if (!id.matches(Regex("^[\\w-]{11}$"))) return@withContext 0
        when (val innertube = fetchStreamsViaInnertube(id)) {
            is StreamsFetch.Ok -> return@withContext innertube.info.maxHeight
            is StreamsFetch.Err -> Unit
        }
        val preferred = YoutubeDefaults.normalizeApiUrl(resolveBaseUrl())
        for (base in YoutubeDefaults.streamApiCandidates(preferred)) {
            when (val result = fetchStreamsFromInstance(base, id)) {
                is StreamsFetch.Ok -> return@withContext result.info.maxHeight
                is StreamsFetch.Err -> Unit
            }
        }
        0
    }

    /** googlevideo URLs without `pot=` need WebView poToken (403 at ExoPlayer otherwise). */
    private fun YoutubeStreamInfo.needsPoTokenFallback(): Boolean =
        playbackUrls().any { it.contains("googlevideo.com") && !it.hasPoToken() }

    private fun YoutubeStreamInfo.playbackUrls(): List<String> = buildList {
        fun add(pb: YoutubePlayback) {
            when (pb) {
                is YoutubePlayback.Progressive -> add(pb.url)
                is YoutubePlayback.Dash -> add(pb.url)
                is YoutubePlayback.Hls -> add(pb.url)
                is YoutubePlayback.SeparateTracks -> {
                    add(pb.videoUrl)
                    add(pb.audioUrl)
                }
            }
        }
        add(playback)
        playbackFallbacks.forEach(::add)
    }

    private fun String.hasPoToken(): Boolean =
        contains("pot=") || contains("pot%3D", ignoreCase = true)

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
     * SmartTube-style: try several InnerTube clients and keep the richest streamingData
     * (DASH + tallest adaptive). ANDROID alone often yields only low progressive / throttled URLs.
     */
    private fun fetchStreamsViaInnertube(id: String): StreamsFetch {
        val attempts = buildList {
            resolveAccessToken()?.takeIf { it.isNotBlank() }?.let { add(InnertubeClient.Tv(it)) }
            add(InnertubeClient.AndroidVr) // no poToken required for GVS
            add(InnertubeClient.WebEmbedded)
            add(InnertubeClient.Ios)
            add(InnertubeClient.Android)
            add(InnertubeClient.Tv(null))
        }
        var best: StreamsFetch.Ok? = null
        var lastErr: String? = null
        for (clientSpec in attempts) {
            when (val result = fetchStreamsWithClient(id, clientSpec)) {
                is StreamsFetch.Ok -> {
                    val score = streamScore(result.info)
                    val bestScore = best?.let { streamScore(it.info) } ?: -1
                    if (score > bestScore) best = result
                    // Good enough: working separate tracks or remote DASH at 720p+.
                    if (result.info.maxHeight >= 720 &&
                        (
                            result.info.playback is YoutubePlayback.SeparateTracks ||
                                (
                                    result.info.playback is YoutubePlayback.Dash &&
                                        result.info.playback.url.startsWith("http")
                                    )
                            )
                    ) {
                        return result
                    }
                }
                is StreamsFetch.Err -> lastErr = result.message
            }
        }
        return best ?: StreamsFetch.Err(lastErr ?: "No playable formats from YouTube")
    }

    private fun streamScore(info: YoutubeStreamInfo): Int {
        var score = info.maxHeight
        when (val playback = info.playback) {
            is YoutubePlayback.SeparateTracks -> score += 12_000
            is YoutubePlayback.Dash -> score += if (playback.url.startsWith("http")) 10_000 else 0
            is YoutubePlayback.Hls -> score += 5_000
            is YoutubePlayback.Progressive -> score += 3_000
        }
        score += info.qualities.size * 10
        return score
    }

    private sealed class InnertubeClient {
        abstract val name: String
        abstract val version: String
        abstract val clientId: String
        abstract val apiKey: String
        abstract val userAgent: String
        open val accessToken: String? get() = null
        open fun clientJson(): JSONObject = JSONObject()
            .put("clientName", name)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")

        class Tv(override val accessToken: String?) : InnertubeClient() {
            override val name = "TVHTML5"
            override val version = TV_CLIENT_VERSION
            override val clientId = "7"
            override val apiKey = TV_API_KEY
            override val userAgent = TV_BROWSE_UA
            override fun clientJson(): JSONObject = super.clientJson()
                .put("platform", "TV")
                .put("osName", "Tizen")
                .put("osVersion", "5.0")
                .put("deviceMake", "Samsung")
                .put("deviceModel", "SmartTV")
                .put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
                .put("userAgent", userAgent)
        }

        data object Ios : InnertubeClient() {
            override val name = "IOS"
            override val version = IOS_CLIENT_VERSION
            override val clientId = "5"
            override val apiKey = IOS_API_KEY
            override val userAgent =
                "com.google.ios.youtube/$IOS_CLIENT_VERSION ($IOS_DEVICE_MODEL; U; CPU iOS $IOS_UA_VERSION like Mac OS X)"
            override fun clientJson(): JSONObject = super.clientJson()
                .put("deviceMake", "Apple")
                .put("deviceModel", IOS_DEVICE_MODEL)
                .put("osName", "iPhone")
                .put("osVersion", IOS_OS_VERSION)
                .put("userAgent", userAgent)
        }

        data object Android : InnertubeClient() {
            override val name = "ANDROID"
            override val version = INNERTUBE_CLIENT_VERSION
            override val clientId = "3"
            override val apiKey = INNERTUBE_API_KEY
            override val userAgent =
                "com.google.android.youtube/$INNERTUBE_CLIENT_VERSION (Linux; U; Android 14) gzip"
            override fun clientJson(): JSONObject = super.clientJson()
                .put("androidSdkVersion", 30)
                .put("userAgent", userAgent)
        }

        /** Quest / Android VR — yt-dlp: GVS poToken not required. */
        data object AndroidVr : InnertubeClient() {
            override val name = "ANDROID_VR"
            override val version = ANDROID_VR_CLIENT_VERSION
            override val clientId = "28"
            override val apiKey = INNERTUBE_API_KEY
            override val userAgent =
                "com.google.android.apps.youtube.vr.oculus/$ANDROID_VR_CLIENT_VERSION " +
                    "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            override fun clientJson(): JSONObject = super.clientJson()
                .put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3")
                .put("osName", "Android")
                .put("osVersion", "12L")
                .put("androidSdkVersion", 32)
                .put("userAgent", userAgent)
        }

        /** Embedded web player — no poToken; works for most public videos. */
        data object WebEmbedded : InnertubeClient() {
            override val name = "WEB_EMBEDDED_PLAYER"
            override val version = WEB_EMBEDDED_CLIENT_VERSION
            override val clientId = "56"
            override val apiKey = WEB_EMBEDDED_API_KEY
            override val userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            override fun clientJson(): JSONObject = super.clientJson()
                .put("clientScreen", "EMBED")
                .put("userAgent", userAgent)
        }
    }

    private fun fetchStreamsWithClient(id: String, clientSpec: InnertubeClient): StreamsFetch {
        val context = JSONObject().put("client", clientSpec.clientJson())
        if (clientSpec is InnertubeClient.WebEmbedded) {
            context.put(
                "thirdParty",
                JSONObject().put("embedUrl", "https://www.youtube.com/"),
            )
        }
        val payload = JSONObject()
            .put("context", context)
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
        val url = "https://youtubei.googleapis.com/youtubei/v1/player?key=${clientSpec.apiKey}&prettyPrint=false"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", clientSpec.userAgent)
            .header("X-YouTube-Client-Name", clientSpec.clientId)
            .header("X-YouTube-Client-Version", clientSpec.version)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
        clientSpec.accessToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }
        val body = runCatching {
            client.newCall(requestBuilder.build()).execute().use { it.body?.string().orEmpty() }
        }.getOrElse {
            return StreamsFetch.Err(it.message ?: "${clientSpec.name} request failed")
        }
        if (body.isBlank()) return StreamsFetch.Err("Empty ${clientSpec.name} response")
        val root = runCatching { JSONObject(body) }.getOrElse {
            return StreamsFetch.Err("Bad ${clientSpec.name} JSON")
        }
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        if (status.isNotBlank() && !status.equals("OK", ignoreCase = true)) {
            val reason = playability?.optString("reason").orEmpty()
                .ifBlank { status }
            return StreamsFetch.Err("${clientSpec.name}: ${reason.take(200)}")
        }
        val streaming = root.optJSONObject("streamingData")
            ?: return StreamsFetch.Err("${clientSpec.name}: no streamingData")
        val details = root.optJSONObject("videoDetails")
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val rawOptions = pickPlaybackFromInnertube(streaming, id, durationSec)
        // ANDROID_VR streams work without pot=; adding videoId pot can 403 them.
        val pot = when (clientSpec) {
            is InnertubeClient.AndroidVr, is InnertubeClient.WebEmbedded -> null
            else -> streamingPoTokenFor(id)
        }
        val enhancedOptions = rawOptions.map { YoutubePoTokenUrls.enhancePlayback(it, pot) }
        val playbackOptions = orderPlaybackByProbe(enhancedOptions, clientSpec.userAgent)
        val playback = playbackOptions.firstOrNull()
            ?: return StreamsFetch.Err("${clientSpec.name}: no reachable formats (HTTP 403)")
        val qualities = qualityOptionsFromInnertube(streaming).map { q ->
            val enhanced = YoutubePoTokenUrls.enhancePlayback(q.playback, pot)
            if (enhanced is YoutubePlayback.SeparateTracks) q.copy(playback = enhanced) else q
        }
        val micro = root.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
        val thumb = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.optJSONObject(0)?.optString("url").orEmpty()
            .ifBlank {
                micro?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?.optJSONObject(0)?.optString("url").orEmpty()
            }
        val maxH = maxOf(
            maxHeightFromStreamingData(streaming),
            qualities.maxOfOrNull { it.height } ?: 0,
        )
        return StreamsFetch.Ok(
            YoutubeStreamInfo(
                id = id,
                title = details?.optString("title").orEmpty().ifBlank { "YouTube" },
                uploader = details?.optString("author").orEmpty(),
                thumbnailUrl = thumb,
                durationSec = durationSec,
                description = details?.optString("shortDescription").orEmpty(),
                livestream = details?.optBoolean("isLiveContent") == true,
                related = emptyList(),
                playback = playback,
                playbackFallbacks = playbackOptions.drop(1),
                channelId = details?.optString("channelId").orEmpty().trim(),
                maxHeight = maxH,
                qualities = qualities,
                playbackUserAgent = clientSpec.userAgent,
            ),
        )
    }

    private fun maxHeightFromStreamingData(streaming: JSONObject): Int {
        var maxH = 0
        fun scan(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = formatHeight(o)
                if (h > maxH) maxH = h
            }
        }
        scan(streaming.optJSONArray("adaptiveFormats"))
        scan(streaming.optJSONArray("formats"))
        return maxH
    }

    /** Prefer numeric height; fall back to qualityLabel ("1080p60") / quality ("hd1080"). */
    private fun formatHeight(o: JSONObject): Int {
        val direct = o.optInt("height")
        if (direct > 0) return direct
        val label = o.optString("qualityLabel").ifBlank { o.optString("quality") }
        Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE).find(label)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when (label.lowercase()) {
            "hd2160", "highres", "4k", "uhd" -> 2160
            "hd1440", "qhd" -> 1440
            "hd1080", "fhd" -> 1080
            "hd720" -> 720
            "large" -> 480
            "medium" -> 360
            "small" -> 240
            "tiny" -> 144
            else -> 0
        }
    }

    private fun pickPlaybackFromInnertube(
        streaming: JSONObject,
        videoId: String,
        durationSec: Long,
    ): List<YoutubePlayback> {
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
                    // Skip ciphered URLs (need JS decipher; TV/iOS/Android usually give plain urls).
                    if (o.has("signatureCipher") || o.has("cipher")) continue
                    val mime = o.optString("mimeType")
                    val height = formatHeight(o)
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
                        ),
                    )
                }
            }
        }

        val progressive = parseList(streaming.optJSONArray("formats"))
        val adaptive = parseList(streaming.optJSONArray("adaptiveFormats"))
        val options = mutableListOf<YoutubePlayback>()

        fun isAvc(m: String) = m.contains("avc1") || m.contains("avc")
        fun isVp9(m: String) = m.contains("vp9") || m.contains("vp09")
        fun isAac(m: String) = m.contains("mp4a") || m.startsWith("audio/mp4")

        val videos = adaptive.filter { it.hasVideo && it.height >= 144 }
            .ifEmpty { adaptive.filter { it.hasVideo } }
        val audios = adaptive.filter { it.hasAudio && !it.hasVideo }

        // Official remote DASH first — track selector picks quality; avoids 403 on raw googlevideo URLs.
        streaming.optString("dashManifestUrl").takeIf { it.startsWith("http") }?.let {
            options += YoutubePlayback.Dash(it)
        }

        // SeparateTracks — high-res VP9/AVC when DASH is unavailable.
        val bestAudio = audios.maxWithOrNull(
            compareBy<Cand> { if (isAac(it.mime)) 1 else 0 }.thenBy { it.bitrate },
        )
        val bestVideo = videos.maxWithOrNull(
            compareBy<Cand> { it.height }
                .thenBy {
                    when {
                        isVp9(it.mime) -> 2
                        isAvc(it.mime) -> 1
                        else -> 0
                    }
                }
                .thenBy { it.bitrate },
        )
        if (bestVideo != null && bestAudio != null) {
            val audio = if (bestVideo.mime.startsWith("video/mp4") || isAvc(bestVideo.mime)) {
                audios.maxWithOrNull(
                    compareBy<Cand> { if (isAac(it.mime)) 1 else 0 }.thenBy { it.bitrate },
                ) ?: bestAudio
            } else {
                audios.maxWithOrNull(
                    compareBy<Cand> {
                        if (it.mime.contains("webm") || it.mime.contains("opus")) 1 else 0
                    }.thenBy { it.bitrate },
                ) ?: bestAudio
            }
            options += YoutubePlayback.SeparateTracks(
                videoUrl = bestVideo.url,
                audioUrl = audio.url,
                videoMime = bestVideo.mime.substringBefore(';'),
                audioMime = audio.mime.substringBefore(';'),
            )
        }

        // Low-res muxed progressive — reliable fallback.
        progressive
            .filter { it.hasVideo && it.hasAudio }
            .maxWithOrNull(compareBy<Cand> { it.height }.thenBy { it.bitrate })
            ?.let { options += YoutubePlayback.Progressive(it.url, it.mime.substringBefore(';')) }

        streaming.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let {
            options += YoutubePlayback.Hls(it)
        }

        return options.distinct()
    }

    /** Keep only formats that respond on this device with the Innertube client User-Agent. */
    private fun orderPlaybackByProbe(
        options: List<YoutubePlayback>,
        userAgent: String,
    ): List<YoutubePlayback> = options.filter { probePlayback(it, userAgent) }

    private fun probePlayback(playback: YoutubePlayback, userAgent: String): Boolean = when (playback) {
        is YoutubePlayback.Progressive -> probeStreamUrl(playback.url, userAgent)
        is YoutubePlayback.Dash -> probeStreamUrl(playback.url, userAgent)
        is YoutubePlayback.Hls -> probeStreamUrl(playback.url, userAgent)
        is YoutubePlayback.SeparateTracks ->
            probeStreamUrl(playback.videoUrl, userAgent) && probeStreamUrl(playback.audioUrl, userAgent)
    }

    private fun probeStreamUrl(url: String, userAgent: String): Boolean {
        // Do not reject googlevideo without pot= here — ANDROID_VR / some HLS paths
        // legitimately work without it. Pot is applied at fetch for clients that need it.
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .header("Range", "bytes=0-65535")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
    }

    private fun qualityOptionsFromInnertube(streaming: JSONObject): List<YoutubeQualityOption> {
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
                    if (o.has("signatureCipher") || o.has("cipher")) continue
                    val mime = o.optString("mimeType")
                    add(
                        Cand(
                            url = url,
                            height = formatHeight(o),
                            bitrate = o.optInt("bitrate").takeIf { it > 0 } ?: o.optInt("averageBitrate"),
                            mime = mime,
                            hasVideo = mime.startsWith("video/"),
                            hasAudio = mime.startsWith("audio/") ||
                                (mime.startsWith("video/") && mime.contains("mp4a")),
                        ),
                    )
                }
            }
        }
        fun isAvc(m: String) = m.contains("avc1") || m.contains("avc")
        fun isVp9(m: String) = m.contains("vp9") || m.contains("vp09")
        fun isAac(m: String) = m.contains("mp4a") || m.startsWith("audio/mp4")
        fun isOpus(m: String) = m.contains("opus") || m.contains("webm")
        fun isMp4Video(m: String) = m.startsWith("video/mp4") || isAvc(m)
        fun isWebmVideo(m: String) = m.startsWith("video/webm") || isVp9(m) || m.contains("av01")

        val adaptive = parseList(streaming.optJSONArray("adaptiveFormats"))
        val videos = adaptive.filter { it.hasVideo && it.height > 0 }
        val audios = adaptive.filter { it.hasAudio && !it.hasVideo }
        if (audios.isEmpty() || videos.isEmpty()) return emptyList()

        fun audioForVideo(video: Cand): Cand? {
            val preferAac = isMp4Video(video.mime)
            return audios.maxWithOrNull(
                compareBy<Cand> {
                    when {
                        preferAac && isAac(it.mime) -> 2
                        !preferAac && isOpus(it.mime) -> 2
                        preferAac && isOpus(it.mime) -> 0
                        !preferAac && isAac(it.mime) -> 0
                        else -> 1
                    }
                }.thenBy { it.bitrate },
            )
        }

        // Prefer VP9/AVC ladder — skip AV1 (SmartTube: many TVs fail AV1; VP9 is best on Shield).
        val usable = videos.filter {
            !it.mime.contains("av01", ignoreCase = true)
        }.ifEmpty { videos }
        val vp9OrAvc = usable.filter { isVp9(it.mime) || isAvc(it.mime) }
        val sourceByHeight = (vp9OrAvc.ifEmpty { usable }).groupBy { it.height }

        return sourceByHeight
            .mapNotNull { (height, cands) ->
                val video = cands.maxWithOrNull(
                    compareBy<Cand> {
                        when {
                            isVp9(it.mime) -> 2
                            isAvc(it.mime) -> 1
                            else -> 0
                        }
                    }.thenBy { it.bitrate },
                ) ?: return@mapNotNull null
                val audio = audioForVideo(video) ?: return@mapNotNull null
                val label = YoutubeResolutionCache.labelForHeight(height) ?: "${height}p"
                YoutubeQualityOption(
                    height = height,
                    label = label,
                    playback = YoutubePlayback.SeparateTracks(
                        videoUrl = video.url,
                        audioUrl = audio.url,
                        videoMime = video.mime.substringBefore(';'),
                        audioMime = audio.mime.substringBefore(';'),
                    ),
                )
            }
            .sortedByDescending { it.height }
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
        val playbackPick = pickPlaybackOptions(root, base)
        val playback = playbackPick.options.firstOrNull()
            ?: return StreamsFetch.Err("No playable formats on $base")
        val videoStreams = root.optJSONArray("videoStreams") ?: JSONArray()
        var maxH = playbackPick.qualities.maxOfOrNull { it.height } ?: 0
        for (i in 0 until videoStreams.length()) {
            val h = videoStreams.optJSONObject(i)?.optInt("height") ?: 0
            if (h > maxH) maxH = h
        }
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
                playbackFallbacks = playbackPick.options.drop(1),
                maxHeight = maxH,
                qualities = playbackPick.qualities,
                playbackUserAgent = YoutubeDefaults.PLAYBACK_USER_AGENT,
            )
        )
    }

    private fun YoutubeStreamInfo.toItem() = YoutubeVideoItem(
        id = id,
        title = title,
        uploader = uploader,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        resolutionLabel = YoutubeResolutionCache.labelForHeight(maxHeight),
    )

    /**
     * Ordered playback candidates. Prefer discrete high-res SeparateTracks first so adaptive
     * DASH/HLS does not start at 360p; livestreams keep HLS first.
     */
    private data class PipedPlaybackPick(
        val options: List<YoutubePlayback>,
        val qualities: List<YoutubeQualityOption>,
    )

    private fun pickPlaybackOptions(root: JSONObject, apiBase: String): PipedPlaybackPick {
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
                val url = o.optString("url").takeIf { it.isNotBlank() }?.let(::absUrl) ?: continue
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
                val url = o.optString("url").takeIf { it.isNotBlank() }?.let(::absUrl) ?: continue
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

        videos.filter {
            it.mime?.contains("mpegurl", ignoreCase = true) == true ||
                it.mime?.contains("m3u8", ignoreCase = true) == true
        }.forEach { options += YoutubePlayback.Hls(it.url) }

        val videoOnly = videos.filter { it.videoOnly && it.height > 0 }
            .ifEmpty { videos.filter { it.videoOnly } }
        val bestAudio = audios.maxWithOrNull(
            compareBy<Cand> { preferAac(it) }.thenBy { it.bitrate },
        )
        val bestVideo = videoOnly.maxWithOrNull(
            compareBy<Cand> { it.height }.thenBy { preferAvc(it) }.thenBy { it.bitrate },
        )
        if (bestVideo != null && bestAudio != null) {
            options += YoutubePlayback.SeparateTracks(
                videoUrl = bestVideo.url,
                audioUrl = bestAudio.url,
                videoMime = bestVideo.mime,
                audioMime = bestAudio.mime,
            )
        }

        if (dash != null) options.add(0, YoutubePlayback.Dash(dash))
        if (hls != null && !root.optBoolean("livestream")) options += YoutubePlayback.Hls(hls)

        videos.filter { !it.videoOnly || it.mime?.startsWith("video/mp4") == true }
            .filter { it.mime?.contains("mpegurl", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Cand> { it.height }.thenBy { preferAvc(it) }.thenBy { it.bitrate })
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

        val qualities = if (bestAudio == null) {
            emptyList()
        } else {
            fun audioFor(video: Cand): Cand {
                val wantAac = preferAvc(video) >= 2 ||
                    video.mime?.contains("mp4", ignoreCase = true) == true
                return audios.maxWithOrNull(
                    compareBy<Cand> {
                        when {
                            wantAac && preferAac(it) >= 2 -> 2
                            !wantAac && preferAac(it) < 2 -> 2
                            else -> 1
                        }
                    }.thenBy { it.bitrate },
                ) ?: bestAudio
            }
            val avcOnly = videoOnly.filter { preferAvc(it) >= 2 }
            val ladder = avcOnly.ifEmpty { videoOnly }
            ladder
                .groupBy { it.height }
                .mapNotNull { (height, cands) ->
                    if (height <= 0) return@mapNotNull null
                    val video = cands.maxWithOrNull(
                        compareBy<Cand> { preferAvc(it) }.thenBy { it.bitrate },
                    ) ?: return@mapNotNull null
                    val audio = audioFor(video)
                    YoutubeQualityOption(
                        height = height,
                        label = YoutubeResolutionCache.labelForHeight(height) ?: "${height}p",
                        playback = YoutubePlayback.SeparateTracks(
                            videoUrl = video.url,
                            audioUrl = audio.url,
                            videoMime = video.mime,
                            audioMime = audio.mime,
                        ),
                    )
                }
                .sortedByDescending { it.height }
        }

        return PipedPlaybackPick(options = options.distinct(), qualities = qualities)
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

        fun putItem(item: YoutubeVideoItem) {
            val existing = found[item.id]
            if (existing == null) {
                found[item.id] = item
                return
            }
            found[item.id] = existing.copy(
                title = preferText(existing.title, item.title, existing.id),
                uploader = preferText(existing.uploader, item.uploader),
                thumbnailUrl = preferText(existing.thumbnailUrl, item.thumbnailUrl),
                durationSec = when {
                    item.durationSec > 0L -> item.durationSec
                    existing.durationSec > 0L -> existing.durationSec
                    item.durationSec < 0L -> item.durationSec // live
                    else -> existing.durationSec
                },
                views = maxOf(existing.views, item.views),
                uploadedDate = preferText(existing.uploadedDate, item.uploadedDate),
                uploadedEpochMs = maxOf(existing.uploadedEpochMs, item.uploadedEpochMs),
                channelId = preferText(existing.channelId, item.channelId),
            )
        }

        fun fromTileRenderer(tile: JSONObject) {
            val videoId = tile.optJSONObject("onSelectCommand")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty().trim()
                .ifBlank {
                    tile.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId").orEmpty().trim()
                }
                .ifBlank {
                    tile.optJSONObject("onTap")
                        ?.optJSONObject("innertubeCommand")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId").orEmpty().trim()
                }
            if (!videoId.matches(Regex("^[\\w-]{11}$"))) return
            val meta = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
            val title = extractText(meta?.opt("title"))
                .ifBlank { extractText(tile.opt("title")) }
                .ifBlank { extractText(tile.opt("headline")) }
                .ifBlank { videoId }
            val line0 = lineItemTexts(meta?.optJSONArray("lines")?.optJSONObject(0))
            val line1 = lineItemTexts(meta?.optJSONArray("lines")?.optJSONObject(1))
            val menuSubtitle = extractText(
                tile.optJSONObject("onLongPressCommand")
                    ?.optJSONObject("showMenuCommand")
                    ?.opt("subtitle"),
            ).removePrefix("@").trim()
                .substringBefore("·").substringBefore("•").trim()
            val uploader = menuSubtitle
                .ifBlank { line0.firstOrNull().orEmpty() }
                .ifBlank { extractText(tile.opt("shortBylineText")) }
                .ifBlank { extractText(tile.opt("longBylineText")) }
                .removePrefix("@").trim()
            val durationSec = tileDurationSeconds(tile)
            val published = line1.firstOrNull { looksLikeRelativeDate(it) }.orEmpty()
                .ifBlank { line0.firstOrNull { looksLikeRelativeDate(it) }.orEmpty() }
                .ifBlank { extractText(tile.opt("publishedTimeText")) }
            val views = line1.firstOrNull { looksLikeViewCount(it) }
                ?.let { parseViewCountLabel(it) }
                ?: 0L
            val channelId = tileChannelId(tile)
            putItem(
                YoutubeVideoItem(
                    id = videoId,
                    title = title,
                    uploader = uploader,
                    thumbnailUrl = firstThumbnailUrl(tile).ifBlank {
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    },
                    durationSec = durationSec,
                    views = views,
                    uploadedDate = published,
                    uploadedEpochMs = relativeDateToEpochMs(published),
                    channelId = channelId,
                ),
            )
        }

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("tileRenderer")?.let { fromTileRenderer(it) }
                    if (node.has("onSelectCommand") && node.has("metadata")) {
                        fromTileRenderer(node)
                    }
                    // Fallback: videoId on this node (watchEndpoint), accept even without title.
                    val videoId = node.optString("videoId").trim()
                        .ifBlank {
                            node.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty().trim()
                        }
                        .ifBlank {
                            node.optJSONObject("onSelectCommand")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty().trim()
                        }
                        .ifBlank {
                            node.optJSONObject("onTap")
                                ?.optJSONObject("innertubeCommand")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty().trim()
                        }
                    if (videoId.matches(Regex("^[\\w-]{11}$"))) {
                        val title = extractText(node.opt("title"))
                            .ifBlank { extractText(node.opt("headline")) }
                            .ifBlank { extractText(node.opt("primaryText")) }
                            .ifBlank {
                                extractText(node.optJSONObject("metadata")?.opt("title"))
                            }
                            .ifBlank {
                                extractText(
                                    node.optJSONObject("metadata")
                                        ?.optJSONObject("tileMetadataRenderer")
                                        ?.opt("title"),
                                )
                            }
                            .ifBlank { videoId }
                        val uploader = extractText(node.opt("shortBylineText"))
                            .ifBlank { extractText(node.opt("longBylineText")) }
                            .ifBlank { extractText(node.opt("ownerText")) }
                            .ifBlank { extractText(node.opt("subtitle")) }
                        val thumb = firstThumbnailUrl(node)
                        val durationSec = tileDurationSeconds(node).let { d ->
                            if (d != 0L) d else parseDurationSeconds(extractText(node.opt("lengthText")))
                        }
                        val published = extractText(node.opt("publishedTimeText"))
                        val channelId = tileChannelId(node)
                            .ifBlank {
                                channelIdFromAttributedText(node.opt("shortBylineText")).orEmpty()
                            }
                            .ifBlank {
                                channelIdFromAttributedText(node.opt("longBylineText")).orEmpty()
                            }
                            .ifBlank {
                                channelIdFromAttributedText(node.opt("ownerText")).orEmpty()
                            }
                        putItem(
                            YoutubeVideoItem(
                                id = videoId,
                                title = title,
                                uploader = uploader,
                                thumbnailUrl = thumb.ifBlank {
                                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                },
                                durationSec = durationSec,
                                views = 0L,
                                uploadedDate = published,
                                uploadedEpochMs = relativeDateToEpochMs(published),
                                channelId = channelId,
                            ),
                        )
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

    private fun preferText(a: String, b: String, bad: String = ""): String {
        fun score(s: String): Int {
            if (s.isBlank()) return 0
            if (bad.isNotBlank() && s.equals(bad, ignoreCase = true)) return 1
            return 2
        }
        return if (score(b) > score(a)) b else a.ifBlank { b }
    }

    private fun tileChannelId(tile: JSONObject): String {
        val fromBrowse = tile.optJSONObject("onSelectCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        YoutubeDefaults.channelIdFromUrl(fromBrowse)?.let { return it }

        channelIdFromAttributedText(tile.opt("shortBylineText"))?.let { return it }
        channelIdFromAttributedText(tile.opt("longBylineText"))?.let { return it }
        channelIdFromAttributedText(tile.opt("ownerText"))?.let { return it }
        channelIdFromAttributedText(tile.optJSONObject("metadata")?.opt("subtitle"))?.let { return it }
        channelIdFromAttributedText(
            tile.optJSONObject("metadata")
                ?.optJSONObject("tileMetadataRenderer")
                ?.opt("subtitle"),
        )?.let { return it }

        val menuItems = tile.optJSONObject("onLongPressCommand")
            ?.optJSONObject("showMenuCommand")
            ?.optJSONObject("menu")
            ?.optJSONObject("menuRenderer")
            ?.optJSONArray("items")
        if (menuItems != null) {
            for (i in 0 until menuItems.length()) {
                val nav = menuItems.optJSONObject(i)
                    ?.optJSONObject("menuNavigationItemRenderer")
                    ?: continue
                val label = extractText(nav.opt("text")).lowercase()
                val browseId = nav.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                if (browseId.startsWith("UC") || label.contains("channel")) {
                    YoutubeDefaults.channelIdFromUrl(browseId)?.let { return it }
                }
            }
        }
        val endpointBrowse = tile.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        YoutubeDefaults.channelIdFromUrl(endpointBrowse)?.let { return it }

        return findChannelIdDeep(tile).orEmpty()
    }

    /** Channel id from byline runs (`navigationEndpoint.browseEndpoint.browseId`). */
    private fun channelIdFromAttributedText(value: Any?): String? {
        val obj = value as? JSONObject ?: return null
        val runs = obj.optJSONArray("runs") ?: return null
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val browseId = run.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty()
            YoutubeDefaults.channelIdFromUrl(browseId)?.let { return it }
        }
        return null
    }

    /** Last-resort scan for a `UC…` browseId / channelId in the tile JSON. */
    private fun findChannelIdDeep(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 14) return null
        when (node) {
            is String -> return YoutubeDefaults.channelIdFromUrl(node)
            is JSONObject -> {
                val direct = node.optString("channelId").orEmpty()
                    .ifBlank { node.optString("browseId").orEmpty() }
                YoutubeDefaults.channelIdFromUrl(direct)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findChannelIdDeep(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findChannelIdDeep(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    /** TV tile duration; `-1` means an explicit LIVE badge. */
    private fun tileDurationSeconds(tile: JSONObject): Long {
        val overlays = tile.optJSONObject("header")
            ?.optJSONObject("tileHeaderRenderer")
            ?.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val time = overlays.optJSONObject(i)
                    ?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                    ?: continue
                val style = time.optString("style")
                if (style.contains("LIVE", ignoreCase = true)) return -1L
                val label = extractText(time.opt("text"))
                if (label.contains("LIVE", ignoreCase = true) &&
                    !label.contains(':')
                ) {
                    return -1L
                }
                val sec = parseDurationSeconds(label)
                if (sec > 0L) return sec
            }
        }
        return parseDurationSeconds(extractText(tile.opt("lengthText")))
    }

    private fun lineItemTexts(lineObj: JSONObject?): List<String> {
        if (lineObj == null) return emptyList()
        val items = lineObj.optJSONObject("lineRenderer")?.optJSONArray("items")
            ?: return extractText(lineObj).takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        val out = ArrayList<String>(items.length())
        for (i in 0 until items.length()) {
            val renderer = items.optJSONObject(i)?.optJSONObject("lineItemRenderer") ?: continue
            val textNode = renderer.opt("text")
            val plain = extractText(textNode)
            val access = (textNode as? JSONObject)
                ?.optJSONObject("accessibility")
                ?.optJSONObject("accessibilityData")
                ?.optString("label").orEmpty().trim()
            val value = plain.ifBlank { access }
                .replace('\u00a0', ' ')
                .trim()
            if (value.isBlank() || value == "•" || value == "·" || value == "|") continue
            out.add(value)
        }
        return out
    }

    private fun looksLikeRelativeDate(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("ago") ||
            t.contains("streamed") ||
            t.contains("premier") ||
            t == "today" ||
            t == "yesterday" ||
            Regex("""\b\d+\s*(second|minute|hour|day|week|month|year)s?\b""").containsMatchIn(t)
    }

    private fun looksLikeViewCount(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("view") || Regex("""^\d[\d.,]*\s*[kmb]?$""").containsMatchIn(t)
    }

    private fun parseViewCountLabel(text: String): Long {
        val cleaned = text.lowercase()
            .replace("views", "")
            .replace("view", "")
            .replace(",", "")
            .trim()
        val m = Regex("""^([\d.]+)\s*([kmb])?$""").find(cleaned) ?: return 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2]) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    private fun relativeDateToEpochMs(label: String): Long {
        val t = label.lowercase().trim()
        if (t.isBlank()) return 0L
        val now = System.currentTimeMillis()
        if (t == "today") return now
        if (t == "yesterday") return now - 86_400_000L
        val m = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?""").find(t)
            ?: return 0L
        val n = m.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (m.groupValues[2]) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            "year" -> 31_536_000_000L
            else -> return 0L
        }
        return (now - n * unitMs).coerceAtLeast(0L)
    }

    private fun firstThumbnailUrl(node: JSONObject): String {
        val candidates = listOf(
            node.optJSONObject("thumbnail"),
            node.optJSONObject("thumbnails"),
            node.optJSONObject("header")
                ?.optJSONObject("tileHeaderRenderer")
                ?.optJSONObject("thumbnail"),
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
        private const val ANDROID_VR_CLIENT_VERSION = "1.65.10"
        private const val WEB_EMBEDDED_CLIENT_VERSION = "1.20241201.00.00"
        private const val WEB_EMBEDDED_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        /** Public TVHTML5 InnerTube key (same family as SmartTube / Lincoln). */
        private const val TV_API_KEY = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8"
        private const val TV_CLIENT_VERSION = "7.20250209.19.00"
        private const val TV_BROWSE_UA =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 5.0) AppleWebKit/538.1 " +
                "(KHTML, like Gecko) Version/5.0 NativeTVAds Safari/538.1,gzip(gfe)"
        /** iOS client — often returns plain adaptive URLs + strong 1080p/60 ladders. */
        private const val IOS_API_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
        private const val IOS_CLIENT_VERSION = "21.03.2"
        private const val IOS_DEVICE_MODEL = "iPhone16,2"
        private const val IOS_OS_VERSION = "18.7.2.22H124"
        private const val IOS_UA_VERSION = "18_7_2"

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

    }
}

class YoutubeApiException(message: String) : Exception(message)
