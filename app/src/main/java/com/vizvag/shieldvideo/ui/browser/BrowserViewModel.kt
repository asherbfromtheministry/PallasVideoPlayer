package com.vizvag.shieldvideo.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.data.index.VideoIndexController
import com.vizvag.shieldvideo.data.nas.NasConnectionErrors
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.smb.SmbEntry
import com.vizvag.shieldvideo.data.tmdb.TmdbRepository
import com.vizvag.shieldvideo.data.trakt.FilenameParser
import com.vizvag.shieldvideo.data.trakt.ForcedMetadata
import com.vizvag.shieldvideo.data.trakt.MediaKind
import com.vizvag.shieldvideo.data.trakt.MetadataOverrideStore
import com.vizvag.shieldvideo.data.trakt.TraktHistory
import com.vizvag.shieldvideo.data.trakt.TraktMatch
import com.vizvag.shieldvideo.data.trakt.TraktRepository
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.PlayerLaunchResult
import com.vizvag.shieldvideo.playback.ResumeMonitor
import com.vizvag.shieldvideo.playback.VlcLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MediaCardItem(
    val entry: SmbEntry,
    val displayTitle: String,
    val line1: String,
    val line2: String,
    val line3: String,
    val fanartUrl: String?,
    val posterUrl: String?,
    val overview: String?,
    val watched: Boolean = false,
    val resumePositionMs: Long? = null,
    /** 0f–1f when known from Trakt progress / runtime */
    val resumeProgress: Float? = null,
    val resolutionLabel: String? = null,
    val isHdr: Boolean = false,
    val fpsLabel: String? = null,
    /** e.g. `1h 42m` from Trakt runtime or measured duration */
    val runtimeLabel: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val metadataCleared: Boolean = false,
    /** Configured video-folder root (e.g. `/download`) when item comes from search. */
    val videoRoot: String? = null
)

data class BrowserUiState(
    val settings: AppSettings = AppSettings(),
    val selectedShare: String = "download",
    val pathStack: List<String> = emptyList(),
    val items: List<MediaCardItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val showVlcMissing: Boolean = false,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchFolders: Set<String> = emptySet(),
    val searchResults: List<MediaCardItem> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val searchRan: Boolean = false,
    val searchUseIndex: Boolean = true,
    val indexBuilding: Boolean = false,
    val indexEntryCount: Int = 0,
    val indexBuiltAtMs: Long = 0L,
    val indexMessage: String? = null
)

data class FolderAssignUi(
    val folder: MediaCardItem? = null,
    val loading: Boolean = false,
    val candidates: List<TraktMatch> = emptyList(),
    val error: String? = null,
)

data class FolderClearOptionsUi(
    val folder: MediaCardItem? = null,
)

data class BrowserItemOptionsUi(
    val item: MediaCardItem? = null,
    val confirmingDelete: Boolean = false,
)

data class ArchiveExtractUi(
    val item: MediaCardItem? = null,
    val confirming: Boolean = false,
    val running: Boolean = false,
    val minimized: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val error: String? = null,
)

