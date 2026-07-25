package com.vizvag.shieldvideo.data.nas

import android.net.Uri
import com.vizvag.shieldvideo.data.http.HttpNasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.smb.SmbEntry
import com.vizvag.shieldvideo.data.smb.SmbRepository
import java.net.URLEncoder

class NasRepository(
    private val smbRepository: SmbRepository = SmbRepository(),
    private val httpRepository: HttpNasRepository = HttpNasRepository()
) {
    suspend fun listShares(settings: AppSettings): Result<List<String>> =
        when (settings.connectionMode) {
            ConnectionMode.HTTP -> httpRepository.listShares(settings)
            ConnectionMode.SMB3 -> {
                // Optional DSM share names — never block SMB browsing on HTTP/VPN failures.
                val viaDsm = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                        httpRepository.listShares(
                            settings.copy(port = 5000, connectionMode = ConnectionMode.HTTP)
                        )
                    }
                }.getOrNull()
                if (viaDsm != null && viaDsm.isSuccess && !viaDsm.getOrNull().isNullOrEmpty()) {
                    viaDsm
                } else {
                    smbRepository.listShares(settings)
                }
            }
        }

    suspend fun testConnection(settings: AppSettings): Result<Unit> =
        when (settings.connectionMode) {
            ConnectionMode.SMB3 -> smbRepository.testConnection(settings)
                .fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { Result.failure(Exception(NasConnectionErrors.friendly(it, settings), it)) }
                )
            ConnectionMode.HTTP -> httpRepository.testConnection(settings)
                .fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { Result.failure(Exception(NasConnectionErrors.friendly(it, settings), it)) }
                )
        }

    suspend fun listDirectories(
        settings: AppSettings,
        shareName: String,
        path: String
    ): Result<List<SmbEntry>> =
        list(settings, shareName, path, allowedExtensions = emptySet())
            .map { entries -> entries.filter { it.isDirectory } }

    suspend fun list(
        settings: AppSettings,
        shareName: String,
        path: String,
        allowedExtensions: Set<String>? = null
    ): Result<List<SmbEntry>> {
        val browseExts = allowedExtensions
            ?: (NasPaths.videoExtensions + NasPaths.archiveExtensions)
        val listed = when (settings.connectionMode) {
            ConnectionMode.SMB3 -> smbRepository.list(
                settings,
                shareName,
                path,
                browseExts,
            )
            ConnectionMode.HTTP -> httpRepository.list(
                settings,
                shareName,
                path,
                browseExts,
            )
        }
        return listed.map { entries ->
            val visible = when {
                // Caller asked for a custom set (e.g. images / dirs-only) — leave as-is.
                allowedExtensions != null -> entries
                else -> {
                    val hasVideo = entries.any { !it.isDirectory && NasPaths.isVideoFile(it.name) }
                    entries.filter { entry ->
                        entry.isDirectory ||
                            (
                                NasPaths.isVideoFile(entry.name) &&
                                    !NasPaths.isProgressSidecar(entry.name)
                                ) ||
                            (!hasVideo && NasPaths.isArchiveFile(entry.name))
                    }
                }
            }
            visible.filterNot(::isIgnoredDirectory)
        }
    }

    /**
     * Extract an archive on the NAS via File Station (same folder as the archive).
     * Works in SMB browse mode too — extract always goes through DSM HTTP.
     */
    suspend fun extractArchive(
        settings: AppSettings,
        shareName: String,
        relativeArchivePath: String,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result<Unit> {
        val httpSettings = settings.copy(
            connectionMode = ConnectionMode.HTTP,
            port = if (settings.connectionMode == ConnectionMode.HTTP && settings.port > 0) {
                settings.port
            } else {
                5000
            },
        )
        return httpRepository.extractArchive(
            settings = httpSettings,
            shareName = shareName,
            relativeArchivePath = relativeArchivePath,
            onProgress = onProgress,
        )
    }

    suspend fun playbackUri(
        settings: AppSettings,
        shareName: String,
        relativePath: String
    ): Result<Uri> =
        when (settings.connectionMode) {
            ConnectionMode.SMB3 -> Result.success(buildSmbUri(settings.host, shareName, relativePath))
            ConnectionMode.HTTP -> httpRepository.playbackUrl(settings, shareName, relativePath)
                .map { Uri.parse(it) }
        }

    /** Portable URI for HA / cross-Shield handoff (always path-only SMB — never File Station / never user:pass). */
    fun handoffUri(settings: AppSettings, shareName: String, relativePath: String): Uri =
        buildSmbUri(settings.host, shareName, relativePath)

    /**
     * Recursively walk configured video roots. Returns all files (and directories).
     * Used to build the local search index.
     */
    suspend fun walkVideos(
        settings: AppSettings,
        roots: List<String>,
        maxDepth: Int = 12,
        onProgress: ((Int) -> Unit)? = null
    ): Result<List<Pair<String, SmbEntry>>> = runCatching {
        val hits = mutableListOf<Pair<String, SmbEntry>>()
        val seen = mutableSetOf<String>()
        var counted = 0

        for (root in roots) {
            val (share, folder) = NasPaths.parseFolderPath(root) ?: continue
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(folder to 0)

            while (queue.isNotEmpty()) {
                val (path, depth) = queue.removeFirst()
                val listed = list(settings, share, path).getOrNull() ?: continue
                for (entry in listed) {
                    val key = "$share/${entry.path}".lowercase()
                    if (!seen.add(key)) continue
                    // Index videos + folders only — never .rar archives or progress sidecars.
                    if (!entry.isDirectory && !NasPaths.isVideoFile(entry.name)) continue
                    if (NasPaths.isProgressSidecar(entry.name)) continue
                    hits += root to entry
                    counted += 1
                    if (counted % 25 == 0) onProgress?.invoke(counted)
                    if (entry.isDirectory && depth < maxDepth) {
                        queue.add(entry.path to (depth + 1))
                    }
                }
            }
        }
        onProgress?.invoke(counted)
        hits
    }

    /**
     * Recursively search configured video roots for files/folders matching [query].
     * Returns pairs of (configuredRootPath, entry). Caps results for TV responsiveness.
     */
    suspend fun searchVideos(
        settings: AppSettings,
        roots: List<String>,
        query: String,
        maxResults: Int = 80,
        maxDepth: Int = 8
    ): Result<List<Pair<String, SmbEntry>>> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return Result.success(emptyList())
        val tokens = needle.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.success(emptyList())

        return runCatching {
            val hits = mutableListOf<Pair<String, SmbEntry>>()
            val seen = mutableSetOf<String>()

            fun matches(entry: SmbEntry): Boolean {
                val hay = "${entry.name} ${entry.path}".lowercase()
                return tokens.all { it in hay }
            }

            for (root in roots) {
                if (hits.size >= maxResults) break
                val (share, folder) = NasPaths.parseFolderPath(root) ?: continue
                val queue = ArrayDeque<Pair<String, Int>>()
                queue.add(folder to 0)

                while (queue.isNotEmpty() && hits.size < maxResults) {
                    val (path, depth) = queue.removeFirst()
                    val listed = list(settings, share, path).getOrNull() ?: continue
                    for (entry in listed) {
                        if (hits.size >= maxResults) break
                        val key = "$share/${entry.path}".lowercase()
                        if (!seen.add(key)) continue
                        if (matches(entry)) {
                            hits += root to entry
                        }
                        if (entry.isDirectory && depth < maxDepth) {
                            queue.add(entry.path to (depth + 1))
                        }
                    }
                }
            }
            hits
        }
    }

    /** Coil-friendly model: local [java.io.File] for SMB, HTTP [Uri] string for File Station. */
    suspend fun imageModel(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        cacheFile: java.io.File
    ): Result<Any> =
        when (settings.connectionMode) {
            ConnectionMode.SMB3 -> smbRepository.copyFileTo(settings, shareName, relativePath, cacheFile)
            ConnectionMode.HTTP -> httpRepository.playbackUrl(settings, shareName, relativePath)
                .map { it }
        }

    suspend fun readTextFile(
        settings: AppSettings,
        folderPath: String,
        fileName: String
    ): Result<String> {
        if (settings.connectionMode != ConnectionMode.SMB3) {
            return Result.failure(
                IllegalStateException("Settings backup requires the SMB3 connection mode")
            )
        }
        val (share, folder) = NasPaths.parseFolderPath(folderPath)
            ?: return Result.failure(IllegalArgumentException("Select a NAS backup folder"))
        val path = listOf(folder.trim('/'), fileName.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
        return smbRepository.readText(settings, share, path)
    }

    suspend fun writeTextFile(
        settings: AppSettings,
        folderPath: String,
        fileName: String,
        contents: String
    ): Result<Unit> {
        if (settings.connectionMode != ConnectionMode.SMB3) {
            return Result.failure(
                IllegalStateException("Settings backup requires the SMB3 connection mode")
            )
        }
        val (share, folder) = NasPaths.parseFolderPath(folderPath)
            ?: return Result.failure(IllegalArgumentException("Select a NAS backup folder"))
        val path = listOf(folder.trim('/'), fileName.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
        return smbRepository.writeText(settings, share, path, contents)
    }

    /**
     * Read a text file at [shareName]/[relativePath] over SMB3/445,
     * even when normal browsing uses DSM HTTP.
     */
    suspend fun readTextAt(
        settings: AppSettings,
        shareName: String,
        relativePath: String
    ): Result<String> =
        smbRepository.readText(
            settings.copy(connectionMode = ConnectionMode.SMB3, port = 445),
            shareName.trim('/'),
            relativePath.trim('/').replace('\\', '/')
        )

    /**
     * Write a text file at [shareName]/[relativePath] over SMB3/445,
     * even when normal browsing uses DSM HTTP.
     */
    suspend fun writeTextAt(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        contents: String
    ): Result<Unit> =
        smbRepository.writeText(
            settings.copy(connectionMode = ConnectionMode.SMB3, port = 445),
            shareName.trim('/'),
            relativePath.trim('/').replace('\\', '/'),
            contents
        )

    /**
     * File names in a folder that look like progress sidecars (`*.pallas.json`).
     * Uses SMB listNames so HTTP browse mode still works.
     */
    suspend fun listProgressSidecarNames(
        settings: AppSettings,
        shareName: String,
        folderPath: String
    ): Result<List<String>> =
        smbRepository.listNames(
            settings.copy(connectionMode = ConnectionMode.SMB3, port = 445),
            shareName.trim('/'),
            folderPath.trim('/').replace('\\', '/')
        ).map { names -> names.filter { NasPaths.isProgressSidecar(it) } }

    /** Uploads a completed recording over SMB even when normal browsing uses DSM HTTP. */
    suspend fun writeRecordingFile(
        settings: AppSettings,
        folderPath: String,
        fileName: String,
        source: java.io.File
    ): Result<Unit> {
        val (share, folder) = NasPaths.parseFolderPath(folderPath)
            ?: return Result.failure(IllegalArgumentException("Select a NAS recording folder"))
        val path = listOf(folder.trim('/'), fileName.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
        return smbRepository.copyLocalFileTo(
            settings.copy(connectionMode = ConnectionMode.SMB3, port = 445),
            share,
            path,
            source
        )
    }

    private fun buildSmbUri(host: String, share: String, relativePath: String): Uri {
        val sharePart = encode(share.trim('/'))
        val pathPart = relativePath.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encode(it) }
        return Uri.parse("smb://${host.trim()}/$sharePart/$pathPart")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun isIgnoredDirectory(entry: SmbEntry): Boolean =
        entry.isDirectory && NasPaths.isIgnoredDirectoryName(entry.name)
}
