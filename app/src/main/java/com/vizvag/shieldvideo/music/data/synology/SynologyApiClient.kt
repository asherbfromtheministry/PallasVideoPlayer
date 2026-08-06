package com.vizvag.shieldvideo.music.data.synology

import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.music.data.settings.NasSettings
import com.vizvag.shieldvideo.music.data.settings.MusicSettingsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SynologyException(message: String, val code: Int? = null) : Exception(message)

class SynologyApiClient constructor(
    private val musicSettings: MusicSettingsBridge,
    private val okHttpClient: OkHttpClient,
) {
    private val authMutex = Mutex()
    private var sessionId: String? = null
    private var api: SynologyApi? = null
    private var currentBaseUrl: String? = null
    private var authPath: String = "auth.cgi"
    private var authVersion: Int = 3

    private suspend fun api(): SynologyApi {
        val settings = musicSettings.currentSettings()
        if (api == null || currentBaseUrl != settings.baseUrl) {
            currentBaseUrl = settings.baseUrl
            discoverApiInfo(settings)
            api = Retrofit.Builder()
                .baseUrl("${settings.baseUrl}/")
                .client(buildClient(settings))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SynologyApi::class.java)
        }
        return api!!
    }

    private suspend fun discoverApiInfo(settings: NasSettings) {
        runCatching {
            val retrofit = Retrofit.Builder()
                .baseUrl("${settings.baseUrl}/")
                .client(buildClient(settings))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            val infoApi = retrofit.create(SynologyApi::class.java)
            val response = infoApi.queryApiInfo(query = "SYNO.API.Auth")
            if (response.success && response.data != null) {
                val auth = response.data!!.getAsJsonObject("SYNO.API.Auth")
                authPath = auth?.get("path")?.asString ?: "auth.cgi"
                authVersion = auth?.get("maxVersion")?.asInt ?: 3
            }
        }
    }

    private fun buildClient(settings: NasSettings): OkHttpClient {
        val builder = okHttpClient.newBuilder()
        if (settings.useHttps && settings.trustSelfSigned) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun downloadUrl(settings: NasSettings, sid: String, path: String, mode: String): okhttp3.HttpUrl {
        val base = settings.baseUrl.trimEnd('/')
        val parsed = base.toHttpUrlOrNull()
            ?: throw SynologyException("Invalid NAS base URL: $base")
        return parsed.newBuilder()
            .addPathSegment("webapi")
            .addPathSegment("entry.cgi")
            .addQueryParameter("api", "SYNO.FileStation.Download")
            .addQueryParameter("version", "2")
            .addQueryParameter("method", "download")
            .addQueryParameter("path", jsonArray(listOf(path)))
            .addQueryParameter("mode", jsonQuote(mode))
            .addQueryParameter("_sid", sid)
            .build()
    }

    suspend fun ensureSession(): String = authMutex.withLock {
        sessionId?.let { return it }
        loginInternal()
    }

    suspend fun login(settings: NasSettings? = null): String = authMutex.withLock {
        settings?.let { musicSettings.save(it) }
        sessionId = null
        api = null
        currentBaseUrl = null
        loginInternal()
    }

    private suspend fun loginInternal(): String {
        val settings = musicSettings.currentSettings()
        if (!settings.isConfigured) {
            throw SynologyException("NAS credentials not configured")
        }
        if (!settings.useHttps && settings.port == NasSettings.HTTPS_PORT) {
            throw SynologyException(
                "Port 5001 requires HTTPS. Turn on \"Use HTTPS\" or change port to 5000.",
            )
        }
        if (settings.useHttps && settings.port == NasSettings.HTTP_PORT) {
            throw SynologyException(
                "Port 5000 is HTTP only. Turn off \"Use HTTPS\" or change port to 5001.",
            )
        }
        try {
            val response = api().login(
                path = authPath,
                version = authVersion.coerceAtMost(7),
                account = settings.username,
                password = settings.password,
            )
            if (!response.success || response.data?.sid.isNullOrBlank()) {
                val apiCode = response.error?.code
                throw SynologyException(
                    synologyErrorMessage(apiCode),
                    apiCode,
                )
            }
            sessionId = response.data!!.sid
        } catch (e: HttpException) {
            throw SynologyException(httpErrorMessage(e, settings), e.code())
        }
        return sessionId!!
    }

    suspend fun logout() = authMutex.withLock {
        sessionId?.let { sid ->
            runCatching { api().logout(path = authPath, sid = sid) }
        }
        sessionId = null
    }

    suspend fun testConnection(settings: NasSettings): Result<String> = runCatching {
        val normalized = settings.copy(
            host = settings.normalizedHost,
            port = NasSettings.portForProtocol(settings.useHttps, settings.port),
        )
        musicSettings.save(normalized)
        sessionId = null
        api = null
        currentBaseUrl = null
        val sid = login()
        val shares = listShares()
        "Connected via ${normalized.baseUrl}. ${shares.size} shared folder(s) visible."
    }

    private suspend fun <T> withSession(block: suspend (String) -> T): T {
        val sid = ensureSession()
        return try {
            block(sid)
        } catch (e: SynologyException) {
            if (e.code == 119) {
                authMutex.withLock {
                    sessionId = null
                    loginInternal()
                }
                block(ensureSession())
            } else throw e
        }
    }

    suspend fun listShares(): List<FileEntry> = withSession { sid ->
        val response = api().fileStationList(
            mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list_share",
                "_sid" to sid,
                "additional" to """["real_path","time"]""",
            ),
        )
        ensureSuccess(response)
        response.data?.shares ?: emptyList()
    }

    suspend fun listFolder(folderPath: String, offset: Int = 0, limit: Int = 0): FileListData =
        withSession { sid ->
            val response = api().fileStationList(
                mapOf(
                    "api" to "SYNO.FileStation.List",
                    "version" to "2",
                    "method" to "list",
                    "_sid" to sid,
                    "folder_path" to jsonQuote(folderPath),
                    "offset" to offset.toString(),
                    "limit" to limit.toString(),
                    "additional" to """["real_path","size","time","type"]""",
                ),
            )
            ensureSuccess(response)
            response.data ?: FileListData()
        }

    /**
     * Pull songs from Synology Audio Station's media index (already tagged on the NAS).
     * Returns null when Audio Station is unavailable / empty so callers can fall back.
     */
    suspend fun listAudioStationSongs(
        musicRoots: List<String>,
        onProgress: suspend (fetched: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AudioStationSong>? = withSession { sid ->
        val roots = musicRoots
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotBlank() && it != "/" }
            .distinctBy { it.lowercase() }
        val all = mutableListOf<AudioStationSong>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val response = api().audioStation(
                path = "AudioStation/song.cgi",
                params = mapOf(
                    "api" to "SYNO.AudioStation.Song",
                    "version" to "3",
                    "method" to "list",
                    "library" to "all",
                    "offset" to offset.toString(),
                    "limit" to AUDIO_STATION_PAGE.toString(),
                    "additional" to "song_tag,song_audio,song_rating",
                    "_sid" to sid,
                ),
            )
            if (!response.success) {
                val code = response.error?.code
                // 103/104/105 → not usable; treat as unavailable for fallback
                if (code == 103 || code == 104 || code == 105 || all.isEmpty()) return@withSession null
                break
            }
            val data = response.data ?: break
            total = data.total.coerceAtLeast(0)
            val page = data.songs
            if (page.isEmpty()) break
            for (song in page) {
                val path = song.path.replace('\\', '/')
                if (path.isBlank()) continue
                if (roots.isNotEmpty() && roots.none { root ->
                        path.equals(root, ignoreCase = true) ||
                            path.startsWith("$root/", ignoreCase = true)
                    }
                ) {
                    continue
                }
                all += song
            }
            offset += page.size
            onProgress(offset.coerceAtMost(total.coerceAtLeast(offset)), total.coerceAtLeast(offset))
            if (page.size < AUDIO_STATION_PAGE) break
        }
        all
    }

    /**
     * Pull videos from Synology Video Station's media index (movies, TV episodes,
     * home videos, TV recordings). Returns null when Video Station is unavailable
     * so callers can fall back to a File Station / SMB walk.
     */
    suspend fun listVideoStationVideos(
        videoRoots: List<String>,
        onProgress: suspend (fetched: Int, total: Int) -> Unit = { _, _ -> },
    ): List<VideoStationIndexedFile>? = withSession { sid ->
        val roots = videoRoots
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotBlank() && it != "/" }
            .distinctBy { it.lowercase() }
        val all = mutableListOf<VideoStationIndexedFile>()
        val seenPaths = mutableSetOf<String>()
        var fetchedAcross = 0
        var estimatedTotal = 0

        for (endpoint in VIDEO_STATION_ENDPOINTS) {
            var offset = 0
            var total = Int.MAX_VALUE
            while (offset < total) {
                val response = try {
                    api().videoStation(
                        path = endpoint.path,
                        params = mapOf(
                            "api" to endpoint.api,
                            "version" to endpoint.version.toString(),
                            "method" to "list",
                            "offset" to offset.toString(),
                            "limit" to VIDEO_STATION_PAGE.toString(),
                            "additional" to """["file","tvshow"]""",
                            "sort_by" to "title",
                            "sort_direction" to "asc",
                            "_sid" to sid,
                        ),
                    )
                } catch (_: Exception) {
                    return@withSession if (all.isEmpty()) null else all
                }
                if (!response.success) {
                    val code = response.error?.code
                    // Package missing / no privilege / API error → unavailable for fallback
                    if (code == 103 || code == 104 || code == 105 ||
                        (all.isEmpty() && offset == 0 && endpoint == VIDEO_STATION_ENDPOINTS.first())
                    ) {
                        return@withSession null
                    }
                    break
                }
                val data = response.data ?: break
                total = data.total.coerceAtLeast(0)
                if (offset == 0) estimatedTotal += total
                val page = data.items()
                if (page.isEmpty()) break
                for (item in page) {
                    val displayTitle = item.displayTitle()
                    for (file in item.additional?.file.orEmpty()) {
                        val path = file.resolvedPath() ?: continue
                        if (roots.isNotEmpty() && roots.none { root ->
                                path.equals(root, ignoreCase = true) ||
                                    path.startsWith("$root/", ignoreCase = true)
                            }
                        ) {
                            continue
                        }
                        val key = path.lowercase()
                        if (!seenPaths.add(key)) continue
                        all += VideoStationIndexedFile(
                            path = path,
                            title = displayTitle,
                            size = file.resolvedSize(),
                            filename = file.filename,
                        )
                    }
                }
                offset += page.size
                fetchedAcross += page.size
                onProgress(
                    all.size.coerceAtLeast(fetchedAcross),
                    estimatedTotal.coerceAtLeast(all.size),
                )
                if (page.size < VIDEO_STATION_PAGE) break
            }
        }
        all
    }

    suspend fun searchAudioFiles(
        folderPath: String,
        extensions: List<String> = AUDIO_EXTENSIONS,
        onProgress: suspend (Int, Boolean) -> Unit = { _, _ -> },
    ): List<FileEntry> = withSession { sid ->
        val extPattern = extensions.joinToString(",")
        val startResponse = api().fileStationSearch(
            mapOf(
                "api" to "SYNO.FileStation.Search",
                "version" to "2",
                "method" to "start",
                "_sid" to sid,
                "folder_path" to jsonArray(listOf(folderPath)),
                "pattern" to "",
                "extension" to jsonQuote(extPattern),
                "filetype" to "file",
                "recursive" to "true",
            ),
        )
        ensureSuccess(startResponse)
        val taskId = SynologyApiParser.parseSearchStart(startResponse)
            ?: throw SynologyException("Search start failed")
        val results = mutableListOf<FileEntry>()
        try {
            var finished = false
            while (!finished) {
                val listResponse = api().fileStationSearch(
                    mapOf(
                        "api" to "SYNO.FileStation.Search",
                        "version" to "2",
                        "method" to "list",
                        "_sid" to sid,
                        "taskid" to jsonQuote(taskId.taskid),
                        "limit" to "-1",
                        "additional" to """["real_path","size","time","type"]""",
                    ),
                )
                ensureSuccess(listResponse)
                val data = SynologyApiParser.parseSearchList(listResponse)
                    ?: throw SynologyException("Search list failed")
                results.clear()
                results.addAll(data.files)
                finished = data.finished
                onProgress(data.total, finished)
                if (!finished) kotlinx.coroutines.delay(300)
            }
        } finally {
            runCatching {
                api().fileStationSearch(
                    mapOf(
                        "api" to "SYNO.FileStation.Search",
                        "version" to "2",
                        "method" to "clean",
                        "_sid" to sid,
                        "taskid" to jsonQuote(taskId.taskid),
                    ),
                )
            }
        }
        results.filterNot { NasPaths.pathContainsIgnoredDirectory(it.path) }
    }

    fun buildDownloadUrl(path: String, mode: String = "open"): suspend () -> String = suspend {
        val settings = musicSettings.currentSettings()
        val sid = ensureSession()
        downloadUrl(settings, sid, path, mode).toString()
    }

    suspend fun downloadBytes(path: String, maxBytes: Long = Long.MAX_VALUE): ByteArray =
        withContext(Dispatchers.IO) {
            withSession { sid ->
                val settings = musicSettings.currentSettings()
                val url = downloadUrl(settings, sid, path, mode = "download")
                val request = Request.Builder().url(url).build()
                val client = buildClient(settings)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw SynologyException("Download failed: ${response.code}")
                    }
                    val body = response.body ?: throw SynologyException("Empty download body")
                    if (maxBytes == Long.MAX_VALUE) {
                        body.bytes()
                    } else {
                        body.byteStream().use { it.readNBytes(maxBytes.toInt()) }
                    }
                }
            }
        }

    /** Stream a NAS file into [out] without buffering the whole file in memory. */
    suspend fun downloadTo(path: String, out: java.io.OutputStream) =
        withContext(Dispatchers.IO) {
            withSession { sid ->
                val settings = musicSettings.currentSettings()
                val url = downloadUrl(settings, sid, path, mode = "download")
                val request = Request.Builder().url(url).build()
                val client = buildClient(settings)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw SynologyException("Download failed: ${response.code}")
                    }
                    val body = response.body ?: throw SynologyException("Empty download body")
                    body.byteStream().use { input -> input.copyTo(out) }
                }
            }
        }

    suspend fun downloadText(path: String): String =
        String(downloadBytes(path))

    suspend fun fileExists(path: String): Boolean = runCatching {
        listFolder(path.substringBeforeLast("/", "/"))
            .files.any { it.path == path }
    }.getOrDefault(false)

    private fun <T> ensureSuccess(response: SynologyResponse<T>) {
        if (!response.success) {
            throw SynologyException(synologyErrorMessage(response.error?.code), response.error?.code)
        }
    }

    private fun synologyErrorMessage(code: Int?): String = when (code) {
        400 -> "Invalid username or password"
        401, 402 -> "Account disabled or permission denied"
        403 -> "2-step verification is enabled; not supported by this app yet"
        404 -> "Account not found"
        119 -> "Session expired"
        else -> "Login failed (error ${code ?: "unknown"})"
    }

    private fun httpErrorMessage(e: HttpException, settings: NasSettings): String {
        val body = e.response()?.errorBody()?.string().orEmpty()
        return when {
            e.code() == 400 && body.contains("plain HTTP request was sent to HTTPS port") ->
                "Port ${settings.port} is HTTPS-only. Enable \"Use HTTPS\" in Settings."
            e.code() == 400 && body.contains("HTTPS request was sent to HTTP port") ->
                "Port ${settings.port} is HTTP-only. Disable \"Use HTTPS\" or use port 5000."
            e.code() == 401 || e.code() == 403 ->
                "Connection refused. Check host ${settings.normalizedHost} and that the phone is on the same Wi‑Fi as the NAS."
            else -> "HTTP ${e.code()}: ${body.take(120).ifBlank { e.message() }}"
        }
    }

    private fun jsonQuote(value: String): String = "\"$value\""

    private fun jsonArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { "\"$it\"" }

    companion object {
        val AUDIO_EXTENSIONS = listOf("mp3", "flac", "m4a", "ogg", "aac", "wav", "wma")
        private const val AUDIO_STATION_PAGE = 1000
        private const val VIDEO_STATION_PAGE = 500

        private data class VideoStationEndpoint(
            val api: String,
            val path: String,
            val version: Int,
        )

        private val VIDEO_STATION_ENDPOINTS = listOf(
            VideoStationEndpoint("SYNO.VideoStation.Movie", "VideoStation/movie.cgi", 4),
            VideoStationEndpoint("SYNO.VideoStation.TVShowEpisode", "VideoStation/tvshow_episode.cgi", 4),
            VideoStationEndpoint("SYNO.VideoStation.HomeVideo", "VideoStation/homevideo.cgi", 4),
            VideoStationEndpoint("SYNO.VideoStation.TVRecording", "VideoStation/tvrecord.cgi", 3),
        )
    }
}

/** Flattened Video Station file entry used by the local video search index. */
data class VideoStationIndexedFile(
    val path: String,
    val title: String? = null,
    val size: Long = 0L,
    val filename: String? = null,
)

private fun VideoStationItem.displayTitle(): String? {
    val show = tvshowName
        ?: additional?.tvshow?.firstOrNull()?.title
    val episodeTitle = title?.takeIf { it.isNotBlank() }
    return when {
        !show.isNullOrBlank() && season != null && episode != null && !episodeTitle.isNullOrBlank() ->
            "$show S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · $episodeTitle"
        !show.isNullOrBlank() && !episodeTitle.isNullOrBlank() -> "$show · $episodeTitle"
        !episodeTitle.isNullOrBlank() -> episodeTitle
        !show.isNullOrBlank() -> show
        else -> null
    }
}
