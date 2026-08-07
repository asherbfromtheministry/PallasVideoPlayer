package com.vizvag.shieldvideo.data.index

import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.smb.SmbEntry
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import com.vizvag.shieldvideo.music.data.synology.VideoStationFile
import com.vizvag.shieldvideo.music.data.synology.VideoStationIndexedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class VideoIndexUiStatus(
    val building: Boolean = false,
    val entryCount: Int = 0,
    val builtAtMs: Long = 0L,
    val progressCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val source: String = "",
) {
    val isStale: Boolean
        get() = builtAtMs <= 0L ||
            System.currentTimeMillis() - builtAtMs >= VideoIndexController.STALE_AFTER_MS
}

class VideoIndexController(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val store: VideoIndexStore,
    private val synologyApiClient: SynologyApiClient? = null,
) {
    private val mutex = Mutex()
    private var snapshot = store.load()
    private var loopJob: Job? = null
    private var rebuildJob: Job? = null

    private val _status = MutableStateFlow(
        VideoIndexUiStatus(
            entryCount = snapshot.entries.size,
            builtAtMs = snapshot.builtAtMs,
            source = snapshot.source,
        )
    )
    val status: StateFlow<VideoIndexUiStatus> = _status.asStateFlow()

    fun start(scope: CoroutineScope) {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val settings = settingsRepository.load()
                if (settings.password.isNotBlank()) {
                    ensureFresh(settings, force = false)
                }
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    fun rebuildNow(scope: CoroutineScope) {
        rebuildJob?.cancel()
        rebuildJob = scope.launch(Dispatchers.IO) {
            val settings = settingsRepository.load()
            ensureFresh(settings, force = true)
        }
    }

    suspend fun ensureFresh(settings: AppSettings? = null, force: Boolean = false): Result<VideoIndexSnapshot> {
        val cfg = settings ?: settingsRepository.load()
        if (cfg.password.isBlank()) {
            return Result.failure(IllegalStateException("NAS password required to build index"))
        }
        val roots = cfg.shares.map { if (it.startsWith("/")) it else "/$it" }.distinct()
        if (roots.isEmpty()) {
            return Result.failure(IllegalStateException("No video folders configured"))
        }

        val missingIndexedRoot = roots.any { root ->
            val share = NasPaths.parseFolderPath(root)?.first
                ?: root.trim('/').substringBefore('/')
            snapshot.entries.none { entry ->
                !entry.isDirectory && (
                    entry.root.equals(root, ignoreCase = true) ||
                        entry.share.equals(share, ignoreCase = true)
                    )
            }
        }

        val needsRebuild = force ||
            snapshot.isEmpty ||
            missingIndexedRoot ||
            snapshot.host != cfg.host ||
            snapshot.connectionMode != cfg.connectionMode.name ||
            snapshot.roots.map { it.lowercase() }.toSet() != roots.map { it.lowercase() }.toSet() ||
            snapshot.ageMs >= STALE_AFTER_MS

        if (!needsRebuild) {
            _status.update {
                it.copy(
                    building = false,
                    entryCount = snapshot.entries.size,
                    builtAtMs = snapshot.builtAtMs,
                    source = snapshot.source,
                    error = null,
                    message = readyMessage(snapshot),
                )
            }
            return Result.success(snapshot)
        }

        return rebuild(cfg, roots)
    }

    fun search(
        query: String,
        roots: List<String>,
        maxResults: Int = 80
    ): List<Pair<String, SmbEntry>> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        fun normalizeRoot(raw: String): String {
            val t = raw.trim().trimEnd('/').replace('\\', '/')
            return if (t.startsWith("/")) t.lowercase() else "/$t".lowercase()
        }
        val rootSet = roots.map(::normalizeRoot).toSet()
        return snapshot.entries.asSequence()
            .filter { entry ->
                rootSet.isEmpty() || rootSet.contains(normalizeRoot(entry.root))
            }
            .filter { entry -> !pathContainsIgnoredFolder(entry.path) }
            .filter { it.matches(tokens) }
            .take(maxResults)
            .map { it.root to it.toEntry() }
            .toList()
    }

    fun currentSnapshot(): VideoIndexSnapshot = snapshot

    /** True when the index knows at least one video file on this SMB/File Station share. */
    fun shareHasIndexedVideos(share: String): Boolean {
        val want = share.trim('/').lowercase()
        if (want.isEmpty() || snapshot.isEmpty) return false
        return snapshot.entries.any { entry ->
            !entry.isDirectory && entry.share.trim('/').equals(want, ignoreCase = true)
        }
    }

    /**
     * True if any indexed video file lives at [folderRelativePath] or under it
     * (share-relative, e.g. `APK` or `Shows/Foo`).
     */
    fun hasPlayableUnder(share: String, folderRelativePath: String): Boolean {
        val wantShare = share.trim('/').lowercase()
        val folder = folderRelativePath.trim('/').replace('\\', '/').lowercase()
        if (wantShare.isEmpty() || snapshot.isEmpty) return false
        return snapshot.entries.any { entry ->
            if (entry.isDirectory) return@any false
            if (!entry.share.trim('/').equals(wantShare, ignoreCase = true)) return@any false
            val path = entry.path.trim('/').replace('\\', '/').lowercase()
            when {
                folder.isBlank() -> true
                path == folder -> true
                path.startsWith("$folder/") -> true
                else -> false
            }
        }
    }

    /** Indexed video files at or under [folderRelativePath] on [share] (flat list). */
    fun videosUnder(
        share: String,
        folderRelativePath: String,
        maxResults: Int = 2_000,
    ): List<SmbEntry> {
        val wantShare = share.trim('/').lowercase()
        val folder = folderRelativePath.trim('/').replace('\\', '/').lowercase()
        if (wantShare.isEmpty() || snapshot.isEmpty) return emptyList()
        return snapshot.entries.asSequence()
            .filter { entry ->
                if (entry.isDirectory) return@filter false
                if (!entry.share.trim('/').equals(wantShare, ignoreCase = true)) return@filter false
                if (pathContainsIgnoredFolder(entry.path)) return@filter false
                val path = entry.path.trim('/').replace('\\', '/').lowercase()
                when {
                    folder.isBlank() -> true
                    path == folder -> true
                    path.startsWith("$folder/") -> true
                    else -> false
                }
            }
            .take(maxResults)
            .map { it.toEntry() }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    private fun pathContainsIgnoredFolder(path: String): Boolean =
        NasPaths.pathContainsIgnoredDirectory(path)

    private suspend fun rebuild(settings: AppSettings, roots: List<String>): Result<VideoIndexSnapshot> =
        mutex.withLock {
            _status.update {
                it.copy(building = true, progressCount = 0, error = null, message = "Syncing Video Station…")
            }

            val fromStation = withContext(Dispatchers.IO) {
                runCatching {
                    synologyApiClient?.listVideoStationVideos(roots) { fetched, total ->
                        _status.update { status ->
                            status.copy(
                                progressCount = fetched,
                                message = if (total > 0) {
                                    "Video Station… $fetched / $total"
                                } else {
                                    "Video Station… $fetched items"
                                },
                            )
                        }
                    }
                }.getOrNull()
            }

            if (fromStation != null && fromStation.isNotEmpty()) {
                val fromVs = entriesFromVideoStation(fromStation, roots)
                val missingRoots = roots.filter { root ->
                    val share = NasPaths.parseFolderPath(root)?.first ?: root.trim('/').substringBefore('/')
                    fromVs.none { entry ->
                        !entry.isDirectory && (
                            entry.root.equals(root, ignoreCase = true) ||
                                entry.share.equals(share, ignoreCase = true)
                            )
                    }
                }
                val merged = if (missingRoots.isEmpty()) {
                    fromVs
                } else {
                    _status.update {
                        it.copy(
                            building = true,
                            message = "Video Station missing ${missingRoots.joinToString()} — scanning…",
                        )
                    }
                    val walked = withContext(Dispatchers.IO) {
                        walkRootEntries(settings, missingRoots) { count ->
                            _status.update { status ->
                                status.copy(
                                    progressCount = count,
                                    message = "Scanning ${missingRoots.joinToString()}… $count",
                                )
                            }
                        }
                    }
                    (fromVs + walked).distinctBy {
                        "${it.share}/${it.path.lowercase()}/${it.isDirectory}"
                    }
                }
                return@withLock commitSnapshot(
                    settings = settings,
                    roots = roots,
                    entries = merged,
                    source = if (missingRoots.isEmpty()) {
                        SOURCE_VIDEO_STATION
                    } else {
                        SOURCE_VIDEO_STATION_PLUS_WALK
                    },
                    message = { n ->
                        if (missingRoots.isEmpty()) {
                            "Synced $n items from Video Station"
                        } else {
                            "Synced $n items (Video Station + ${missingRoots.joinToString()} scan)"
                        }
                    },
                )
            }

            _status.update {
                it.copy(
                    building = true,
                    progressCount = 0,
                    message = if (fromStation == null) {
                        "Video Station unavailable — scanning folders…"
                    } else {
                        "Video Station empty — scanning folders…"
                    },
                )
            }
            val walked = withContext(Dispatchers.IO) {
                walkRootEntries(settings, roots) { count ->
                    _status.update { status ->
                        status.copy(progressCount = count, message = "Indexing… $count items")
                    }
                }
            }
            if (walked.isEmpty() && fromStation == null) {
                // walkRootEntries swallows failures — surface a generic error if nothing indexed
                val probe = nasRepository.walkVideos(settings, roots, maxDepth = 1)
                if (probe.isFailure) {
                    val error = probe.exceptionOrNull() ?: Exception("Index build failed")
                    _status.update {
                        it.copy(
                            building = false,
                            error = error.message ?: "Index build failed",
                            message = null,
                        )
                    }
                    return@withLock Result.failure(error)
                }
            }
            return@withLock commitSnapshot(
                settings = settings,
                roots = roots,
                entries = walked,
                source = SOURCE_WALK,
                message = { n -> "Indexed $n items" },
            )
        }

    private suspend fun walkRootEntries(
        settings: AppSettings,
        roots: List<String>,
        onProgress: (Int) -> Unit = {},
    ): List<IndexedVideo> {
        val walked = nasRepository.walkVideos(settings, roots, maxDepth = 12, onProgress = onProgress)
        return walked.getOrElse { emptyList() }.mapNotNull { (root, entry) ->
            val (share, _) = NasPaths.parseFolderPath(root) ?: return@mapNotNull null
            IndexedVideo(
                root = root,
                share = share,
                path = entry.path,
                name = entry.name,
                isDirectory = entry.isDirectory,
                size = entry.size,
            )
        }
    }

    private fun commitSnapshot(
        settings: AppSettings,
        roots: List<String>,
        entries: List<IndexedVideo>,
        source: String,
        message: (Int) -> String,
    ): Result<VideoIndexSnapshot> {
        val next = VideoIndexSnapshot(
            builtAtMs = System.currentTimeMillis(),
            host = settings.host,
            connectionMode = settings.connectionMode.name,
            roots = roots,
            entries = entries,
            source = source,
        )
        store.save(next)
        snapshot = next
        _status.update {
            it.copy(
                building = false,
                entryCount = entries.size,
                builtAtMs = next.builtAtMs,
                progressCount = entries.size,
                source = source,
                message = message(entries.size),
                error = null,
            )
        }
        return Result.success(next)
    }

    private fun readyMessage(snap: VideoIndexSnapshot): String {
        val n = snap.entries.size
        return when (snap.source) {
            SOURCE_VIDEO_STATION -> "Video Station index ready ($n items)"
            SOURCE_WALK -> "Index ready ($n items)"
            else -> "Index ready ($n items)"
        }
    }

    companion object {
        val STALE_AFTER_MS: Long = TimeUnit.HOURS.toMillis(24)
        const val SOURCE_VIDEO_STATION = "videostation"
        const val SOURCE_VIDEO_STATION_PLUS_WALK = "videostation+walk"
        const val SOURCE_WALK = "walk"

        internal fun entriesFromVideoStation(
            files: List<VideoStationIndexedFile>,
            roots: List<String>,
        ): List<IndexedVideo> {
            val normalizedRoots = roots
                .map { it.replace('\\', '/').trimEnd('/') }
                .filter { it.isNotBlank() && it != "/" }
                .distinctBy { it.lowercase() }
            val entries = LinkedHashMap<String, IndexedVideo>()

            for (file in files) {
                val fullPath = VideoStationFile.toFileStationPath(file.path)
                if (NasPaths.pathContainsIgnoredDirectory(fullPath)) continue
                val root = normalizedRoots
                    .filter {
                        fullPath.equals(it, ignoreCase = true) ||
                            fullPath.startsWith("$it/", ignoreCase = true)
                    }
                    .maxByOrNull { it.length }
                    ?: continue
                val (share, _) = NasPaths.parseFolderPath(root) ?: continue
                val sharePrefix = "/$share"
                val withinShare = when {
                    fullPath.equals(sharePrefix, ignoreCase = true) -> ""
                    fullPath.startsWith("$sharePrefix/", ignoreCase = true) ->
                        fullPath.substring(sharePrefix.length + 1)
                    else -> fullPath.trimStart('/')
                }
                if (withinShare.isBlank()) continue
                val fileName = file.filename?.takeIf { it.isNotBlank() }
                    ?: withinShare.substringAfterLast('/')
                if (!NasPaths.isVideoFile(fileName)) continue
                if (NasPaths.isProgressSidecar(fileName)) continue

                val fileKey = "$share/${withinShare.lowercase()}"
                entries[fileKey] = IndexedVideo(
                    root = root,
                    share = share,
                    path = withinShare,
                    name = fileName,
                    isDirectory = false,
                    size = file.size,
                    title = file.title,
                )

                // Synthesize parent folders so search / shelf helpers match walk-based indexes.
                var parent = withinShare.substringBeforeLast('/', missingDelimiterValue = "")
                while (parent.isNotBlank()) {
                    val dirKey = "$share/${parent.lowercase()}"
                    if (!entries.containsKey(dirKey)) {
                        entries[dirKey] = IndexedVideo(
                            root = root,
                            share = share,
                            path = parent,
                            name = parent.substringAfterLast('/'),
                            isDirectory = true,
                        )
                    }
                    parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
                }
            }
            return entries.values.toList()
        }
    }
}
