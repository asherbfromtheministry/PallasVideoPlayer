package com.vizvag.shieldvideo.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.podcast.PodcastEpisode
import com.vizvag.shieldvideo.data.podcast.PodcastEpisodeProgress
import com.vizvag.shieldvideo.data.podcast.PodcastEpisodeSort
import com.vizvag.shieldvideo.data.podcast.PodcastRepository
import com.vizvag.shieldvideo.data.podcast.PodcastShow
import com.vizvag.shieldvideo.data.podcast.PodcastShowSort
import com.vizvag.shieldvideo.playback.podcast.PodcastPlaybackController
import com.vizvag.shieldvideo.playback.podcast.PodcastPlaybackState
import com.vizvag.shieldvideo.playback.remote.RemotePlayBridge
import com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode
import com.vizvag.shieldvideo.playback.remote.RemoteStatus
import com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller
import com.vizvag.shieldvideo.playback.remote.RemoteTargetStore
import com.vizvag.shieldvideo.playback.remote.TransportAction
import com.vizvag.shieldvideo.ui.notice.AppNoticeBus
import com.vizvag.shieldvideo.ui.notice.AppNoticeKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

data class PodcastRefreshOverlay(
    val current: Int = 0,
    val total: Int = 0,
    val detail: String = "",
) {
    val headline: String
        get() = when {
            total > 0 -> "$current / $total podcasts updated"
            detail.isNotBlank() -> detail
            else -> "Loading podcasts…"
        }
}

data class PodcastUiState(
    val shows: List<PodcastShow> = emptyList(),
    /** null = all subscriptions (recent feed). */
    val selectedShow: PodcastShow? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    /** How many sorted episodes to paint; grows via [PodcastViewModel.loadMoreEpisodes]. */
    val visibleEpisodeCount: Int = PodcastViewModel.EPISODE_PAGE_SIZE,
    val loadingEpisodes: Boolean = false,
    val loadingCatalog: Boolean = false,
    val refreshing: Boolean = false,
    val refreshOverlay: PodcastRefreshOverlay? = null,
    val statusMessage: String? = null,
    val progress: Map<String, PodcastEpisodeProgress> = emptyMap(),
    val showSort: PodcastShowSort = PodcastShowSort.TITLE,
    val episodeSort: PodcastEpisodeSort = PodcastEpisodeSort.NEWEST,
) {
    val browsingAll: Boolean get() = selectedShow == null

    val isBusy: Boolean get() = refreshOverlay != null || loadingEpisodes || loadingCatalog || refreshing

    val showTitleById: Map<String, String>
        get() = shows.associate { it.id to it.title }

    val displayedShows: List<PodcastShow>
        get() = sortShows(shows, showSort, progress)

    val showGroups: List<Pair<String, List<PodcastShow>>>
        get() = when (showSort) {
            PodcastShowSort.GENRE -> displayedShows
                .groupBy { it.primaryGenre }
                .toList()
                .sortedBy { it.first.lowercase() }
            else -> listOf("" to displayedShows)
        }

    val sortedEpisodes: List<PodcastEpisode>
        get() = sortEpisodes(episodes, episodeSort, progress)

    val displayedEpisodes: List<PodcastEpisode>
        get() = sortedEpisodes.take(visibleEpisodeCount.coerceAtLeast(0))

    val hasMoreEpisodes: Boolean
        get() = visibleEpisodeCount < sortedEpisodes.size
}

fun sortShows(
    shows: List<PodcastShow>,
    sort: PodcastShowSort,
    progress: Map<String, PodcastEpisodeProgress>,
): List<PodcastShow> {
    val inProgressIds = progress.values
        .filter { it.inProgress }
        .mapNotNull { it.showId.takeIf { id -> id.isNotBlank() } }
        .toSet()
    return when (sort) {
        PodcastShowSort.TITLE -> shows.sortedBy { it.title.lowercase() }
        PodcastShowSort.RECENT -> shows.sortedWith(
            compareByDescending<PodcastShow> { it.latestEpisodeEpochMs }
                .thenBy { it.title.lowercase() },
        )
        PodcastShowSort.GENRE -> shows.sortedWith(
            compareBy<PodcastShow> { it.primaryGenre.lowercase() }
                .thenBy { it.title.lowercase() },
        )
        PodcastShowSort.IN_PROGRESS -> shows.sortedWith(
            compareByDescending<PodcastShow> { it.id in inProgressIds }
                .thenBy { it.title.lowercase() },
        )
    }
}