class BrowserViewModel(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val vlcLauncher: VlcLauncher,
    private val resumeStore: LocalResumeStore,
    private val resumeMonitor: ResumeMonitor,
    private val nasWatchHistory: NasWatchHistoryStore,
    private val metadataOverrides: MetadataOverrideStore,
    private val videoIndex: VideoIndexController,
    private val progressSync: NasProgressSync,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _folderAssign = MutableStateFlow(FolderAssignUi())
    val folderAssign: StateFlow<FolderAssignUi> = _folderAssign.asStateFlow()

    private val _folderClearOptions = MutableStateFlow(FolderClearOptionsUi())
    val folderClearOptions: StateFlow<FolderClearOptionsUi> = _folderClearOptions.asStateFlow()

    private val _itemOptions = MutableStateFlow(BrowserItemOptionsUi())
    val itemOptions: StateFlow<BrowserItemOptionsUi> = _itemOptions.asStateFlow()

    private val _archiveExtract = MutableStateFlow(ArchiveExtractUi())
    val archiveExtract: StateFlow<ArchiveExtractUi> = _archiveExtract.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var extractJob: Job? = null
    private var history: TraktHistory = TraktHistory()
    private var assignJob: Job? = null
    /** Share + pathStack key for the items currently on screen — soft refresh only when unchanged. */
    private var listedLocationKey: String? = null

    init {
        val settings = settingsRepository.load()
        val indexStatus = videoIndex.status.value
        _state.value = BrowserUiState(
            settings = settings,
            selectedShare = settings.defaultShare.ifBlank {
                settings.shares.firstOrNull() ?: "download"
            },
            loading = true,
            indexBuilding = indexStatus.building,
            indexEntryCount = indexStatus.entryCount,
            indexBuiltAtMs = indexStatus.builtAtMs,
            indexMessage = indexStatus.message
        )
        viewModelScope.launch {
            videoIndex.status.collect { status ->
                _state.update {
                    it.copy(
                        indexBuilding = status.building,
                        indexEntryCount = status.entryCount,
                        indexBuiltAtMs = status.builtAtMs,
                        indexMessage = status.message ?: status.error
                    )
                }
            }
        }
    }

    fun reloadFromSettings() {
        val settings = settingsRepository.load()
        val current = _state.value.selectedShare
        val stillValid = settings.shares.any { it.equals(current, true) } ||
            settings.iptvRecordingNasFolder.equals(current, ignoreCase = true)
        val share = if (stillValid) {
            current
        } else {
            settings.defaultShare.ifBlank { settings.shares.firstOrNull() ?: "download" }
        }
        val resetPath = _state.value.settings.host != settings.host ||
            _state.value.settings.username != settings.username ||
            _state.value.settings.password != settings.password ||
            _state.value.settings.connectionMode != settings.connectionMode ||
            !_state.value.selectedShare.equals(share, true)
        _state.update {
            it.copy(
                settings = settings,
                selectedShare = share,
                pathStack = if (resetPath) emptyList() else it.pathStack,
                message = null,
                error = null
            )
        }
        refresh()
    }

    fun selectShare(share: String) {
        _state.update {
            it.copy(
                selectedShare = share,
                pathStack = emptyList(),
                items = emptyList(),
                loading = true,
                error = null,
                message = null,
            )
        }
        listedLocationKey = null
        refresh()
    }

    fun openFolderEntry(entry: SmbEntry) {
        if (!entry.isDirectory) return
        val relative = relativeToVideoRoot(entry.path)
        _state.update {
            it.copy(
                pathStack = relative,
                items = emptyList(),
                loading = true,
                error = null,
                message = null,
            )
        }
        listedLocationKey = null
        refresh()
    }

    fun goUp() {
        val stack = _state.value.pathStack
        if (stack.isEmpty()) return
        _state.update {
            it.copy(
                pathStack = stack.dropLast(1),
                items = emptyList(),
                loading = true,
                error = null,
            )
        }
        listedLocationKey = null
        refresh()
    }

    fun openFolderClearOptions(item: MediaCardItem) {
        if (!item.entry.isDirectory) return
        _folderClearOptions.value = FolderClearOptionsUi(folder = item)
    }

    fun dismissFolderClearOptions() {
        _folderClearOptions.value = FolderClearOptionsUi()
    }

    fun openItemOptions(item: MediaCardItem) {
        _itemOptions.value = BrowserItemOptionsUi(item = item, confirmingDelete = false)
    }

    fun dismissItemOptions() {
        _itemOptions.value = BrowserItemOptionsUi()
    }

    fun requestDeleteFromOptions() {
        val item = _itemOptions.value.item ?: return
        _itemOptions.value = BrowserItemOptionsUi(item = item, confirmingDelete = true)
    }

    fun cancelDeleteFromOptions() {
        val item = _itemOptions.value.item ?: return
        _itemOptions.value = BrowserItemOptionsUi(item = item, confirmingDelete = false)
    }

    fun confirmDeleteItem() {
        val item = _itemOptions.value.item ?: return
        dismissItemOptions()
        viewModelScope.launch {
            val settings = _state.value.settings
            val (shareName, _) = parseVideoRoot(item.videoRoot ?: _state.value.selectedShare)
            val relative = pathRelativeToShare(item.entry.path, shareName)
            val result = nasRepository.deleteEntry(
                settings = settings,
                shareName = shareName,
                relativePath = relative,
                isDirectory = item.entry.isDirectory,
            )
            result.fold(
                onSuccess = {
                    val kind = if (item.entry.isDirectory) "folder" else "file"
                    _state.update {
                        it.copy(message = "Deleted $kind “${item.entry.name}”")
                    }
                    refresh()
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(message = "Delete failed: ${err.message ?: "unknown error"}")
                    }
                }
            )
        }
    }

    fun clearItemMetadata(item: MediaCardItem, includeDescendants: Boolean = true) {
        metadataOverrides.clearMetadata(item.entry.path, includeDescendants = includeDescendants)
        val kind = when {
            !item.entry.isDirectory -> "file"
            includeDescendants -> "folder and everything inside"
            else -> "folder only"
        }
        _state.update {
            it.copy(message = "Cleared TV/movie metadata for $kind “${item.displayTitle}”")
        }
        dismissFolderClearOptions()
        dismissItemOptions()
        reenrichItem(item)
    }

    fun restoreItemMetadata(item: MediaCardItem) {
        metadataOverrides.restoreMetadata(item.entry.path)
        val kind = if (item.entry.isDirectory) "folder" else "file"
        _state.update {
            it.copy(message = "Restoring metadata lookup for $kind “${item.displayTitle}”")
        }
        dismissItemOptions()
        reenrichItem(item)
    }

    /** Update one card in place so the list does not remount / jump scroll. */
    private fun reenrichItem(item: MediaCardItem) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val card = if (item.entry.isDirectory) {
                enrichFolder(item.entry, settings)
            } else {
                enrichFile(item.entry, settings)
            }.copy(videoRoot = item.videoRoot)
            if (_state.value.searchOpen) {
                replaceSearchResult(
                    path = item.entry.path,
                    root = item.videoRoot ?: _state.value.selectedShare,
                    card = card,
                )
            } else {
                replaceBrowseItem(item.entry.path, card)
            }
        }
    }

    fun openFolderAssign(item: MediaCardItem) {
        dismissItemOptions()
        assignJob?.cancel()
        _folderAssign.value = FolderAssignUi(folder = item, loading = true)
        assignJob = viewModelScope.launch {
            val settings = _state.value.settings
            val results = linkedMapOf<String, TraktMatch>()
            val queries = folderAssignQueries(item.entry.name, item.entry.path)
            for (query in queries) {
                val hits = runCatching {
                    traktRepository.searchCandidates(settings.traktClientId, query)
                }.getOrElse { emptyList() }
                hits.forEach { match ->
                    val key = "${match.mediaType}:${match.tmdbId ?: match.traktId}"
                    if (!results.containsKey(key)) results[key] = match
                }
                if (results.size >= 10) break
            }
            // Trakt 429 / blank client id — fall back to TMDB search so assign still works.
            if (results.isEmpty() &&
                (settings.tmdbApiKey.isNotBlank() || settings.tmdbReadToken.isNotBlank())
            ) {
                for (query in queries) {
                    val hits = runCatching {
                        tmdbRepository.searchCandidates(
                            apiKey = settings.tmdbApiKey,
                            readToken = settings.tmdbReadToken,
                            query = query,
                            preferTv = true,
                            limit = 12,
                        )
                    }.getOrElse { emptyList() }
                    hits.forEach { hit ->
                        val key = "${hit.mediaType}:${hit.tmdbId}"
                        if (!results.containsKey(key)) {
                            results[key] = TraktMatch(
                                title = hit.title,
                                year = hit.year,
                                overview = hit.overview,
                                rating = null,
                                mediaType = hit.mediaType,
                                tmdbId = hit.tmdbId,
                            )
                        }
                    }
                    if (results.size >= 10) break
                }
            }
            val list = results.values.toList()
            _folderAssign.update { current ->
                if (current.folder?.entry?.path != item.entry.path) current
                else current.copy(
                    loading = false,
                    candidates = list,
                    error = when {
                        list.isNotEmpty() -> null
                        settings.traktClientId.isBlank() &&
                            settings.tmdbApiKey.isBlank() &&
                            settings.tmdbReadToken.isBlank() ->
                            "Add a Trakt Client ID or TMDB key in Settings"
                        else -> "No matching shows or movies"
                    },
                )
            }
        }
    }

    /** Progressive Trakt search strings from a folder name (title first, then shorter fallbacks). */
    private fun folderAssignQueries(folderName: String, folderPath: String): List<String> {
        val parsed = parseFolderQuery(folderName, folderPath).searchQuery.trim()
        val spaced = folderName.replace('.', ' ').replace('_', ' ')
        val cleaned = spaced
            .replace(
                Regex(
                    """(?i)\b(S\d{1,2}(\s*[-–]\s*S\d{1,2})?|S\d{1,2}\s?E\d{1,3}|season\s*\d+|complete|pack|1080p|720p|2160p|4k|uhd|bluray|web[- ]?dl|webrip|amzn|dsnp|hulu|x264|x265|hevc|hdr10?\+?|proper|repack)\b""",
                ),
                " ",
            )
            .replace(Regex("""(?i)-[a-z0-9]{2,10}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val noYear = cleaned
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val short = TraktRepository.significantTokens(cleaned).take(3).joinToString(" ")
        return listOf(parsed, cleaned, noYear, short)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun dismissFolderAssign() {
        assignJob?.cancel()
        assignJob = null
        _folderAssign.value = FolderAssignUi()
    }

    fun assignFolderMetadata(match: TraktMatch) {
        val folder = _folderAssign.value.folder ?: return
        val tmdbId = match.tmdbId ?: return
        metadataOverrides.assignMetadata(
            folder.entry.path,
            ForcedMetadata(
                mediaType = match.mediaType,
                tmdbId = tmdbId,
                traktId = match.traktId,
                title = match.title,
                year = match.year,
                overview = match.overview,
            ),
        )
        dismissFolderAssign()
        _state.update {
            it.copy(message = "Assigned “${match.title}” to “${folder.displayTitle}” and all folders/files inside")
        }
        reenrichItem(folder)
    }

    fun keepFolderEmpty() {
        val folder = _folderAssign.value.folder ?: return
        metadataOverrides.keepEmpty(folder.entry.path)
        dismissFolderAssign()
        _state.update {
            it.copy(message = "Keeping “${folder.displayTitle}” without TV/movie art")
        }
        reenrichItem(folder)
    }

    fun openSearch() {
        val shares = _state.value.settings.shares
        _state.update {
            val retained = it.searchFolders.mapNotNull { selected ->
                shares.firstOrNull { share -> share.equals(selected, ignoreCase = true) }
            }.toSet()
            it.copy(
                searchOpen = true,
                searchFolders = retained.ifEmpty { shares.toSet() },
                searchError = null
            )
        }
        viewModelScope.launch {
            videoIndex.ensureFresh(force = false)
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchOpen = false,
                searchLoading = false,
                searchError = null
            )
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, searchRan = false) }
    }

    fun setSearchUseIndex(useIndex: Boolean) {
        _state.update { it.copy(searchUseIndex = useIndex, searchRan = false) }
    }

    fun rebuildVideoIndex() {
        videoIndex.rebuildNow(viewModelScope)
    }

    fun toggleSearchFolder(folder: String) {
        _state.update { state ->
            val shares = state.settings.shares
            val canonical = shares.firstOrNull { it.equals(folder, ignoreCase = true) } ?: folder
            val selected = state.searchFolders.mapNotNull { current ->
                shares.firstOrNull { it.equals(current, ignoreCase = true) }
            }.toMutableSet()
            val allSelected = shares.isNotEmpty() && shares.all { share ->
                selected.any { it.equals(share, ignoreCase = true) }
            }
            val alreadyOn = selected.any { it.equals(canonical, ignoreCase = true) }

            val next = when {
                // All on → pick one folder to search only that one
                allSelected && !alreadyOn -> mutableSetOf(canonical)
                allSelected && alreadyOn -> {
                    // Turn off this one while leaving the rest
                    selected.filterNot { it.equals(canonical, ignoreCase = true) }.toMutableSet()
                        .ifEmpty { mutableSetOf(canonical) }
                }
                alreadyOn -> {
                    selected.filterNot { it.equals(canonical, ignoreCase = true) }.toMutableSet()
                        .ifEmpty { mutableSetOf(canonical) }
                }
                else -> (selected + canonical).toMutableSet()
            }

            state.copy(searchFolders = next.toSet(), searchRan = false)
        }
    }

    fun selectAllSearchFolders() {
        _state.update {
            it.copy(searchFolders = it.settings.shares.toSet(), searchRan = false)
        }
    }

    fun runSearch() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank()) {
            _state.update { it.copy(searchError = "Enter a search term", searchRan = true) }
            return
        }
        val folders = _state.value.searchFolders.ifEmpty { _state.value.settings.shares.toSet() }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val settings = _state.value.settings
            _state.update {
                it.copy(searchLoading = true, searchError = null, searchRan = true, searchResults = emptyList())
            }
            if (settings.password.isBlank()) {
                _state.update {
                    it.copy(searchLoading = false, searchError = "Open settings and enter the NAS password")
                }
                return@launch
            }
            val controllingRemote =
                com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.isControllingRemote()
            val historyDeferred = async {
                if (controllingRemote) {
                    history
                } else {
                    runCatching {
                        traktRepository.loadHistory(
                            clientId = settings.traktClientId,
                            accessToken = settings.traktAccessToken.ifBlank { null },
                            slug = settings.traktSlug
                        )
                    }.getOrDefault(history)
                }
            }

            val useIndex = _state.value.searchUseIndex
            val found = if (useIndex) {
                val ensured = videoIndex.ensureFresh(settings, force = false)
                if (ensured.isFailure && videoIndex.currentSnapshot().isEmpty) {
                    Result.failure(
                        ensured.exceptionOrNull()
                            ?: IllegalStateException("Index unavailable — try Raw search or rebuild")
                    )
                } else {
                    Result.success(videoIndex.search(query, folders.toList()))
                }
            } else {
                nasRepository.searchVideos(settings, folders.toList(), query)
            }
            found.fold(
                onSuccess = { hits ->
                    val placeholders = hits.map { (root, entry) ->
                        placeholderCard(entry).copy(
                            videoRoot = root,
                            line2 = NasPaths.labelFor(root).takeIf { it.isNotBlank() }.orEmpty(),
                        )
                    }
                    _state.update {
                        it.copy(searchLoading = false, searchResults = placeholders, searchError = null)
                    }
                    history = historyDeferred.await()
                    if (!controllingRemote) {
                        enrichSearchHitsProgressively(hits, settings)
                    }
                },
                onFailure = { error ->
                    historyDeferred.cancel()
                    _state.update {
                        it.copy(
                            searchLoading = false,
                            searchResults = emptyList(),
                            searchError = error.message ?: "Search failed"
                        )
                    }
                }
            )
        }
    }

    private suspend fun enrichSearchHitsProgressively(
        hits: List<Pair<String, SmbEntry>>,
        settings: AppSettings
    ) = coroutineScope {
        val semaphore = Semaphore(2)
        hits.map { (root, entry) ->
            async {
                semaphore.withPermit {
                    val card = if (entry.isDirectory) {
                        enrichFolder(entry, settings).copy(
                            videoRoot = root,
                            line2 = NasPaths.labelFor(root).takeIf { it.isNotBlank() }.orEmpty(),
                        )
                    } else {
                        val (shareName, _) = parseVideoRoot(root)
                        runCatching {
                            progressSync.readAndMerge(settings, shareName, entry.path)
                        }
                        val base = enrichFile(entry, settings)
                        base.copy(
                            videoRoot = root,
                            line2 = listOf(NasPaths.labelFor(root), base.line2)
                                .filter { it.isNotBlank() }
                                .joinToString("  ·  ")
                        )
                    }
                    replaceSearchResult(entry.path, root, card)
                }
            }
        }.awaitAll()
    }

    private fun replaceSearchResult(path: String, root: String, card: MediaCardItem) {
        _state.update { state ->
            if (!state.searchOpen) return@update state
            val idx = state.searchResults.indexOfFirst {
                it.entry.path == path && (it.videoRoot == null || it.videoRoot.equals(root, true))
            }
            if (idx < 0) state
            else state.copy(
                searchResults = state.searchResults.toMutableList().also { it[idx] = card }
            )
        }
    }

    fun openSearchResult(item: MediaCardItem) {
        val root = item.videoRoot ?: _state.value.selectedShare
        if (item.entry.isDirectory) {
            val relative = relativeToVideoRootFor(root, item.entry.path)
            _state.update {
                it.copy(
                    selectedShare = root,
                    pathStack = relative,
                    searchOpen = false,
                    searchLoading = false
                )
            }
            refresh()
        } else {
            play(item)
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null, showVlcMissing = false) }
    }

    fun play(item: MediaCardItem) {
        if (NasPaths.isArchiveFile(item.entry.name)) {
            _state.update { it.copy(message = "Hold OK to extract") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(message = null, showVlcMissing = false) }
            val settings = _state.value.settings
            val path = item.entry.path
            val (shareName, _) = parseVideoRoot(item.videoRoot ?: _state.value.selectedShare)
            val merged = progressSync.readAndMerge(settings, shareName, path)
            val startPositionMs = when {
                merged?.watched == true -> null
                merged?.isMeaningful == true -> merged.positionMs
                else -> item.resumePositionMs
            }
            runCatching {
                com.vizvag.shieldvideo.playback.remote.RemotePlayBridge.playNasVideo(
                    share = shareName,
                    path = path,
                    title = item.displayTitle,
                    positionMs = startPositionMs,
                    host = settings.host,
                ) {
                    playLocalNasVideo(item, settings, shareName, path, startPositionMs)
                }
            }.onFailure { err ->
                _state.update { it.copy(message = err.message ?: "Remote play failed") }
            }
        }
    }

    private suspend fun playLocalNasVideo(
        item: MediaCardItem,
        settings: com.vizvag.shieldvideo.data.settings.AppSettings,
        shareName: String,
        path: String,
        startPositionMs: Long?,
    ) {
            val uriResult = nasRepository.playbackUri(
                settings = settings,
                shareName = shareName,
                relativePath = path
            )
            val result = uriResult.fold(
                onSuccess = { uri ->
                    runCatching {
                        val playerPkg = settings.playerPackage.ifBlank { MediaPlayerLauncher.VLC_PACKAGE }
                        vlcLauncher.play(
                            playbackUri = uri,
                            relativePath = path,
                            title = item.displayTitle,
                            playerPackage = playerPkg,
                            startPositionMs = startPositionMs
                        ) {
                            nasWatchHistory.record(shareName, path, item.displayTitle)
                            val handoff = nasRepository.handoffUri(settings, shareName, path).toString()
                            resumeMonitor.start(
                                path = path,
                                playerPackage = playerPkg,
                                playbackUri = handoff,
                                title = item.displayTitle,
                                share = shareName,
                                host = settings.host
                            )
                        }
                    }.getOrElse { error ->
                        PlayerLaunchResult.Failed(error.message ?: "Playback failed")
                    }
                },
                onFailure = { error ->
                    PlayerLaunchResult.Failed(error.message ?: "Unable to build playback URL")
                }
            )
            when (result) {
                PlayerLaunchResult.Success -> Unit
                PlayerLaunchResult.NotInstalled -> _state.update {
                    it.copy(
                        showVlcMissing = true,
                        message = "Selected player is not installed — pick another in Settings"
                    )
                }
                is PlayerLaunchResult.Failed -> _state.update { it.copy(message = result.message) }
            }
    }

    fun openArchiveExtract(item: MediaCardItem) {
        if (!NasPaths.isArchiveFile(item.entry.name)) return
        dismissItemOptions()
        val current = _archiveExtract.value
        if (current.running) {
            // Keep the in-flight NAS job; just reopen the progress UI.
            _archiveExtract.update { it.copy(minimized = false) }
            if (current.item?.entry?.path != item.entry.path) {
                _state.update { it.copy(message = "Extract already running — hide it to browse") }
            }
            return
        }
        extractJob?.cancel()
        _archiveExtract.value = ArchiveExtractUi(item = item, confirming = true)
    }

    fun dismissArchiveExtract() {
        if (_archiveExtract.value.running) {
            minimizeArchiveExtract()
            return
        }
        extractJob?.cancel()
        _archiveExtract.value = ArchiveExtractUi()
    }

    fun minimizeArchiveExtract() {
        if (!_archiveExtract.value.running) return
        _archiveExtract.update { it.copy(minimized = true) }
    }

    fun expandArchiveExtract() {
        if (_archiveExtract.value.item == null) return
        _archiveExtract.update { it.copy(minimized = false) }
    }

    fun confirmArchiveExtract() {
        val item = _archiveExtract.value.item ?: return
        if (_archiveExtract.value.running) return
        extractJob?.cancel()
        extractJob = viewModelScope.launch {
            _archiveExtract.value = ArchiveExtractUi(
                item = item,
                confirming = false,
                running = true,
                minimized = false,
                progress = 0f,
                status = "Starting extract…",
            )
            val settings = _state.value.settings
            val (shareName, _) = parseVideoRoot(item.videoRoot ?: _state.value.selectedShare)
            val result = nasRepository.extractArchive(
                settings = settings,
                shareName = shareName,
                relativeArchivePath = item.entry.path,
            ) { progress, status ->
                _archiveExtract.update {
                    it.copy(progress = progress, status = status, error = null)
                }
            }
            result.fold(
                onSuccess = {
                    _archiveExtract.value = ArchiveExtractUi()
                    _state.update { it.copy(message = "Extracted ${item.entry.name}") }
                    refresh()
                },
                onFailure = { error ->
                    _archiveExtract.value = ArchiveExtractUi(
                        item = item,
                        confirming = false,
                        running = false,
                        minimized = false,
                        progress = 0f,
                        status = "",
                        error = error.message ?: "Extract failed",
                    )
                },
            )
        }
    }

    fun onBrowserResumed() {
        resumeMonitor.captureOnce()
        // Refresh list so resume bars update after returning from VLC
        if (!_state.value.searchOpen && !_state.value.loading && _state.value.items.isNotEmpty()) {
            refresh()
        }
    }

    fun needsNotificationAccess(): Boolean = !resumeMonitor.isNotificationAccessEnabled()

    fun refresh() {
        refreshWithPath(listPathUnderShare())
    }

    /** Share name + folder under that share for the configured video root. */
    private fun parseVideoRoot(selected: String): Pair<String, String> {
        val parsed = NasPaths.parseFolderPath(selected)
        return parsed ?: ("download" to "")
    }

    /** pathStack is relative to the configured root folder (not the SMB share root). */
    private fun listPathUnderShare(): String {
        val (_, rootFolder) = parseVideoRoot(_state.value.selectedShare)
        val beneath = _state.value.pathStack.joinToString("/")
        return when {
            rootFolder.isBlank() -> beneath
            beneath.isBlank() -> rootFolder
            else -> "$rootFolder/$beneath"
        }
    }

    private fun relativeToVideoRoot(entryPath: String): List<String> =
        relativeToVideoRootFor(_state.value.selectedShare, entryPath)

    private fun relativeToVideoRootFor(selected: String, entryPath: String): List<String> {
        val (_, rootFolder) = parseVideoRoot(selected)
        val entryParts = entryPath.split('/').filter { it.isNotBlank() }
        val rootParts = rootFolder.split('/').filter { it.isNotBlank() }
        return if (
            rootParts.isNotEmpty() &&
            entryParts.size >= rootParts.size &&
            entryParts.take(rootParts.size).map { it.lowercase() } == rootParts.map { it.lowercase() }
        ) {
            entryParts.drop(rootParts.size)
        } else {
            entryParts
        }
    }

    private fun refreshWithPath(path: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val settings = _state.value.settings
            val shareSnapshot = _state.value.selectedShare
            val pathStackSnapshot = _state.value.pathStack
            val (shareName, _) = parseVideoRoot(shareSnapshot)
            val locationKey = "$shareSnapshot\u0000${pathStackSnapshot.joinToString("/")}"
            // Soft refresh only when staying in the same folder (resume bars / re-enrich).
            // Navigating must not keep the previous folder's cards or preview art.
            val soft = _state.value.items.isNotEmpty() &&
                _state.value.error == null &&
                listedLocationKey == locationKey
            _state.update {
                if (soft) it.copy(error = null)
                else it.copy(loading = true, error = null, items = emptyList())
            }

            if (settings.password.isBlank()) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Open settings and enter the NAS password",
                        items = emptyList()
                    )
                }
                return@launch
            }

            // Fetch Trakt history in parallel with the NAS listing so the file list
            // can appear as soon as the folder returns (art/metadata fill in after).
            // While controlling a room, skip Trakt/TMDB entirely — the tablet would
            // share the same API keys as the TV and stampede rate limits (429), which
            // clears art on both devices. Play-to-TV only needs the NAS file list.
            val controllingRemote =
                com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.isControllingRemote()
            val historyDeferred = async {
                if (controllingRemote) {
                    TraktHistory()
                } else {
                    runCatching {
                        traktRepository.loadHistory(
                            clientId = settings.traktClientId,
                            accessToken = settings.traktAccessToken.ifBlank { null },
                            slug = settings.traktSlug
                        )
                    }.getOrDefault(TraktHistory())
                }
            }

            val listed = nasRepository.list(settings, shareName, path)
            listed.fold(
                onSuccess = { entries ->
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@fold
                    val placeholders = entries.map { placeholderCard(it) }
                    listedLocationKey = locationKey
                    _state.update {
                        it.copy(loading = false, items = placeholders, error = null)
                    }
                    history = historyDeferred.await()
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@fold
                    val videoPaths = entries
                        .filter { !it.isDirectory && NasPaths.isVideoFile(it.name) }
                        .map { it.path }
                    runCatching {
                        progressSync.mergeFolder(settings, shareName, path, videoPaths)
                    }
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@fold
                    if (controllingRemote) return@fold
                    enrichProgressively(entries, settings, shareSnapshot, pathStackSnapshot)
                },
                onFailure = { error ->
                    historyDeferred.cancel()
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@fold
                    _state.update {
                        it.copy(
                            loading = false,
                            items = emptyList(),
                            error = NasConnectionErrors.friendly(error, settings)
                        )
                    }
                }
            )
        }
    }

    private fun isBrowseTarget(share: String, pathStack: List<String>): Boolean {
        val s = _state.value
        return s.selectedShare.equals(share, ignoreCase = true) && s.pathStack == pathStack
    }

    /** Immediate name/type card — no Trakt/TMDB wait. */
    private fun placeholderCard(entry: SmbEntry): MediaCardItem {
        if (entry.isDirectory) {
            return MediaCardItem(
                entry = entry,
                displayTitle = cleanFolderDisplayName(entry.name),
                line1 = "",
                line2 = "",
                line3 = "",
                fanartUrl = null,
                posterUrl = null,
                overview = null,
            )
        }
        if (NasPaths.isArchiveFile(entry.name)) {
            return MediaCardItem(
                entry = entry,
                displayTitle = entry.name,
                line1 = "Archive",
                line2 = "Hold OK → Extract",
                line3 = entry.name,
                fanartUrl = null,
                posterUrl = null,
                overview = null,
            )
        }
        val quality = FilenameParser.qualityTags(entry.name, entry.path)
        val parsed = FilenameParser.parse(entry.name, entry.path)
        return MediaCardItem(
            entry = entry,
            displayTitle = parsed.searchQuery.ifBlank {
                entry.name.substringBeforeLast('.').ifBlank { entry.name }
            },
            line1 = "",
            line2 = "",
            line3 = entry.name,
            fanartUrl = null,
            posterUrl = null,
            overview = null,
            resolutionLabel = quality.resolutionLabel,
            isHdr = quality.isHdr,
            fpsLabel = quality.fpsLabel,
        )
    }

    private suspend fun enrichProgressively(
        entries: List<SmbEntry>,
        settings: AppSettings,
        shareSnapshot: String,
        pathStackSnapshot: List<String>,
    ) = coroutineScope {
        val semaphore = Semaphore(2)
        entries.map { entry ->
            async {
                semaphore.withPermit {
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@withPermit
                    val card = if (entry.isDirectory) {
                        enrichFolder(entry, settings)
                    } else {
                        enrichFile(entry, settings)
                    }
                    if (!isBrowseTarget(shareSnapshot, pathStackSnapshot)) return@withPermit
                    replaceBrowseItem(entry.path, card)
                }
            }
        }.awaitAll()
    }

    private fun replaceBrowseItem(path: String, card: MediaCardItem) {
        _state.update { state ->
            val idx = state.items.indexOfFirst { it.entry.path == path }
            if (idx < 0) state
            else state.copy(items = state.items.toMutableList().also { it[idx] = card })
        }
    }

    private suspend fun enrichFolder(entry: SmbEntry, settings: AppSettings): MediaCardItem {
        metadataOverrides.getForcedForFolder(entry.path)?.let { forced ->
            return forcedFolderCard(entry, forced, settings)
        }

        if (metadataOverrides.isCleared(entry.path)) {
            return MediaCardItem(
                entry = entry,
                displayTitle = cleanFolderDisplayName(entry.name),
                line1 = "Category",
                line2 = "Metadata cleared",
                line3 = entry.name,
                fanartUrl = null,
                posterUrl = null,
                overview = null,
                metadataCleared = true,
            )
        }

        // Top-level folders under /video are category bins (Movies, TV, …).
        if (isVideoShareCategoryFolder()) {
            return genericFolderCard(entry, category = true)
        }

        // Prefer folder-name match, then any video inside (index → live NAS list).
        // Never leave a show/movie pack blank just because Video Station didn't index /download.
        val art = lookupFolderArtwork(entry.name, entry.path, settings)
            ?: sampleChildFolderArtwork(entry, settings)
            ?: sampleNasChildFolderArtwork(entry, settings)

        if (art == null) {
            return genericFolderCard(entry, category = false)
        }

        return MediaCardItem(
            entry = entry,
            displayTitle = folderTitleWithSeason(art.title, entry.name),
            line1 = "",
            line2 = art.subtitle,
            line3 = "",
            fanartUrl = art.fanartUrl,
            posterUrl = art.posterUrl,
            overview = art.overview,
        )
    }

    private suspend fun forcedFolderCard(
        entry: SmbEntry,
        forced: ForcedMetadata,
        settings: AppSettings,
    ): MediaCardItem {
        val images = runCatching {
            tmdbRepository.images(
                apiKey = settings.tmdbApiKey,
                readToken = settings.tmdbReadToken,
                mediaType = forced.mediaType,
                tmdbId = forced.tmdbId,
            )
        }.getOrNull()
        return MediaCardItem(
            entry = entry,
            displayTitle = folderTitleWithSeason(forced.title, entry.name),
            line1 = "",
            line2 = forced.year?.toString().orEmpty(),
            line3 = "",
            fanartUrl = images?.fanartUrl,
            posterUrl = images?.posterUrl,
            overview = forced.overview,
        )
    }

    private fun genericFolderCard(entry: SmbEntry, category: Boolean = false): MediaCardItem =
        MediaCardItem(
            entry = entry,
            displayTitle = cleanFolderDisplayName(entry.name),
            line1 = if (category) "Category" else "",
            line2 = "",
            line3 = "",
            fanartUrl = null,
            posterUrl = null,
            overview = null,
        )

    /** Immediate children of the `video` share root are library categories, not show folders. */
    private fun isVideoShareCategoryFolder(): Boolean {
        val share = _state.value.selectedShare.trim('/').substringBefore('/').lowercase()
        return share == "video" && _state.value.pathStack.isEmpty()
    }

    private fun indexedVideosUnder(folder: SmbEntry): List<com.vizvag.shieldvideo.data.index.IndexedVideo> {
        val share = _state.value.selectedShare.trim('/').substringBefore('/').lowercase()
        val folderNorm = normalizeIndexRelativePath(folder.path, share)
        if (folderNorm.isBlank() && folder.name.isBlank()) return emptyList()
        val folderName = folder.name.trim('/')
        return videoIndex.currentSnapshot().entries
            .asSequence()
            .filter { !it.isDirectory }
            .filter { indexed ->
                indexed.share.equals(share, ignoreCase = true) ||
                    indexed.root.trim('/').equals(share, ignoreCase = true)
            }
            .filter { indexed ->
                val p = indexed.path.replace('\\', '/').trim('/').trimEnd('/')
                when {
                    folderNorm.isNotBlank() && (
                        p.startsWith("$folderNorm/", ignoreCase = true) ||
                            p.substringBeforeLast('/').equals(folderNorm, ignoreCase = true)
                        ) -> true
                    // Same folder name only within this share (never cross /video ↔ /download).
                    folderName.isNotBlank() && (
                        p.startsWith("$folderName/", ignoreCase = true) ||
                            p.substringBeforeLast('/').equals(folderName, ignoreCase = true)
                        ) -> true
                    else -> false
                }
            }
            .take(48)
            .toList()
    }

    /** Strip share / root prefixes so browse paths match index-relative paths. */
    private fun normalizeIndexRelativePath(rawPath: String, share: String): String {
        var p = rawPath.replace('\\', '/').trim('/')
        if (p.isBlank() || share.isBlank()) return p
        val shareLc = share.lowercase()
        val prefixes = listOf(
            shareLc,
            "/$shareLc",
            "volume1/$shareLc",
            "/volume1/$shareLc",
        )
        for (prefix in prefixes) {
            if (p.equals(prefix, ignoreCase = true)) return ""
            if (p.startsWith("$prefix/", ignoreCase = true)) {
                p = p.substring(prefix.length + 1).trim('/')
                break
            }
        }
        return p
    }

    private data class FolderArtwork(
        val title: String,
        val subtitle: String = "",
        val fanartUrl: String?,
        val posterUrl: String?,
        val overview: String?,
    )

    /** Match the folder name as a show/movie (e.g. season packs, release folders). */
    private suspend fun lookupFolderArtwork(
        folderName: String,
        folderPath: String,
        settings: AppSettings,
    ): FolderArtwork? {
        val queries = folderLookupQueries(folderName, folderPath)
        for (query in queries) {
            if (query.searchQuery.isBlank()) continue
            // Season-only folder names are not searchable titles.
            if (FilenameParser.isSeasonOrJunkFolderName(query.searchQuery) &&
                query.searchQuery.equals(folderName, ignoreCase = true)
            ) {
                continue
            }
            val match = runCatching {
                traktRepository.lookup(settings.traktClientId, query)
            }.getOrNull()
            val images = if (match?.tmdbId != null) {
                runCatching {
                    tmdbRepository.images(
                        apiKey = settings.tmdbApiKey,
                        readToken = settings.tmdbReadToken,
                        mediaType = match.mediaType,
                        tmdbId = match.tmdbId,
                    )
                }.getOrNull()
            } else null
            if (!images?.posterUrl.isNullOrBlank() || !images?.fanartUrl.isNullOrBlank()) {
                return FolderArtwork(
                    title = match!!.title,
                    subtitle = match.year?.toString().orEmpty(),
                    fanartUrl = images?.fanartUrl,
                    posterUrl = images?.posterUrl,
                    overview = match.overview,
                )
            }
            // Trakt often 429s when enriching a whole download share — fall back to TMDB search.
            val preferTv = query.kind != com.vizvag.shieldvideo.data.trakt.MediaKind.MOVIE
            val hit = runCatching {
                tmdbRepository.searchTitle(
                    apiKey = settings.tmdbApiKey,
                    readToken = settings.tmdbReadToken,
                    query = query.searchQuery,
                    preferTv = preferTv,
                    year = query.year,
                )
            }.getOrNull() ?: continue
            val tmdbImages = runCatching {
                tmdbRepository.images(
                    apiKey = settings.tmdbApiKey,
                    readToken = settings.tmdbReadToken,
                    mediaType = hit.mediaType,
                    tmdbId = hit.tmdbId,
                )
            }.getOrNull()
            if (tmdbImages?.posterUrl.isNullOrBlank() && tmdbImages?.fanartUrl.isNullOrBlank()) continue
            return FolderArtwork(
                title = hit.title,
                subtitle = hit.year?.toString().orEmpty(),
                fanartUrl = tmdbImages?.fanartUrl,
                posterUrl = tmdbImages?.posterUrl,
                overview = hit.overview,
            )
        }
        return null
    }

    /** Several increasingly aggressive title guesses for scene-named folders. */
    private fun folderLookupQueries(
        folderName: String,
        folderPath: String,
    ): List<com.vizvag.shieldvideo.data.trakt.ParsedMediaQuery> {
        val primary = FilenameParser.parseFolder(folderName, folderPath)
        val cleaned = cleanFolderDisplayName(folderName)
            .replace(Regex("""(?i)\bS\d{1,2}(?:\s*[-–]\s*S\d{1,2})?\b"""), " ")
            .replace(Regex("""(?i)\bSeason\s*\d+\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val fallback = if (cleaned.isNotBlank() &&
            !cleaned.equals(primary.searchQuery, ignoreCase = true) &&
            !FilenameParser.isSeasonOrJunkFolderName(cleaned)
        ) {
            com.vizvag.shieldvideo.data.trakt.ParsedMediaQuery(
                searchQuery = cleaned,
                kind = com.vizvag.shieldvideo.data.trakt.MediaKind.UNKNOWN,
            )
        } else null
        // Cut at first quality / codec token if still noisy
        val cut = Regex(
            """(?i)^(.+?)(?:\s+(?:1080p|720p|2160p|4k|uhd|bluray|web[- ]?dl|webrip|hdtv|proper|repack)\b).*""",
        ).find(folderName.replace('.', ' ').replace('_', ' '))
            ?.groupValues?.getOrNull(1)
            ?.replace(Regex("""(?i)\bS\d{1,2}(?:E\d{1,3})?\b|\bSeason\s*\d+\b|\bcomplete\b|\bpack\b"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        val cutQuery = if (!cut.isNullOrBlank() &&
            cut.length >= 2 &&
            !FilenameParser.isSeasonOrJunkFolderName(cut) &&
            !cut.equals(primary.searchQuery, ignoreCase = true) &&
            !cut.equals(cleaned, ignoreCase = true)
        ) {
            com.vizvag.shieldvideo.data.trakt.ParsedMediaQuery(
                searchQuery = cut,
                kind = com.vizvag.shieldvideo.data.trakt.MediaKind.UNKNOWN,
            )
        } else null
        // Season folder under a show: `Show Name/Season 01` → query the show parent.
        val pathParts = folderPath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val parentShowQuery = if (
            FilenameParser.isSeasonOrJunkFolderName(folderName) &&
            pathParts.size >= 2
        ) {
            pathParts.asReversed().drop(1).firstOrNull { !FilenameParser.isSeasonOrJunkFolderName(it) }
                ?.let { parent ->
                    val parsed = FilenameParser.parseFolder(parent, folderPath)
                    parsed.takeIf { it.searchQuery.isNotBlank() }
                }
        } else null
        return listOfNotNull(primary, fallback, cutQuery, parentShowQuery)
            .filter { it.searchQuery.isNotBlank() && !FilenameParser.isSeasonOrJunkFolderName(it.searchQuery) }
            .distinctBy { it.searchQuery.lowercase() }
    }

    /**
     * Use a video inside the folder for Trakt/TMDB art when the folder name alone fails.
     */
    private suspend fun sampleChildFolderArtwork(
        folder: SmbEntry,
        settings: AppSettings,
        children: List<com.vizvag.shieldvideo.data.index.IndexedVideo> = indexedVideosUnder(folder),
    ): FolderArtwork? {
        for (child in children.take(12)) {
            val card = enrichFile(child.toEntry(), settings)
            if (!card.posterUrl.isNullOrBlank() || !card.fanartUrl.isNullOrBlank()) {
                return FolderArtwork(
                    title = card.displayTitle,
                    subtitle = card.line2,
                    fanartUrl = card.fanartUrl,
                    posterUrl = card.posterUrl,
                    overview = card.overview,
                )
            }
        }
        return null
    }

    /**
     * /download is often missing from Video Station's index — list the folder on the NAS
     * and resolve art from the first matching video (or nested season folders).
     */
    private suspend fun sampleNasChildFolderArtwork(
        folder: SmbEntry,
        settings: AppSettings,
    ): FolderArtwork? {
        val (shareName, _) = parseVideoRoot(_state.value.selectedShare)
        val folderPath = pathRelativeToShare(folder.path, shareName)
        val listed = nasRepository.list(settings, shareName, folderPath).getOrNull().orEmpty()
        val videos = listed.filter { !it.isDirectory && NasPaths.isVideoFile(it.name) }
        artworkFromEntries(videos, settings)?.let { return it }

        // Nested: ShowName/Season 01/*.mkv or ShowName/S01/*.mkv (and one more level).
        val subdirs = listed.filter { it.isDirectory }
            .sortedByDescending { FilenameParser.isSeasonOrJunkFolderName(it.name) }
            .take(8)
        for (sub in subdirs) {
            val subPath = pathRelativeToShare(sub.path, shareName)
            val nested = nasRepository.list(settings, shareName, subPath)
                .getOrNull()
                .orEmpty()
            val nestedVideos = nested.filter { !it.isDirectory && NasPaths.isVideoFile(it.name) }
            artworkFromEntries(nestedVideos, settings)?.let { return it }

            // Season → episode files two levels down, or child pack folder.
            for (deep in nested.filter { it.isDirectory }.take(4)) {
                val deepPath = pathRelativeToShare(deep.path, shareName)
                val deepListed = nasRepository.list(settings, shareName, deepPath)
                    .getOrNull()
                    .orEmpty()
                val deepVideos = deepListed.filter { !it.isDirectory && NasPaths.isVideoFile(it.name) }
                artworkFromEntries(deepVideos, settings)?.let { return it }
            }

            // Subfolder name itself may be the show (e.g. parent is "TV", child is the pack)
            if (!FilenameParser.isSeasonOrJunkFolderName(sub.name)) {
                lookupFolderArtwork(sub.name, sub.path, settings)?.let { return it }
            }
        }

        // Last resort: folder name may be noisy; try cleaned show title only.
        if (FilenameParser.isSeasonOrJunkFolderName(folder.name)) {
            val showParent = folderPath.substringBeforeLast('/', missingDelimiterValue = "")
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() && !FilenameParser.isSeasonOrJunkFolderName(it) }
            if (showParent != null) {
                lookupFolderArtwork(showParent, folderPath, settings)?.let { return it }
            }
        }
        return null
    }

    /** Share-relative path; strips a duplicated/cased share prefix if present. */
    private fun pathRelativeToShare(rawPath: String, shareName: String): String {
        var p = rawPath.trim('/').replace('\\', '/')
        val share = shareName.trim('/')
        if (share.isBlank() || p.isBlank()) return p
        if (p.equals(share, ignoreCase = true)) return ""
        if (p.startsWith("$share/", ignoreCase = true)) {
            p = p.substring(share.length + 1).trim('/')
        }
        return p
    }

    private suspend fun artworkFromEntries(
        entries: List<SmbEntry>,
        settings: AppSettings,
    ): FolderArtwork? {
        for (entry in entries.take(10)) {
            val card = enrichFile(entry, settings)
            if (!card.posterUrl.isNullOrBlank() || !card.fanartUrl.isNullOrBlank()) {
                return FolderArtwork(
                    title = card.displayTitle,
                    subtitle = card.line2,
                    fanartUrl = card.fanartUrl,
                    posterUrl = card.posterUrl,
                    overview = card.overview,
                )
            }
        }
        return null
    }

    private fun parseFolderQuery(
        folderName: String,
        folderPath: String,
    ): com.vizvag.shieldvideo.data.trakt.ParsedMediaQuery {
        return FilenameParser.parseFolder(folderName, folderPath)
    }

    private fun cleanFolderDisplayName(raw: String): String {
        val stripped = raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(
                Regex(
                    """(?i)\b(S\d{1,2}\s?E\d{1,3}|complete|pack|1080p|720p|2160p|4k|uhd|bluray|web[- ]?dl|webrip|web|hdtv|amzn|nf|dsnp|hulu|atvp|hmax|x264|x265|hevc|h\.?264|h\.?265|avc|ddp\d*|atmos|truehd|eac3|ac3|hdr10?\+?|proper|repack|multi|remux|d3g)\b""",
                ),
                " ",
            )
            .replace(Regex("""(?i)-[a-z0-9]{2,10}$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return stripped.ifBlank { raw }
    }

    /** Trakt/show title plus season label from the folder name when present (e.g. "The Musketeers S01"). */
    private fun folderTitleWithSeason(preferredTitle: String, folderName: String): String {
        val season = extractSeasonLabel(folderName) ?: return preferredTitle
        if (preferredTitle.contains(Regex("""(?i)\bS\d{1,2}\b""")) ||
            preferredTitle.contains(Regex("""(?i)\bSeason\s*\d+"""))
        ) {
            return preferredTitle
        }
        return "$preferredTitle $season".replace(Regex("""\s+"""), " ").trim()
    }

    private fun extractSeasonLabel(folderName: String): String? {
        val spaced = folderName.replace('.', ' ').replace('_', ' ')
        val range = Regex("""(?i)\bS(\d{1,2})\s*[-–]\s*S(\d{1,2})\b""").find(spaced)
        if (range != null) {
            return "S${range.groupValues[1].padStart(2, '0')}-S${range.groupValues[2].padStart(2, '0')}"
        }
        val seasonWord = Regex("""(?i)\bSeason\s*(\d{1,2})\b""").find(spaced)
        if (seasonWord != null) {
            return "S${seasonWord.groupValues[1].padStart(2, '0')}"
        }
        val code = Regex("""(?i)\bS(\d{1,2})\b(?!\s?E\d)""").find(spaced) ?: return null
        return "S${code.groupValues[1].padStart(2, '0')}"
    }

    private suspend fun enrichFile(entry: SmbEntry, settings: AppSettings): MediaCardItem {
        if (NasPaths.isArchiveFile(entry.name)) {
            return MediaCardItem(
                entry = entry,
                displayTitle = entry.name,
                line1 = "Archive",
                line2 = "Hold OK → Extract",
                line3 = entry.name,
                fanartUrl = null,
                posterUrl = null,
                overview = null,
            )
        }
        val quality = FilenameParser.qualityTags(entry.name, entry.path)
        val forced = metadataOverrides.getForced(entry.path)
        if (forced == null && metadataOverrides.isCleared(entry.path)) {
            return MediaCardItem(
                entry = entry,
                displayTitle = entry.name.substringBeforeLast('.').ifBlank { entry.name },
                line1 = "",
                line2 = "",
                line3 = entry.name,
                fanartUrl = null,
                posterUrl = null,
                overview = null,
                resolutionLabel = quality.resolutionLabel,
                isHdr = quality.isHdr,
                fpsLabel = quality.fpsLabel,
                metadataCleared = true
            )
        }

        val parsed = FilenameParser.parse(entry.name, entry.path)
        var match: TraktMatch? = if (forced != null) {
            TraktMatch(
                title = forced.title,
                year = forced.year,
                overview = forced.overview,
                rating = null,
                mediaType = forced.mediaType,
                tmdbId = forced.tmdbId,
                traktId = forced.traktId,
            )
        } else {
            runCatching {
                traktRepository.lookup(settings.traktClientId, parsed)
            }.getOrNull()
        }
        if (match == null && forced == null && parsed.searchQuery.isNotBlank()) {
            val preferTv = parsed.kind != MediaKind.MOVIE
            val hit = runCatching {
                tmdbRepository.searchTitle(
                    apiKey = settings.tmdbApiKey,
                    readToken = settings.tmdbReadToken,
                    query = parsed.showTitle?.takeIf { it.isNotBlank() } ?: parsed.searchQuery,
                    preferTv = preferTv,
                    year = parsed.year,
                )
            }.getOrNull()
            if (hit != null) {
                match = TraktMatch(
                    title = hit.title,
                    year = hit.year,
                    overview = hit.overview,
                    rating = null,
                    mediaType = hit.mediaType,
                    tmdbId = hit.tmdbId,
                    season = parsed.season,
                    episode = parsed.episode,
                )
            }
        }

        val season = match?.season ?: parsed.season
        val episode = match?.episode ?: parsed.episode

        // Always resolve episode title/overview from Trakt (then TMDB) when S/E are known.
        // Folder assignment only pins the show — it must not skip episode metadata.
        if (match != null &&
            match.mediaType != "movie" &&
            season != null &&
            episode != null &&
            match.episodeTitle.isNullOrBlank()
        ) {
            match = runCatching {
                traktRepository.withEpisodeDetails(
                    settings.traktClientId,
                    match!!,
                    season,
                    episode,
                )
            }.getOrDefault(match)
            if (match!!.episodeTitle.isNullOrBlank() && match.tmdbId != null) {
                val tmdbEp = runCatching {
                    tmdbRepository.episode(
                        apiKey = settings.tmdbApiKey,
                        readToken = settings.tmdbReadToken,
                        tmdbShowId = match.tmdbId,
                        season = season,
                        episode = episode,
                    )
                }.getOrNull()
                if (tmdbEp != null) {
                    match = match.copy(
                        episodeTitle = tmdbEp.title ?: match.episodeTitle,
                        overview = tmdbEp.overview ?: match.overview,
                        airedDate = tmdbEp.airDate ?: match.airedDate,
                        rating = tmdbEp.rating ?: match.rating,
                        runtimeMinutes = tmdbEp.runtimeMinutes ?: match.runtimeMinutes,
                        season = season,
                        episode = episode,
                    )
                }
            }
        }

        val images = if (match?.tmdbId != null) {
            runCatching {
                tmdbRepository.images(
                    apiKey = settings.tmdbApiKey,
                    readToken = settings.tmdbReadToken,
                    mediaType = match.mediaType,
                    tmdbId = match.tmdbId
                )
            }.getOrNull()
        } else null

        val title = match?.title ?: parsed.searchQuery.ifBlank { entry.name }
        val traktWatched = when (match?.mediaType) {
            "movie" -> history.isMovieWatched(match.traktId)
            else -> history.isEpisodeWatched(match?.traktId, season, episode)
        }
        val localMarker = resumeStore.getIncludingWatched(entry.path)
        val watched = traktWatched || localMarker?.watched == true
        val traktResumeMs = when (match?.mediaType) {
            "movie" -> history.resumeMs(null, null, null, match.traktId)
            else -> history.resumeMs(match?.traktId, season, episode, null)
        }?.takeUnless { watched }

        val local = localMarker?.takeIf { it.isMeaningful }
        val resumeMs = when {
            watched -> null
            local != null -> local.positionMs
            else -> traktResumeMs
        }

        val runtimeMs = match?.runtimeMinutes?.takeIf { it > 0 }?.times(60_000L)
            ?: localMarker?.durationMs?.takeIf { it > 0 }
        val runtimeLabel = match?.runtimeMinutes?.takeIf { it > 0 }?.let { formatRuntimeMinutes(it) }
            ?: localMarker?.durationMs?.takeIf { it > 0 }?.let { formatRuntimeFromMs(it) }
        val resumeProgress = when {
            resumeMs == null -> null
            local?.progress != null -> local.progress
            runtimeMs != null && runtimeMs > 0 ->
                (resumeMs.toFloat() / runtimeMs.toFloat()).coerceIn(0.02f, 0.98f)
            else -> null
        }

        val metaBits = buildList {
            when {
                !match?.airedDate.isNullOrBlank() -> add(match!!.airedDate!!)
                match?.mediaType == "movie" -> match.year?.let { add(it.toString()) }
                    ?: parsed.year?.let { add(it.toString()) }
                match == null -> parsed.year?.let { add(it.toString()) }
            }
            match?.rating?.let { add("%.1f★".format(it)) }
            if (watched) add("Watched")
        }

        // Trakt → TMDB → filename only as last resort
        val episodeTitle = match?.episodeTitle?.takeIf { it.isNotBlank() }
            ?: parsed.episodeTitle?.takeIf { it.isNotBlank() }
        val line1 = formatEpisodeLabel(season, episode, episodeTitle).orEmpty()
        val line2 = metaBits.joinToString("  ·  ")
        val line3 = match?.overview?.take(90)?.let { if (it.length == 90) "$it…" else it }
            ?: entry.name

        return MediaCardItem(
            entry = entry,
            displayTitle = title,
            line1 = line1,
            line2 = line2,
            line3 = line3,
            fanartUrl = images?.fanartUrl,
            posterUrl = images?.posterUrl,
            overview = match?.overview,
            watched = watched,
            resumePositionMs = resumeMs,
            resumeProgress = resumeProgress,
            resolutionLabel = quality.resolutionLabel,
            isHdr = quality.isHdr,
            fpsLabel = quality.fpsLabel,
            runtimeLabel = runtimeLabel,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            metadataCleared = false
        )
    }

    private fun formatRuntimeMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    private fun formatRuntimeFromMs(ms: Long): String {
        val minutes = (ms / 60_000L).toInt().coerceAtLeast(1)
        return formatRuntimeMinutes(minutes)
    }

    companion object {
        /** e.g. `New Zealand Town (Series 2, Episode 8)` */
        fun formatEpisodeLabel(season: Int?, episode: Int?, episodeTitle: String?): String? {
            val location = when {
                season != null && episode != null -> "Series $season, Episode $episode"
                season != null -> "Series $season"
                episode != null -> "Episode $episode"
                else -> null
            }
            val title = episodeTitle?.takeIf { it.isNotBlank() }
            return when {
                title != null && location != null -> "$title ($location)"
                title != null -> title
                location != null -> location
                else -> null
            }
        }
    }
}

class BrowserViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val vlcLauncher: VlcLauncher,
    private val resumeStore: LocalResumeStore,
    private val resumeMonitor: ResumeMonitor,
    private val nasWatchHistory: NasWatchHistoryStore,
    private val metadataOverrides: MetadataOverrideStore,
    private val videoIndex: VideoIndexController,
    private val progressSync: NasProgressSync,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BrowserViewModel(
            settingsRepository,
            nasRepository,
            traktRepository,
            tmdbRepository,
            vlcLauncher,
            resumeStore,
            resumeMonitor,
            nasWatchHistory,
            metadataOverrides,
            videoIndex,
            progressSync,
        ) as T
    }
}
