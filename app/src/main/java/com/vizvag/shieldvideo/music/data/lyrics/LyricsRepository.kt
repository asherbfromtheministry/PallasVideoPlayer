package com.vizvag.shieldvideo.music.data.lyrics

import android.util.Log
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.smb.SmbRepository
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient

data class LyricsLoadResult(
    val lines: List<LyricLine>,
    val lyricsPath: String? = null,
)

class LyricsRepository(
    private val synologyApiClient: SynologyApiClient,
    private val settingsRepository: SettingsRepository,
    private val smbRepository: SmbRepository,
    private val onlineLyricsClient: OnlineLyricsClient = OnlineLyricsClient(),
) {
    private val cache = mutableMapOf<String, List<LyricLine>>()
    private val onlineCache = mutableMapOf<String, List<LyricLine>>()

    suspend fun getLyrics(path: String?): List<LyricLine> {
        if (path.isNullOrBlank()) return emptyList()
        return downloadFirstHit(path)?.lines.orEmpty()
    }

    suspend fun getOnlineLyrics(
        track: TrackEntity,
        titleOverride: String? = null,
        artistOverride: String? = null,
        albumOverride: String? = null,
        durationMsOverride: Long? = null,
    ): LyricsLoadResult {
        val cacheKey = track.id.ifBlank { track.nasPath }
        onlineCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let {
            return LyricsLoadResult(it, lyricsPath = "online:lrclib")
        }
        val title = titleOverride?.takeIf { it.isNotBlank() } ?: track.title
        val artist = artistOverride?.takeIf { it.isNotBlank() } ?: track.artistName
        val album = albumOverride?.takeIf { it.isNotBlank() } ?: track.albumTitle
        val durationMs = durationMsOverride?.takeIf { it > 0 }
            ?: track.durationMs.takeIf { it > 0 }
            ?: 0L
        Log.i(TAG, "online lyrics lookup: '$title' / '$artist' / '$album' (${durationMs}ms)")
        val lines = runCatching {
            onlineLyricsClient.fetch(title, artist, album, durationMs)
        }.onFailure {
            Log.e(TAG, "online lyrics failed", it)
        }.getOrDefault(emptyList())
        if (lines.isNotEmpty()) {
            onlineCache[cacheKey] = lines
            Log.i(TAG, "online lyrics loaded (${lines.size} lines)")
            return LyricsLoadResult(lines, lyricsPath = "online:lrclib")
        }
        Log.w(TAG, "online lyrics not found for '$title'")
        return LyricsLoadResult(emptyList())
    }

    fun cachedOnlineLyrics(trackId: String): List<LyricLine>? =
        onlineCache[trackId]?.takeIf { it.isNotEmpty() }

    suspend fun getLyricsForTrack(track: TrackEntity): LyricsLoadResult {
        Log.i(TAG, "loading lyrics for ${track.nasPath}")
        val folder = track.nasPath.replace('\\', '/').substringBeforeLast('/')
        val audioStem = track.nasPath.substringAfterLast('/').substringBeforeLast('.')

        // SMB first — real on-disk names (#Pop, double spaces after track #).
        // File Station often fails on '#' in paths and on spacing mismatches from Audio Station.
        val smbHit = loadViaSmb(folder, audioStem, track.title)
        if (smbHit != null) {
            Log.i(TAG, "lyrics loaded via SMB: ${smbHit.lyricsPath} (${smbHit.lines.size} lines)")
            return smbHit
        }

        val tried = linkedSetOf<String>()
        track.lyricsPath?.takeIf { it.isNotBlank() }?.let { tried += it }
        tried += MetadataResolver.lyricsPathFor(track.nasPath)

        for (path in tried) {
            val hit = downloadFirstHit(path)
            if (hit != null) {
                Log.i(TAG, "lyrics loaded via direct path: ${hit.lyricsPath} (${hit.lines.size} lines)")
                return hit
            }
        }

        // File Station list (may fail on odd folder names like #Pop)
        val listed = pathVariants(folder).firstNotNullOfOrNull { variant ->
            runCatching { synologyApiClient.listFolder(variant) }
                .onFailure { Log.w(TAG, "listFolder failed for $variant: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.files.isNotEmpty() }
        }
        if (listed != null) {
            val lrcFiles = listed.files.filter {
                !it.isdir && it.name.endsWith(".lrc", ignoreCase = true)
            }
            val match = findBestLrc(
                files = lrcFiles.map { it.name to it.path },
                audioStem = audioStem,
                trackTitle = track.title,
            )
            if (match != null) {
                downloadFirstHit(match)?.let {
                    Log.i(TAG, "lyrics loaded via File Station list: ${it.lyricsPath}")
                    return it
                }
                val name = lrcFiles.firstOrNull {
                    it.path == match || it.name.equals(match.substringAfterLast('/'), true)
                }?.name
                if (name != null) {
                    for (folderVariant in pathVariants(folder)) {
                        downloadFirstHit("$folderVariant/$name")?.let {
                            Log.i(TAG, "lyrics loaded via rebuilt FS path: ${it.lyricsPath}")
                            return it
                        }
                    }
                }
            }
        }

        val cachedOnline = onlineCache[track.id.ifBlank { track.nasPath }]
            ?.takeIf { it.isNotEmpty() }
        if (cachedOnline != null) {
            Log.i(TAG, "lyrics from online cache (${cachedOnline.size} lines)")
            return LyricsLoadResult(cachedOnline, lyricsPath = "online:lrclib")
        }

        Log.w(TAG, "lyrics not found for ${track.nasPath}")
        return LyricsLoadResult(emptyList())
    }

    private suspend fun loadViaSmb(
        folderNasPath: String,
        audioStem: String,
        trackTitle: String,
    ): LyricsLoadResult? {
        val settings = runCatching { settingsRepository.load() }
            .onFailure { Log.e(TAG, "settings load failed", it) }
            .getOrNull()
        if (settings == null) {
            Log.w(TAG, "SMB lyrics skipped: no settings")
            return null
        }
        if (settings.host.isBlank()) {
            Log.w(TAG, "SMB lyrics skipped: blank host")
            return null
        }
        if (settings.password.isBlank()) {
            Log.w(TAG, "SMB lyrics skipped: blank password")
            return null
        }
        val parsed = NasPaths.parseFolderPath(folderNasPath)
        if (parsed == null) {
            Log.w(TAG, "SMB lyrics skipped: bad folder path $folderNasPath")
            return null
        }
        val (share, folderRel) = parsed
        val smbSettings = smbConnectSettings(settings)

        val namesResult = smbRepository.listNames(smbSettings, share, folderRel)
        val names = namesResult.getOrElse {
            Log.e(TAG, "SMB list failed share=$share folder=$folderRel port=${smbSettings.port}", it)
            return null
        }
        val lrcNames = names.filter { it.endsWith(".lrc", ignoreCase = true) }
        if (lrcNames.isEmpty()) {
            Log.w(TAG, "SMB folder listed ${names.size} entries but no .lrc under $folderRel")
            return null
        }

        val matchName = findBestLrc(
            files = lrcNames.map { it to it },
            audioStem = audioStem,
            trackTitle = trackTitle,
        )
        if (matchName == null) {
            Log.w(TAG, "SMB: no .lrc match for stem='$audioStem' title='$trackTitle' among ${lrcNames.size}")
            return null
        }

        val fileName = matchName.substringAfterLast('/')
        for (nameVariant in filenameSpacingVariants(fileName)) {
            val rel = if (folderRel.isBlank()) nameVariant else "$folderRel/$nameVariant"
            val bytesResult = smbRepository.readBytes(smbSettings, share, rel)
            if (bytesResult.isFailure) {
                Log.w(TAG, "SMB read failed for $rel: ${bytesResult.exceptionOrNull()?.message}")
                continue
            }
            val bytes = bytesResult.getOrThrow()
            val parsedLines = LrcParser.parse(decodeLrc(bytes))
            if (parsedLines.isNotEmpty()) {
                val nasPath = "/$share/$rel".replace("//", "/")
                cache[nasPath] = parsedLines
                return LyricsLoadResult(parsedLines, nasPath)
            }
            Log.w(TAG, "SMB read $rel but LRC parse yielded 0 lines (${bytes.size} bytes)")
        }
        return null
    }

    private suspend fun downloadFirstHit(path: String): LyricsLoadResult? {
        for (candidate in pathVariants(path)) {
            cache[candidate]?.let { return LyricsLoadResult(it, candidate) }

            val rawFs = runCatching { synologyApiClient.downloadBytes(candidate) }
                .onFailure { Log.w(TAG, "FS download failed for $candidate: ${it.message}") }
                .getOrNull()
            if (rawFs != null && !looksLikeSynologyJsonError(rawFs)) {
                val parsed = LrcParser.parse(decodeLrc(rawFs))
                if (parsed.isNotEmpty()) {
                    cache[candidate] = parsed
                    return LyricsLoadResult(parsed, candidate)
                }
            }

            val settings = runCatching { settingsRepository.load() }.getOrNull()
            if (settings != null && settings.password.isNotBlank()) {
                val parsedPath = NasPaths.parseFolderPath(candidate)
                if (parsedPath != null) {
                    val (share, rel) = parsedPath
                    val rawSmb = smbRepository.readBytes(smbConnectSettings(settings), share, rel)
                        .onFailure { Log.w(TAG, "SMB read failed for $rel: ${it.message}") }
                        .getOrNull()
                    if (rawSmb != null) {
                        val parsed = LrcParser.parse(decodeLrc(rawSmb))
                        if (parsed.isNotEmpty()) {
                            cache[candidate] = parsed
                            return LyricsLoadResult(parsed, candidate)
                        }
                    }
                }
            }
        }
        return null
    }

    fun clearCache() {
        cache.clear()
        onlineCache.clear()
    }

    /** Lyrics SMB must use 445 even when the app is in HTTP/File Station mode (port 5000). */
    private fun smbConnectSettings(settings: AppSettings): AppSettings = settings.copy(
        connectionMode = ConnectionMode.SMB3,
        port = ConnectionMode.SMB3.defaultPort,
    )

    companion object {
        private const val TAG = "PallasLyrics"

        fun looksLikeSynologyJsonError(bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || bytes[0] != '{'.code.toByte()) return false
            val head = String(bytes, 0, minOf(bytes.size, 96), Charsets.UTF_8)
            return head.contains("\"success\"") && head.contains("false")
        }

        fun pathVariants(path: String): List<String> {
            val n = path.replace('\\', '/').trim()
            if (n.isEmpty()) return emptyList()
            val folder = n.substringBeforeLast('/', "")
            val name = n.substringAfterLast('/')
            val folders = linkedSetOf(
                folder,
                folder.trimStart('/'),
                if (folder.isNotEmpty()) "/${folder.trimStart('/')}" else "",
            ).filter { it.isNotEmpty() }
            val names = filenameSpacingVariants(name)
            val out = linkedSetOf<String>()
            out += n
            out += n.trimStart('/')
            out += "/${n.trimStart('/')}"
            for (f in folders) {
                for (nm in names) {
                    out += "$f/$nm"
                }
            }
            return out.toList()
        }

        fun filenameSpacingVariants(fileName: String): List<String> {
            val out = linkedSetOf(fileName)
            out += fileName.replace(Regex("""\s+"""), " ")
            out += fileName.replace(Regex("""^(\d{1,3})\s+"""), "$1  ")
            out += fileName.replace(Regex("""^(\d{1,3})\s+"""), "$1 ")
            return out.toList()
        }

        fun normalizeStem(value: String): String =
            value.lowercase()
                .replace('_', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()

        fun coreStem(value: String): String =
            normalizeStem(value)
                .replace(Regex("""^\d{1,3}\s*[).:\-–—]?\s*"""), "")
                .trim()

        internal fun findBestLrc(
            files: List<Pair<String, String>>,
            audioStem: String,
            trackTitle: String,
        ): String? {
            val audioNorm = normalizeStem(audioStem)
            val audioCore = coreStem(audioStem)
            val titleCore = coreStem(trackTitle).takeIf { it.length >= 3 }

            files.firstOrNull {
                it.first.substringBeforeLast('.').equals(audioStem, ignoreCase = true)
            }?.let { return it.second }

            files.firstOrNull {
                normalizeStem(it.first.substringBeforeLast('.')) == audioNorm
            }?.let { return it.second }

            if (audioCore.isNotBlank()) {
                files.firstOrNull {
                    coreStem(it.first.substringBeforeLast('.')) == audioCore
                }?.let { return it.second }
            }

            if (titleCore != null) {
                files.firstOrNull {
                    val core = coreStem(it.first.substringBeforeLast('.'))
                    core.contains(titleCore) || titleCore.contains(core)
                }?.let { return it.second }
            }

            if (audioCore.length >= 4) {
                files.firstOrNull {
                    val core = coreStem(it.first.substringBeforeLast('.'))
                    core.contains(audioCore) || audioCore.contains(core)
                }?.let { return it.second }
            }

            return null
        }

        fun decodeLrc(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, Charsets.UTF_16BE)
            }
            val utf8 = String(bytes, Charsets.UTF_8)
            val bad = utf8.count { it == '\uFFFD' }
            return if (bad > utf8.length / 10) {
                String(bytes, charset("windows-1252"))
            } else {
                utf8
            }
        }
    }
}