fun sortEpisodes(
    episodes: List<PodcastEpisode>,
    sort: PodcastEpisodeSort,
    progress: Map<String, PodcastEpisodeProgress>,
): List<PodcastEpisode> {
    return when (sort) {
        PodcastEpisodeSort.NEWEST -> episodes.sortedByDescending { it.publishEpochMs }
        PodcastEpisodeSort.OLDEST -> episodes.sortedBy { it.publishEpochMs }
        PodcastEpisodeSort.UNPLAYED -> episodes.sortedWith(
            compareBy<PodcastEpisode> { ep ->
                val p = progress[ep.guid]
                when {
                    p == null -> 0
                    p.completed -> 2
                    p.inProgress -> 1
                    else -> 0
                }
            }.thenByDescending { it.publishEpochMs },
        )
    }
}

class PodcastViewModel(
    private val repository: PodcastRepository,
    val playback: PodcastPlaybackController,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        PodcastUiState(
            showSort = repository.showSort,
            episodeSort = repository.episodeSort,
        ),
    )
    val ui: StateFlow<PodcastUiState> = _ui.asStateFlow()

    private val _remoteStatus = MutableStateFlow<RemoteStatus?>(null)
    val controllingRemote = RemoteTargetStore.target

    val playbackState: StateFlow<PodcastPlaybackState> = combine(
        playback.state,
        _remoteStatus,
        RemoteTargetStore.target,
    ) { local, remote, target ->
        when {
            target == null -> local
            remote != null && remote.mode == RemotePlaybackMode.Podcasts -> remote.toPodcastPlaybackState()
            else -> PodcastPlaybackState()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PodcastPlaybackState(),
    )

    private var progressJob: Job? = null
    private var loadJob: Job? = null
    /** Must be initialized before init{} — Main.immediate collectors can run during construction. */
    private val loadMutex = Mutex()
    /** Full episode set from the last catalog load (used to filter without re-fetching). */
    private var cachedEpisodes: List<PodcastEpisode> = emptyList()
    private var hasLoadedOnce = false

    init {
        viewModelScope.launch {
            RemoteStatusPoller.status.collect { _remoteStatus.value = it }
        }
        viewModelScope.launch {
            RemoteTargetStore.target.collect {
                val wasLoaded = hasLoadedOnce
                hasLoadedOnce = false
                cachedEpisodes = emptyList()
                // Only auto-reload when already on Podcasts and the room target flips.
                if (wasLoaded) {
                    if (!isRemoteSession() && hydrateFromSnapshot()) {
                        startCatalogLoad(forceNetwork = false, showOverlay = false)
                    } else {
                        startCatalogLoad(forceNetwork = false, showOverlay = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.progress.collect { map ->
                if (!isRemoteSession()) {
                    _ui.update { it.copy(progress = map) }
                }
            }
        }
        startProgressTicker()
    }

    fun isRemoteSession(): Boolean = RemoteTargetStore.isControllingRemote()

    /** Single entry from the Podcasts screen — loads once per open (ignored if already loaded). */
    fun onScreenOpened() {
        if (hasLoadedOnce || loadJob?.isActive == true) return
        if (!isRemoteSession() && hydrateFromSnapshot()) {
            // Paint last catalog immediately; refresh quietly in the background.
            startCatalogLoad(forceNetwork = false, showOverlay = false)
            return
        }
        startCatalogLoad(forceNetwork = false, showOverlay = true)
    }

    /** Manual Refresh button — always force-updates feeds with overlay progress. */
    fun refreshAllFeeds() {
        startCatalogLoad(forceNetwork = true, showOverlay = true)
    }

    private fun hydrateFromSnapshot(): Boolean {
        val snap = repository.loadCatalogSnapshot() ?: return false
        val shows = snap.shows.ifEmpty { repository.subscriptions.value }
        if (shows.isEmpty() || snap.episodes.isEmpty()) return false
        cachedEpisodes = snap.episodes
        val selected = _ui.value.selectedShow?.let { sel ->
            shows.firstOrNull { it.id == sel.id }
        }
        _ui.update {
            it.copy(
                shows = shows,
                selectedShow = selected,
                progress = repository.progress.value,
                loadingCatalog = false,
                loadingEpisodes = false,
                refreshing = false,
                refreshOverlay = null,
            )
        }
        publishEpisodesForSelection()
        hasLoadedOnce = true
        return true
    }

    private fun startCatalogLoad(forceNetwork: Boolean, showOverlay: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadMutex.withLock {
                runCatalogLoad(forceNetwork = forceNetwork, showOverlay = showOverlay)
            }
        }
    }

    private suspend fun runCatalogLoad(forceNetwork: Boolean, showOverlay: Boolean) {
        if (isRemoteSession()) {
            runRemoteCatalogLoad(forceNetwork = forceNetwork)
        } else {
            runLocalCatalogLoad(forceNetwork = forceNetwork, showOverlay = showOverlay)
        }
        hasLoadedOnce = true
    }

    private suspend fun setOverlay(current: Int, total: Int, detail: String = "") {
        _ui.update {
            it.copy(
                refreshOverlay = PodcastRefreshOverlay(current, total, detail),
                refreshing = true,
                statusMessage = null,
            )
        }
    }

    private suspend fun clearOverlay(status: String? = null) {
        _ui.update {
            it.copy(
                refreshOverlay = null,
                refreshing = false,
                loadingCatalog = false,
                loadingEpisodes = false,
                statusMessage = null,
            )
        }
        flash(status)
    }

    private fun flash(message: String?, kind: AppNoticeKind? = null) {
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return
        AppNoticeBus.show(
            message = msg,
            kind = kind ?: AppNoticeBus.inferKind(msg),
            title = "Podcasts",
        )
    }

    private suspend fun runLocalCatalogLoad(forceNetwork: Boolean, showOverlay: Boolean) {
        if (showOverlay) {
            setOverlay(0, 0, if (forceNetwork) "Refreshing feeds…" else "Loading subscriptions…")
        }
        var shows = repository.subscriptions.value
        if (shows.isEmpty()) {
            if (showOverlay) setOverlay(0, 0, "Importing OPML…")
            val imported = repository.importOpmlPreferNas()
            shows = repository.subscriptions.value
            if (shows.isEmpty()) {
                if (!showOverlay && cachedEpisodes.isNotEmpty()) {
                    // Keep the painted snapshot; OPML may be mid-import elsewhere.
                    return
                }
                _ui.update {
                    it.copy(
                        shows = emptyList(),
                        episodes = emptyList(),
                        selectedShow = null,
                    )
                }
                cachedEpisodes = emptyList()
                if (showOverlay) {
                    clearOverlay(
                        imported.exceptionOrNull()?.message
                            ?: "No subscriptions — import OPML in Settings",
                    )
                } else {
                    clearOverlay(null)
                }
                return
            }
        }

        val selected = _ui.value.selectedShow?.let { sel ->
            shows.firstOrNull { it.id == sel.id }
        }
        _ui.update {
            it.copy(
                shows = shows,
                selectedShow = selected,
                progress = repository.progress.value,
            )
        }

        val total = shows.size
        val merged = ArrayList<PodcastEpisode>(total * 20)
        shows.forEachIndexed { index, show ->
            coroutineContext.ensureActive()
            if (showOverlay) setOverlay(index + 1, total, show.title)
            val eps = runCatching {
                repository.episodesForShow(show, forceRefresh = forceNetwork)
            }.getOrDefault(emptyList())
            merged.addAll(eps)
        }
        coroutineContext.ensureActive()

        cachedEpisodes = merged
            .sortedByDescending { it.publishEpochMs }
            .take(ALL_EPISODES_CAP)
        // Prefer live subscription meta (images / latest) after feed parse.
        val paintedShows = repository.subscriptions.value.ifEmpty { shows }
        _ui.update {
            it.copy(
                shows = paintedShows,
                selectedShow = it.selectedShow?.let { sel ->
                    paintedShows.firstOrNull { s -> s.id == sel.id }
                },
            )
        }
        publishEpisodesForSelection()
        repository.saveCatalogSnapshot(paintedShows, cachedEpisodes)
        runCatching { ShieldVideoApp.instance.publishPodcastEpisodesToHa() }
        if (showOverlay) {
            clearOverlay(
                if (forceNetwork) "Updated $total shows" else null,
            )
        } else {
            _ui.update {
                it.copy(
                    refreshing = false,
                    loadingCatalog = false,
                    loadingEpisodes = false,
                )
            }
        }
    }

    private suspend fun runRemoteCatalogLoad(forceNetwork: Boolean) {
        val device = RemoteTargetStore.current() ?: return
        setOverlay(0, 0, if (forceNetwork) "Refreshing room feeds…" else "Loading room podcasts…")

        if (forceNetwork) {
            val refresh = ShieldVideoApp.instance.remoteClient.refreshPodcastFeeds(device)
            refresh.onFailure { err ->
                clearOverlay(err.message ?: "Room refresh failed")
                return
            }
            val shows = refresh.getOrNull()?.second?.mapNotNull { parseRemoteShow(it) }.orEmpty()
            _ui.update { it.copy(shows = shows, selectedShow = it.selectedShow?.let { sel ->
                shows.firstOrNull { s -> s.id == sel.id }
            }) }
            if (shows.isEmpty()) {
                cachedEpisodes = emptyList()
                publishEpisodesForSelection()
                clearOverlay("No subscriptions on room — import OPML in Settings")
                return
            }
            setOverlay(shows.size, shows.size, "Loading episodes…")
        } else {
            val subs = ShieldVideoApp.instance.remoteClient.podcastSubscriptions(device)
            if (subs.isFailure) {
                clearOverlay(friendlyRemoteError(subs.exceptionOrNull()))
                return
            }
            val shows = subs.getOrNull()?.mapNotNull { parseRemoteShow(it) }.orEmpty()
            _ui.update { it.copy(shows = shows, selectedShow = it.selectedShow?.let { sel ->
                shows.firstOrNull { s -> s.id == sel.id }
            }) }
            if (shows.isEmpty()) {
                // One-shot OPML import on the room when prefs are empty.
                setOverlay(0, 0, "Importing room subscriptions…")
                val imported = ShieldVideoApp.instance.remoteClient.refreshPodcastFeeds(device)
                val after = imported.getOrNull()?.second?.mapNotNull { parseRemoteShow(it) }.orEmpty()
                _ui.update { it.copy(shows = after) }
                if (after.isEmpty()) {
                    cachedEpisodes = emptyList()
                    publishEpisodesForSelection()
                    clearOverlay(
                        imported.exceptionOrNull()?.message
                            ?: "No subscriptions on room — import OPML in Settings",
                    )
                    return
                }
            }
            val count = _ui.value.shows.size
            setOverlay(0, count, "Loading episodes…")
        }

        val result = ShieldVideoApp.instance.remoteClient.podcastEpisodes(device, showId = null)
        result.onSuccess { objs ->
            val episodes = ArrayList<PodcastEpisode>(objs.size)
            val progress = _ui.value.progress.toMutableMap()
            objs.forEach { o ->
                val ep = parseRemoteEpisode(o)
                episodes.add(ep)
                val pos = o.optLong("positionMs")
                val dur = o.optLong("durationMs")
                val done = o.optBoolean("completed", false)
                if (pos > 0L || done || dur > 0L) {
                    progress[ep.guid] = PodcastEpisodeProgress(
                        guid = ep.guid,
                        showId = ep.showId,
                        positionMs = pos,
                        durationMs = dur,
                        completed = done,
                    )
                }
            }
            cachedEpisodes = episodes
            _ui.update { it.copy(progress = progress) }
            publishEpisodesForSelection()
            val n = _ui.value.shows.size
            clearOverlay(if (forceNetwork) "Updated $n shows" else null)
        }.onFailure { err ->
            clearOverlay(friendlyRemoteError(err))
        }
    }

    private fun publishEpisodesForSelection() {
        val selected = _ui.value.selectedShow
        if (selected != null) {
            // Global catalog is recent-across-all; keep / refresh the full per-show feed.
            refreshSelectedShowEpisodes(selected, resetVisible = false)
            return
        }
        _ui.update {
            it.copy(
                episodes = cachedEpisodes,
                visibleEpisodeCount = EPISODE_PAGE_SIZE,
                loadingEpisodes = false,
                loadingCatalog = false,
            )
        }
    }

    /** Reveal the next page of already-loaded episodes (scroll / focus near list end). */
    fun loadMoreEpisodes() {
        _ui.update { state ->
            val total = sortEpisodes(state.episodes, state.episodeSort, state.progress).size
            if (state.visibleEpisodeCount >= total) state
            else state.copy(
                visibleEpisodeCount = (state.visibleEpisodeCount + EPISODE_PAGE_SIZE)
                    .coerceAtMost(total),
            )
        }
    }

    fun cycleShowSort() {
        val next = _ui.value.showSort.next()
        if (!isRemoteSession()) repository.showSort = next
        _ui.update { it.copy(showSort = next) }
    }

    fun setShowSort(sort: PodcastShowSort) {
        if (!isRemoteSession()) repository.showSort = sort
        _ui.update { it.copy(showSort = sort) }
    }

    fun cycleEpisodeSort() {
        val next = _ui.value.episodeSort.next()
        if (!isRemoteSession()) repository.episodeSort = next
        _ui.update {
            it.copy(
                episodeSort = next,
                visibleEpisodeCount = EPISODE_PAGE_SIZE,
            )
        }
    }

    fun selectAllShows() {
        _ui.update {
            it.copy(
                selectedShow = null,
                visibleEpisodeCount = EPISODE_PAGE_SIZE,
            )
        }
        val multiShow = cachedEpisodes.map { it.showId }.toSet().size > 1
        if (cachedEpisodes.isNotEmpty() && (multiShow || !isRemoteSession())) {
            publishEpisodesForSelection()
        } else {
            startCatalogLoad(forceNetwork = false)
        }
    }

    fun selectShow(show: PodcastShow) {
        val preview = cachedEpisodes.filter { it.showId == show.id }
        _ui.update {
            it.copy(
                selectedShow = show,
                episodes = preview,
                visibleEpisodeCount = EPISODE_PAGE_SIZE,
                loadingEpisodes = preview.isEmpty(),
            )
        }
        refreshSelectedShowEpisodes(show, resetVisible = true)
    }

    private fun refreshSelectedShowEpisodes(show: PodcastShow, resetVisible: Boolean) {
        if (isRemoteSession()) {
            loadShowEpisodesRemote(show, resetVisible = resetVisible)
            return
        }
        viewModelScope.launch {
            val eps = runCatching {
                repository.episodesForShow(show, forceRefresh = false)
            }.getOrDefault(emptyList())
            // Only apply if this show is still selected (user may have switched).
            if (_ui.value.selectedShow?.id != show.id) return@launch
            _ui.update {
                val nextVisible = if (resetVisible) {
                    EPISODE_PAGE_SIZE
                } else {
                    it.visibleEpisodeCount
                        .coerceAtLeast(EPISODE_PAGE_SIZE)
                        .coerceAtMost(eps.size.coerceAtLeast(EPISODE_PAGE_SIZE))
                }
                it.copy(
                    episodes = eps.ifEmpty { it.episodes },
                    loadingEpisodes = false,
                    loadingCatalog = false,
                    visibleEpisodeCount = nextVisible,
                )
            }
        }
    }

    private fun loadShowEpisodesRemote(show: PodcastShow, resetVisible: Boolean = true) {
        val device = RemoteTargetStore.current() ?: return
        viewModelScope.launch {
            val result = ShieldVideoApp.instance.remoteClient.podcastEpisodes(device, showId = show.id)
            if (_ui.value.selectedShow?.id != show.id) return@launch
            result.onSuccess { objs ->
                val episodes = objs.map { parseRemoteEpisode(it) }
                val progress = _ui.value.progress.toMutableMap()
                objs.forEach { o ->
                    val guid = o.optString("guid")
                    if (guid.isBlank()) return@forEach
                    val pos = o.optLong("positionMs")
                    val dur = o.optLong("durationMs")
                    val done = o.optBoolean("completed", false)
                    if (pos > 0L || done || dur > 0L) {
                        progress[guid] = PodcastEpisodeProgress(
                            guid = guid,
                            showId = o.optString("showId", show.id),
                            positionMs = pos,
                            durationMs = dur,
                            completed = done,
                        )
                    }
                }
                _ui.update {
                    val nextVisible = if (resetVisible) {
                        EPISODE_PAGE_SIZE
                    } else {
                        it.visibleEpisodeCount
                            .coerceAtLeast(EPISODE_PAGE_SIZE)
                            .coerceAtMost(episodes.size.coerceAtLeast(EPISODE_PAGE_SIZE))
                    }
                    it.copy(
                        episodes = episodes.ifEmpty { it.episodes },
                        progress = progress,
                        loadingEpisodes = false,
                        loadingCatalog = false,
                        visibleEpisodeCount = nextVisible,
                    )
                }
            }.onFailure {
                _ui.update { it.copy(loadingEpisodes = false, loadingCatalog = false) }
                flash(friendlyRemoteError(it), AppNoticeKind.Error)
            }
        }
    }

    fun refreshSelected(force: Boolean = true) {
        startCatalogLoad(forceNetwork = force)
    }

    fun playEpisode(episode: PodcastEpisode) {
        val show = _ui.value.shows.firstOrNull { it.id == episode.showId }
            ?: _ui.value.selectedShow
            ?: PodcastShow(
                id = episode.showId,
                title = _ui.value.showTitleById[episode.showId] ?: "Podcast",
                feedUrl = "",
                imageUrl = episode.imageUrl,
            )
        val saved = _ui.value.progress[episode.guid]
            ?: repository.progressFor(episode.guid).takeIf { !isRemoteSession() }
        val start = when {
            saved == null -> 0L
            saved.completed -> 0L
            saved.positionMs > 5_000L -> saved.positionMs
            else -> 0L
        }
        viewModelScope.launch {
            runCatching {
                RemotePlayBridge.playPodcast(
                    showId = show.id,
                    episodeGuid = episode.guid,
                    audioUrl = episode.audioUrl,
                    episodeTitle = episode.title,
                    showTitle = show.title,
                    imageUrl = episode.imageUrl.ifBlank { show.imageUrl },
                    durationSec = episode.durationSec,
                    positionMs = start,
                ) {
                    playback.playEpisode(show, episode, startPositionMs = start)
                }
            }.onFailure {
                flash(it.message ?: "Remote play failed", AppNoticeKind.Error)
            }
        }
    }

    fun togglePlayPause() {
        if (isRemoteSession()) {
            remoteTransport(TransportAction.Toggle)
        } else {
            playback.toggle()
        }
    }

    fun seekBack() {
        if (isRemoteSession()) {
            val pos = (playbackState.value.positionMs - 15_000L).coerceAtLeast(0L)
            remoteTransport(TransportAction.Seek, pos)
        } else {
            playback.seekBy(-15_000L)
        }
    }

    fun seekForward() {
        if (isRemoteSession()) {
            val pos = playbackState.value.positionMs + 30_000L
            remoteTransport(TransportAction.Seek, pos)
        } else {
            playback.seekBy(30_000L)
        }
    }

    fun playNewer() {
        if (isRemoteSession()) {
            remoteTransport(TransportAction.Previous)
            return
        }
        val guid = playback.state.value.episodeGuid
        val episodes = _ui.value.sortedEpisodes
        val idx = episodes.indexOfFirst { it.guid == guid }
        if (idx > 0) playEpisode(episodes[idx - 1])
    }

    fun playOlder() {
        if (isRemoteSession()) {
            remoteTransport(TransportAction.Next)
            return
        }
        val guid = playback.state.value.episodeGuid
        val episodes = _ui.value.sortedEpisodes
        val idx = episodes.indexOfFirst { it.guid == guid }
        if (idx in 0 until episodes.lastIndex) playEpisode(episodes[idx + 1])
    }

    fun stopPlayback() {
        if (isRemoteSession()) {
            remoteTransport(TransportAction.Stop)
            return
        }
        persistCurrentProgress(forceComplete = false)
        playback.stop()
    }

    private fun remoteTransport(action: TransportAction, positionMs: Long = 0L) {
        val device = RemoteTargetStore.current() ?: return
        viewModelScope.launch {
            ShieldVideoApp.instance.remoteClient.transport(device, action, positionMs)
                .onSuccess {
                    _remoteStatus.value = it
                    RemoteStatusPoller.publish(it)
                }
        }
    }

    private fun friendlyRemoteError(err: Throwable?): String {
        val raw = err?.message.orEmpty()
        return when {
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                "Room still loading — try Refresh"
            raw.isBlank() -> "Failed to load room podcasts"
            else -> raw
        }
    }

    private fun parseRemoteShow(o: org.json.JSONObject): PodcastShow? {
        val feed = o.optString("feedUrl").trim()
        if (feed.isBlank() && o.optString("id").isBlank()) return null
        val genresArr = o.optJSONArray("genres")
        val genres = buildList {
            if (genresArr != null) {
                for (g in 0 until genresArr.length()) {
                    genresArr.optString(g).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        return PodcastShow(
            id = o.optString("id").ifBlank {
                java.util.UUID.nameUUIDFromBytes(feed.lowercase().toByteArray()).toString()
            },
            title = o.optString("title", feed),
            feedUrl = feed,
            siteUrl = o.optString("siteUrl", ""),
            imageUrl = o.optString("imageUrl", ""),
            genres = genres,
            latestEpisodeEpochMs = o.optLong("latestEpisodeEpochMs", 0L),
        )
    }

    private fun parseRemoteEpisode(o: org.json.JSONObject): PodcastEpisode =
        PodcastEpisode(
            guid = o.optString("guid"),
            showId = o.optString("showId"),
            title = o.optString("title", "Episode"),
            description = o.optString("description", ""),
            audioUrl = o.optString("audioUrl"),
            publishEpochMs = o.optLong("publishEpochMs"),
            durationSec = o.optLong("durationSec"),
            imageUrl = o.optString("imageUrl", ""),
        )

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                if (isRemoteSession()) continue
                if (!playback.isActive()) continue
                playback.syncPosition()
                val s = playback.state.value
                if (s.episodeGuid.isBlank()) continue
                if (s.isPlaying || s.positionMs > 0L) {
                    repository.saveProgress(
                        guid = s.episodeGuid,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                        completed = s.durationMs > 0L && s.positionMs >= s.durationMs - 5_000L,
                        showId = s.showId,
                    )
                }
            }
        }
    }

    private fun persistCurrentProgress(forceComplete: Boolean) {
        if (isRemoteSession()) return
        val s = playback.state.value
        if (s.episodeGuid.isBlank()) return
        repository.saveProgress(
            guid = s.episodeGuid,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            completed = forceComplete ||
                (s.durationMs > 0L && s.positionMs >= s.durationMs - 5_000L),
            showId = s.showId,
        )
    }

    override fun onCleared() {
        persistCurrentProgress(forceComplete = false)
        progressJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val ALL_EPISODES_CAP = 250
        /** Initial / incremental episode window for the list pane. */
        const val EPISODE_PAGE_SIZE = 8
    }
}

private fun RemoteStatus.toPodcastPlaybackState(): PodcastPlaybackState =
    PodcastPlaybackState(
        showTitle = subtitle,
        episodeGuid = "remote",
        episodeTitle = title,
        imageUrl = artworkUrl,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
    )

class PodcastViewModelFactory(
    private val repository: PodcastRepository,
    private val playback: PodcastPlaybackController,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PodcastViewModel(repository, playback) as T
    }
}
