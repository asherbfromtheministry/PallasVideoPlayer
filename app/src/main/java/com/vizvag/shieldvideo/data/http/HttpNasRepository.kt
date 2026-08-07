package com.vizvag.shieldvideo.data.http

import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.smb.SmbEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Synology DSM File Station over HTTP (default port 5000).
 * Browse via list API; VLC plays the File Station download URL with a session id.
 */
class HttpNasRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sessionMutex = Mutex()
    private var cachedSid: String? = null
    private var cachedKey: String? = null

    private val videoExtensions = NasPaths.videoExtensions

    suspend fun testConnection(settings: AppSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val share = settings.defaultShare.ifBlank { settings.shares.first() }
            list(settings, share, "").getOrThrow()
            Unit
        }
    }

    suspend fun list(
        settings: AppSettings,
        shareName: String,
        path: String,
        allowedExtensions: Set<String> = videoExtensions
    ): Result<List<SmbEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val folderPath = absoluteFolder(shareName, path)
            val body = apiGet(settings, mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list",
                "folder_path" to folderPath,
                "additional" to "[\"size\"]"
            ))
            val files = body.optJSONObject("data")?.optJSONArray("files")
                ?: throw IllegalStateException("File Station list failed")
            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    val name = file.optString("name")
                    if (name.isBlank() || name == "." || name == "..") continue
                    val isDir = file.optBoolean("isdir", false)
                    val absPath = file.optString("path").trim('/')
                    // DSM share casing can differ from the configured name (`Download` vs `download`).
                    val relative = stripSharePrefix(absPath, shareName)
                    val size = file.optJSONObject("additional")?.optLong("size") ?: 0L
                    val entry = SmbEntry(
                        name = name,
                        path = relative,
                        isDirectory = isDir,
                        size = size
                    )
                    if (entry.isDirectory ||
                        entry.name.substringAfterLast('.', "").lowercase() in allowedExtensions
                    ) {
                        add(entry)
                    }
                }
            }.sortedWith(
                compareByDescending<SmbEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    suspend fun listShares(settings: AppSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = apiGet(settings, mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list_share",
                "additional" to "[\"perm\"]"
            ))
            val shares = body.optJSONObject("data")?.optJSONArray("shares")
                ?: throw IllegalStateException("File Station list_share failed")
            buildList {
                for (i in 0 until shares.length()) {
                    val share = shares.optJSONObject(i) ?: continue
                    val name = share.optString("name").trim().trim('/')
                    if (name.isNotBlank()) add(name)
                }
            }.sortedBy { it.lowercase() }
        }
    }

    suspend fun playbackUrl(
        settings: AppSettings,
        shareName: String,
        relativePath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sid = ensureSid(settings)
            val path = absoluteFolder(shareName, relativePath)
            val encodedPath = URLEncoder.encode(path, Charsets.UTF_8.name())
            val encodedSid = URLEncoder.encode(sid, Charsets.UTF_8.name())
            val base = baseUrl(settings)
            "$base/webapi/entry.cgi?api=SYNO.FileStation.Download&version=2&method=download&path=$encodedPath&_sid=$encodedSid"
        }
    }

    /** Delete a file or folder via File Station (recursive for directories). */
    suspend fun delete(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = absoluteFolder(shareName, relativePath)
            if (path.trim('/') == shareName.trim('/')) {
                throw IllegalArgumentException("Refusing to delete share root")
            }
            val start = apiGet(
                settings,
                mapOf(
                    "api" to "SYNO.FileStation.Delete",
                    "version" to "2",
                    "method" to "start",
                    "path" to "[\"$path\"]",
                    "recursive" to "true",
                )
            )
            val taskId = start.optJSONObject("data")?.optString("taskid").orEmpty()
            if (taskId.isBlank()) return@runCatching Unit
            repeat(40) {
                val status = apiGet(
                    settings,
                    mapOf(
                        "api" to "SYNO.FileStation.Delete",
                        "version" to "2",
                        "method" to "status",
                        "taskid" to taskId,
                    )
                )
                val data = status.optJSONObject("data")
                if (data?.optBoolean("finished", false) == true) {
                    if (data.optBoolean("success", true)) return@runCatching Unit
                    throw IllegalStateException("File Station delete failed")
                }
                Thread.sleep(250)
            }
            Unit
        }
    }

    /**
     * Extract archive into the same folder on the NAS (File Station Extract API).
     * Does not delete the archive. Supports rar/zip/7z/etc. that DSM supports.
     *
     * DSM's `progress` resets per RAR volume; we remap it to overall volumes done/total.
     */
    suspend fun extractArchive(
        settings: AppSettings,
        shareName: String,
        relativeArchivePath: String,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveAbs = absoluteFolder(shareName, relativeArchivePath)
            val destAbs = archiveAbs.substringBeforeLast('/', "/${shareName.trim('/')}")
                .ifBlank { "/${shareName.trim('/')}" }
            val archiveName = relativeArchivePath.substringAfterLast('/').ifBlank {
                archiveAbs.substringAfterLast('/')
            }
            onProgress(0.01f, "Counting volumes…")
            val volumeTotal = runCatching {
                NasPaths.countRarVolumes(listFileNamesInFolder(settings, destAbs), archiveName)
            }.getOrDefault(1)
            onProgress(
                0.02f,
                if (volumeTotal > 1) "Starting extract ($volumeTotal volumes)…" else "Starting extract…",
            )
            val start = apiGet(
                settings,
                mapOf(
                    "api" to "SYNO.FileStation.Extract",
                    "version" to "2",
                    "method" to "start",
                    "file_path" to archiveAbs,
                    "dest_folder_path" to destAbs,
                    "overwrite" to "false",
                    "keep_dir" to "true",
                    "create_subfolder" to "false",
                ),
            )
            val taskId = start.optJSONObject("data")?.optString("taskid").orEmpty()
                .ifBlank { start.optString("taskid") }
            if (taskId.isBlank()) {
                throw IllegalStateException("Extract start returned no task id")
            }
            var finished = false
            var lastRawProgress = 0f
            var volumesDone = 0
            var overall = 0f
            while (!finished) {
                kotlinx.coroutines.delay(500)
                val status = apiGet(
                    settings,
                    mapOf(
                        "api" to "SYNO.FileStation.Extract",
                        "version" to "2",
                        "method" to "status",
                        "taskid" to taskId,
                    ),
                )
                val data = status.optJSONObject("data") ?: status
                finished = data.optBoolean("finished", false)
                val raw = data.optDouble("progress", lastRawProgress.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                // DSM resets 0→1 for each volume; a drop means the next volume started.
                if (volumeTotal > 1 && raw + 0.15f < lastRawProgress && volumesDone < volumeTotal - 1) {
                    volumesDone += 1
                }
                lastRawProgress = raw
                if (finished) {
                    overall = 1f
                    onProgress(1f, "Extract complete · $volumeTotal/$volumeTotal volumes")
                } else if (volumeTotal > 1) {
                    val next = ((volumesDone + raw) / volumeTotal.toFloat()).coerceIn(0f, 0.995f)
                    overall = maxOf(overall, next)
                    val pct = (overall * 100).toInt()
                    onProgress(
                        overall.coerceAtLeast(0.02f),
                        "Extracting… $volumesDone/$volumeTotal complete ($pct%)",
                    )
                } else {
                    overall = maxOf(overall, raw)
                    val pct = (overall * 100).toInt()
                    onProgress(
                        if (overall > 0f) overall else 0.05f,
                        "Extracting… $pct%",
                    )
                }
            }
            Unit
        }
    }

    private suspend fun listFileNamesInFolder(
        settings: AppSettings,
        folderAbs: String,
    ): List<String> {
        val body = apiGet(
            settings,
            mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list",
                "folder_path" to folderAbs,
                "limit" to "0",
            ),
        )
        val files = body.optJSONObject("data")?.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val file = files.optJSONObject(i) ?: continue
                if (file.optBoolean("isdir", false)) continue
                val name = file.optString("name")
                if (name.isNotBlank()) add(name)
            }
        }
    }

    fun clearSession() {
        cachedSid = null
        cachedKey = null
    }

    private suspend fun apiGet(settings: AppSettings, params: Map<String, String>): JSONObject {
        suspend fun once(forceLogin: Boolean): JSONObject {
            val sid = ensureSid(settings, forceLogin)
            val url = baseUrl(settings).toHttpUrl().newBuilder()
                .addPathSegments("webapi/entry.cgi")
                .apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("_sid", sid)
                }
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) {
                    val code = json.optJSONObject("error")?.optInt("code")
                    throw IllegalStateException("File Station error ${code ?: "unknown"}")
                }
                return json
            }
        }

        return try {
            once(forceLogin = false)
        } catch (error: Exception) {
            // Session may have expired — retry once with a fresh login.
            clearSession()
            once(forceLogin = true)
        }
    }

    private suspend fun ensureSid(settings: AppSettings, force: Boolean = false): String =
        sessionMutex.withLock {
            val key = "${settings.host}|${settings.port}|${settings.username}|${settings.password}"
            if (!force && cachedSid != null && cachedKey == key) {
                return@withLock cachedSid!!
            }
            val url = baseUrl(settings).toHttpUrl().newBuilder()
                .addPathSegments("webapi/auth.cgi")
                .addQueryParameter("api", "SYNO.API.Auth")
                .addQueryParameter("version", "3")
                .addQueryParameter("method", "login")
                .addQueryParameter("account", settings.username)
                .addQueryParameter("passwd", settings.password)
                .addQueryParameter("session", "FileStation")
                .addQueryParameter("format", "sid")
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Auth HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) {
                    val code = json.optJSONObject("error")?.optInt("code")
                    throw IllegalStateException(
                        when (code) {
                            400 -> "Wrong NAS username or password"
                            401 -> "Account disabled"
                            402 -> "Permission denied"
                            403 -> "2FA required — use an app password or disable OTP for this account"
                            else -> "DSM login failed (${code ?: "unknown"})"
                        }
                    )
                }
                val sid = json.optJSONObject("data")?.optString("sid").orEmpty()
                if (sid.isBlank()) throw IllegalStateException("DSM login returned empty session")
                cachedSid = sid
                cachedKey = key
                sid
            }
        }

    private fun baseUrl(settings: AppSettings): String {
        val port = if (settings.port > 0) settings.port else 5000
        return "http://${settings.host.trim()}:$port"
    }

    private fun absoluteFolder(shareName: String, path: String): String =
        absoluteSharePath(shareName, path)

    companion object {
        /**
         * Build a File Station absolute path `/share/relative…`.
         *
         * [path] is share-relative (from browse). Do **not** strip a differently-cased
         * first segment — that is a folder (e.g. `Docs/video.mkv` under share `docs`),
         * not a duplicated share prefix. Stripping it caused Download 408 (no such file).
         */
        fun absoluteSharePath(shareName: String, path: String): String {
            val share = shareName.trim('/')
            var relative = path.trim('/').replace('\\', '/')
            when {
                relative == share -> relative = ""
                relative.startsWith("$share/") ->
                    relative = relative.substring(share.length + 1).trim('/')
            }
            return if (relative.isBlank()) "/$share" else "/$share/$relative"
        }

        /** Strip a DSM absolute share prefix from a path.
         *
         * `/Download/Show` + share `download` → `Show` (case-insensitive prefix).
         *
         * Do **not** collapse a lone segment that only matches the share name
         * case-insensitively — that is a folder under the share (e.g. `/docs/Docs`
         * with share `docs`). Collapsing it made browse reopen the share root.
         */
        fun stripSharePrefix(path: String, shareName: String): String {
            var p = path.trim('/').replace('\\', '/')
            val share = shareName.trim('/')
            if (share.isBlank() || p.isBlank()) return p
            if (p.startsWith("$share/", ignoreCase = true)) {
                return p.substring(share.length + 1).trim('/')
            }
            // Exact share-root absolute path only when casing matches the share token
            // we were given (DSM list paths use the real share casing as the first segment).
            if (p.equals(share, ignoreCase = true) && p.contains('/').not()) {
                // Single segment: keep relative folder names like "Docs" under share "docs".
                // Only treat as share root when the segment is an exact case match.
                return if (p == share) "" else p
            }
            return p
        }
    }
}
