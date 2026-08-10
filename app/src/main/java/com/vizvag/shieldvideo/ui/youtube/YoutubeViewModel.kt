package com.vizvag.shieldvideo.ui.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeApiException
import com.vizvag.shieldvideo.data.youtube.YoutubeFeedSort
import com.vizvag.shieldvideo.data.youtube.YoutubeRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeStreamInfo
import com.vizvag.shieldvideo.data.youtube.YoutubeTvAuthRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeVideoItem
import com.vizvag.shieldvideo.data.youtube.YoutubeResolutionCache
import com.vizvag.shieldvideo.data.youtube.YoutubeWatchHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class YoutubeUiState(
    val searchQuery: String = "",
    val feed: List<YoutubeVideoItem> = emptyList(),
    val recommended: List<YoutubeVideoItem> = emptyList(),
    val searchResults: List<YoutubeVideoItem> = emptyList(),
    val history: List<YoutubeVideoItem> = emptyList(),
    /** Locally watched video ids (for WATCHED badges + Recommended filter). */
    val watchedIds: Set<String> = emptySet(),
    val related: List<YoutubeVideoItem> = emptyList(),
    val channelId: String? = null,
    val channelTitle: String = "",
    val channelVideos: List<YoutubeVideoItem> = emptyList(),
    val feedSort: YoutubeFeedSort = YoutubeFeedSort.Newest,
    val loggedIn: Boolean = false,
    val username: String = "",
    val loadingBrowse: Boolean = false,
    val loadingPlay: Boolean = false,
    val error: String? = null,
    val playing: YoutubeStreamInfo? = null,
    val fullscreen: Boolean = false,
) {
    val sortedFeed: List<YoutubeVideoItem>
        get() = sortVideos(feed, feedSort)

    /** Keep YouTube home order for Newest — tile dates are often missing and sort dumps stubs at the end. */
    val sortedRecommended: List<YoutubeVideoItem>
        get() = when (feedSort) {
            YoutubeFeedSort.Newest -> recommended
            else -> sortVideos(recommended, feedSort)
        }

    val sortedChannelVideos: List<YoutubeVideoItem>
        get() = sortVideos(channelVideos, feedSort)

    val sortedSearchResults: List<YoutubeVideoItem>
        get() = sortVideos(searchResults, feedSort)

    val browsingChannel: Boolean get() = !channelId.isNullOrBlank()
}

private fun sortVideos(
    items: List<YoutubeVideoItem>,
    sort: YoutubeFeedSort,
): List<YoutubeVideoItem> = when (sort) {
    YoutubeFeedSort.Newest -> items.sortedWith(
        compareByDescending<YoutubeVideoItem> { it.uploadedEpochMs }
            .thenByDescending { it.views },
    )
    YoutubeFeedSort.Popular -> items.sortedWith(
        compareByDescending<YoutubeVideoItem> { it.views }
            .thenByDescending { it.uploadedEpochMs },
    )
    YoutubeFeedSort.Title -> items.sortedBy { it.title.lowercase() }
}

class YoutubeViewModel(
    private val settingsRepository: SettingsRepository,
    private val repository: YoutubeRepository,
    private val historyStore: YoutubeWatchHistoryStore,
    private val resolutionCache: YoutubeResolutionCache,
) : ViewModel() {
    private val _state = MutableStateFlow(
        YoutubeUiState(
            history = annotate(historyStore.items()),
            watchedIds = historyStore.watchedIds(),
        ).withAuth(settingsRepository)
    )
    val state: StateFlow<YoutubeUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var feedJob: Job? = null
    private var resolutionJob: Job? = null
    private val youtubeTvAuth = YoutubeTvAuthRepository()

    private fun annotate(items: List<YoutubeVideoItem>): List<YoutubeVideoItem> =
        items.map { item ->
            if (!item.resolutionLabel.isNullOrBlank()) item
            else item.copy(resolutionLabel = resolutionCache.labelFor(item.id))
        }

    private fun applyResolution(id: String, label: String?) {
        if (label.isNullOrBlank()) return
        _state.update { state ->
            fun List<YoutubeVideoItem>.patched() =
                map { if (it.id == id) it.copy(resolutionLabel = label) else it }
            state.copy(
                feed = state.feed.patched(),
                recommended = state.recommended.patched(),
                searchResults = state.searchResults.patched(),
                history = state.history.patched(),
                related = state.related.patched(),
                channelVideos = state.channelVideos.patched(),
            )
        }
    }

    private fun prefetchResolutions(vararg lists: List<YoutubeVideoItem>) {
        resolutionJob?.cancel()
        resolutionJob = viewModelScope.launch {
            val missing = lists.asList()
                .flatten()
                .map { it.id }
                .distinct()
                .filter { it.length == 11 && resolutionCache.heightFor(it) <= 0 }
                .take(40)
            if (missing.isEmpty()) return@launch
            val gate = Semaphore(2)
            missing.map { id ->
                async {
                    gate.withPermit {
                        val height = runCatching { repository.maxVideoHeight(id) }.getOrDefault(0)
                        if (height > 0) {
                            resolutionCache.put(id, height)
                            applyResolution(id, YoutubeResolutionCache.labelForHeight(height))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun setFeedSort(sort: YoutubeFeedSort) {
        _state.update { it.copy(feedSort = sort) }
    }

    fun refreshHome() {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            val settings = settingsRepository.load()
            val displayName = settings.youtubeAccountName
                .ifBlank { if (settings.isYoutubeTvLinked) "YouTube" else "" }
            val watched = historyStore.watchedIds()
            _state.update {
                it.copy(
                    loggedIn = settings.isYoutubeLoggedIn,
                    username = displayName,
                    history = annotate(historyStore.items()),
                    watchedIds = watched,
                    loadingBrowse = true,
                    error = null,
                )
            }
            if (settings.isYoutubeTvLinked) {
                runCatching {
                    coroutineScope {
                        val access = ensureAccessToken(settings)
                        val subs = async { repository.youtubeAccountFeed(access) }
                        val home = async {
                            runCatching { repository.youtubeRecommendedFeed(access) }
                                .getOrDefault(emptyList())
                        }
                        Pair(subs.await(), home.await())
                    }
                }.onSuccess { (subs, home) ->
                    val watchedNow = historyStore.watchedIds()
                    val recommended = annotate(
                        home.filter { v ->
                            v.title.isNotBlank() &&
                                v.id.length == 11 &&
                                v.id !in watchedNow
                        }.take(120),
                    )
                    val feed = annotate(subs.take(100))
                    _state.update {
                        it.copy(
                            feed = feed,
                            recommended = recommended,
                            watchedIds = watchedNow,
                            loadingBrowse = false,
                            error = null,
                        )
                    }
                    prefetchResolutions(recommended, feed)
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            feed = emptyList(),
                            recommended = emptyList(),
                            loadingBrowse = false,
                            error = e.message
                                ?: "Could not load YouTube feed. Re-link in Settings → YouTube.",
                        )
                    }
                }
            } else {
                _state.update {
                    it.copy(
                        feed = emptyList(),
                        recommended = emptyList(),
                        loadingBrowse = false,
                    )
                }
                prefetchResolutions(_state.value.history)
            }
        }
    }

    private suspend fun ensureAccessToken(
        settings: com.vizvag.shieldvideo.data.settings.AppSettings,
    ): String {
        val now = System.currentTimeMillis()
        if (
            settings.youtubeAccessToken.isNotBlank() &&
            settings.youtubeAccessTokenExpiresAtMs > now + 30_000L
        ) {
            return settings.youtubeAccessToken
        }
        val tokens = youtubeTvAuth.refreshAccessToken(settings.youtubeRefreshToken)
            .getOrThrow()
        val updated = settings.copy(
            youtubeAccessToken = tokens.accessToken,
            youtubeRefreshToken = tokens.refreshToken,
            youtubeAccessTokenExpiresAtMs =
                now + tokens.expiresInSeconds * 1000L - 60_000L,
        )
        settingsRepository.save(updated)
        return tokens.accessToken
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val q = query.trim()
        if (q.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(loadingBrowse = true, error = null) }
            runCatching { repository.search(q) }
                .onSuccess { list ->
                    val annotated = annotate(list)
                    _state.update {
                        it.copy(searchResults = annotated, loadingBrowse = false, error = null)
                    }
                    prefetchResolutions(annotated)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loadingBrowse = false,
                            error = e.message
                                ?: "Search failed. Check Settings → YouTube search API URL.",
                        )
                    }
                }
        }
    }

    fun play(item: YoutubeVideoItem) {
        playById(item.id, preview = item)
    }

    fun playById(videoId: String, preview: YoutubeVideoItem? = null) {
        viewModelScope.launch {
            runCatching {
                com.vizvag.shieldvideo.playback.remote.RemotePlayBridge.playYouTube(videoId) {
                    playByIdLocal(videoId, preview)
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loadingPlay = false,
                        error = e.message ?: "Remote play failed",
                        fullscreen = false,
                    )
                }
            }
        }
    }

    private suspend fun playByIdLocal(videoId: String, preview: YoutubeVideoItem? = null) {
            _state.update { it.copy(loadingPlay = true, error = null) }
            runCatching { repository.streams(videoId) }
                .onSuccess { info ->
                    val enriched = info.copy(
                        views = preview?.views?.takeIf { it > 0 } ?: info.views,
                        uploadedDate = preview?.uploadedDate?.takeIf { it.isNotBlank() }
                            ?: info.uploadedDate,
                        channelId = info.channelId.ifBlank { preview?.channelId.orEmpty() },
                        maxHeight = info.maxHeight.takeIf { it > 0 }
                            ?: resolutionCache.heightFor(info.id),
                    )
                    val historyItem = YoutubeVideoItem(
                        id = enriched.id,
                        title = enriched.title,
                        uploader = enriched.uploader,
                        thumbnailUrl = enriched.thumbnailUrl.ifBlank {
                            preview?.thumbnailUrl.orEmpty()
                        },
                        durationSec = enriched.durationSec,
                        views = enriched.views,
                        uploadedDate = enriched.uploadedDate,
                        uploadedEpochMs = preview?.uploadedEpochMs ?: 0L,
                        channelId = enriched.channelId,
                        resolutionLabel = YoutubeResolutionCache.labelForHeight(enriched.maxHeight)
                            ?: preview?.resolutionLabel
                            ?: resolutionCache.labelFor(enriched.id),
                    )
                    if (enriched.maxHeight > 0) {
                        resolutionCache.put(enriched.id, enriched.maxHeight)
                    }
                    historyStore.record(historyItem)
                    val watchedNow = historyStore.watchedIds()
                    val related = annotate(enriched.related)
                    // Playback is started by BindYoutubeStream when [playing] is set —
                    // do not call playStream here (double-start caused Source errors).
                    _state.update {
                        it.copy(
                            playing = enriched,
                            related = related,
                            history = annotate(historyStore.items()),
                            watchedIds = watchedNow,
                            // Drop from Recommended as soon as play starts.
                            recommended = it.recommended.filter { v -> v.id !in watchedNow },
                            loadingPlay = false,
                            fullscreen = true,
                            error = null,
                        )
                    }
                    applyResolution(enriched.id, historyItem.resolutionLabel)
                    prefetchResolutions(related)
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is YoutubeApiException -> e.message
                        else -> e.message
                    } ?: "Playback failed"
                    _state.update {
                        it.copy(loadingPlay = false, error = msg, fullscreen = false)
                    }
                }
    }

    fun closePlayer() {
        _state.update {
            it.copy(playing = null, fullscreen = false, related = emptyList())
        }
    }

    fun openChannel(item: YoutubeVideoItem) {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingBrowse = true,
                    error = null,
                    channelId = item.channelId.trim().ifBlank { null },
                    channelTitle = item.uploader.ifBlank { "Channel" },
                    channelVideos = emptyList(),
                )
            }
            runCatching {
                val settings = settingsRepository.load()
                check(settings.isYoutubeTvLinked) { "Link YouTube in Settings first" }
                var channelId = item.channelId.trim()
                var channelTitle = item.uploader.ifBlank { "Channel" }
                if (channelId.isBlank()) {
                    // Home / Recommended tiles often omit channel ids — resolve via player.
                    val info = repository.streams(item.id)
                    channelId = info.channelId.trim()
                    check(channelId.isNotBlank()) {
                        "Channel unavailable for this video — try another"
                    }
                    if (channelTitle == "Channel") {
                        channelTitle = info.uploader.ifBlank { "Channel" }
                    }
                }
                _state.update {
                    it.copy(channelId = channelId, channelTitle = channelTitle)
                }
                val access = ensureAccessToken(settings)
                Triple(channelId, channelTitle, repository.youtubeChannelFeed(access, channelId))
            }.onSuccess { (channelId, channelTitle, list) ->
                val annotated = annotate(list)
                _state.update {
                    it.copy(
                        channelId = channelId,
                        channelTitle = channelTitle,
                        channelVideos = annotated,
                        loadingBrowse = false,
                        error = if (list.isEmpty()) "No videos found on this channel" else null,
                    )
                }
                prefetchResolutions(annotated)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loadingBrowse = false,
                        channelId = null,
                        channelTitle = "",
                        channelVideos = emptyList(),
                        error = e.message ?: "Could not open channel",
                    )
                }
            }
        }
    }

    fun closeChannel() {
        feedJob?.cancel()
        _state.update {
            it.copy(channelId = null, channelTitle = "", channelVideos = emptyList())
        }
        refreshHome()
    }

    fun reportPlaybackError(message: String?) {
        if (message == null) {
            _state.update { it.copy(error = null) }
            return
        }
        // Soft errors keep the current stream (quality rollback, transient Source error on swap).
        val soft = message.contains("Quality", ignoreCase = true) ||
            message.contains("unavailable", ignoreCase = true) ||
            message.contains("kept previous", ignoreCase = true) ||
            message.contains("Couldn't switch", ignoreCase = true)
        _state.update {
            if (soft) {
                it.copy(error = message)
            } else {
                it.copy(
                    error = message,
                    playing = null,
                    fullscreen = false,
                    related = emptyList(),
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun YoutubeUiState.withAuth(repo: SettingsRepository): YoutubeUiState {
        val s = repo.load()
        val name = s.youtubeAccountName
            .ifBlank { if (s.isYoutubeTvLinked) "YouTube" else "" }
        return copy(loggedIn = s.isYoutubeLoggedIn, username = name)
    }
}

class YoutubeViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val repository: YoutubeRepository,
    private val historyStore: YoutubeWatchHistoryStore,
    private val resolutionCache: YoutubeResolutionCache,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(YoutubeViewModel::class.java)) {
            return YoutubeViewModel(
                settingsRepository,
                repository,
                historyStore,
                resolutionCache,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
