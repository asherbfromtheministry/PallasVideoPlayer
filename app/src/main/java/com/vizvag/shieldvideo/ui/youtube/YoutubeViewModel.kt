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
import com.vizvag.shieldvideo.data.youtube.YoutubeWatchHistoryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YoutubeUiState(
    val searchQuery: String = "",
    val feed: List<YoutubeVideoItem> = emptyList(),
    val searchResults: List<YoutubeVideoItem> = emptyList(),
    val history: List<YoutubeVideoItem> = emptyList(),
    val related: List<YoutubeVideoItem> = emptyList(),
    val feedSort: YoutubeFeedSort = YoutubeFeedSort.Newest,
    val loggedIn: Boolean = false,
    val username: String = "",
    /** Piped account channel count (0 = need Takeout import). */
    val subscriptionCount: Int = 0,
    val loadingBrowse: Boolean = false,
    val loadingPlay: Boolean = false,
    val error: String? = null,
    val playing: YoutubeStreamInfo? = null,
    val fullscreen: Boolean = false,
) {
    val sortedFeed: List<YoutubeVideoItem>
        get() = sortVideos(feed, feedSort)

    val sortedSearchResults: List<YoutubeVideoItem>
        get() = sortVideos(searchResults, feedSort)
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
) : ViewModel() {
    private val _state = MutableStateFlow(
        YoutubeUiState(history = historyStore.items()).withAuth(settingsRepository)
    )
    val state: StateFlow<YoutubeUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var feedJob: Job? = null
    private val youtubeTvAuth = YoutubeTvAuthRepository()

    fun setFeedSort(sort: YoutubeFeedSort) {
        _state.update { it.copy(feedSort = sort) }
    }

    fun refreshHome() {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            val settings = settingsRepository.load()
            val displayName = settings.youtubeAccountName
                .ifBlank { settings.youtubePipedUsername }
                .ifBlank { if (settings.isYoutubeTvLinked) "YouTube" else "" }
            _state.update {
                it.copy(
                    loggedIn = settings.isYoutubeLoggedIn,
                    username = displayName,
                    history = historyStore.items(),
                    loadingBrowse = true,
                    error = null,
                )
            }
            when {
                settings.isYoutubeTvLinked -> {
                    runCatching { loadYoutubeAccountFeed(settings) }
                        .onSuccess { list ->
                            _state.update {
                                it.copy(
                                    feed = list,
                                    subscriptionCount = list.size,
                                    loadingBrowse = false,
                                    error = null,
                                )
                            }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(
                                    feed = emptyList(),
                                    loadingBrowse = false,
                                    error = e.message
                                        ?: "Could not load YouTube feed. Re-link in Settings → YouTube.",
                                )
                            }
                        }
                }
                settings.youtubePipedAuthToken.isNotBlank() -> {
                    val token = settings.youtubePipedAuthToken
                    val subCount = runCatching { repository.subscriptions(token).size }.getOrDefault(0)
                    _state.update { it.copy(subscriptionCount = subCount) }
                    runCatching { repository.feed(token) }
                        .onSuccess { list ->
                            _state.update {
                                it.copy(feed = list, loadingBrowse = false, error = null)
                            }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(
                                    feed = emptyList(),
                                    loadingBrowse = false,
                                    error = e.message
                                        ?: "Could not load subscriptions. Re-login in Settings → YouTube.",
                                )
                            }
                        }
                }
                else -> {
                    _state.update {
                        it.copy(
                            feed = emptyList(),
                            subscriptionCount = 0,
                            loadingBrowse = false,
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadYoutubeAccountFeed(
        settings: com.vizvag.shieldvideo.data.settings.AppSettings,
    ): List<YoutubeVideoItem> {
        val now = System.currentTimeMillis()
        val access = if (
            settings.youtubeAccessToken.isNotBlank() &&
            settings.youtubeAccessTokenExpiresAtMs > now + 30_000L
        ) {
            settings.youtubeAccessToken
        } else {
            val tokens = youtubeTvAuth.refreshAccessToken(settings.youtubeRefreshToken)
                .getOrThrow()
            val updated = settings.copy(
                youtubeAccessToken = tokens.accessToken,
                youtubeRefreshToken = tokens.refreshToken,
                youtubeAccessTokenExpiresAtMs =
                    now + tokens.expiresInSeconds * 1000L - 60_000L,
            )
            settingsRepository.save(updated)
            tokens.accessToken
        }
        return repository.youtubeAccountFeed(access)
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
                    _state.update {
                        it.copy(searchResults = list, loadingBrowse = false, error = null)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loadingBrowse = false,
                            error = e.message
                                ?: "Search failed. Check Settings → YouTube Piped API URL.",
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
                    val historyItem = YoutubeVideoItem(
                        id = info.id,
                        title = info.title,
                        uploader = info.uploader,
                        thumbnailUrl = info.thumbnailUrl.ifBlank {
                            preview?.thumbnailUrl.orEmpty()
                        },
                        durationSec = info.durationSec,
                        views = preview?.views ?: 0L,
                        uploadedDate = preview?.uploadedDate.orEmpty(),
                        uploadedEpochMs = preview?.uploadedEpochMs ?: 0L,
                    )
                    historyStore.record(historyItem)
                    com.vizvag.shieldvideo.ShieldVideoApp.instance.youtubePlayback.playStream(info)
                    _state.update {
                        it.copy(
                            playing = info,
                            related = info.related,
                            history = historyStore.items(),
                            loadingPlay = false,
                            fullscreen = true,
                            error = null,
                        )
                    }
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

    fun reportPlaybackError(message: String?) {
        if (message == null) {
            _state.update { it.copy(error = null) }
            return
        }
        _state.update {
            it.copy(
                error = message,
                playing = null,
                fullscreen = false,
                related = emptyList(),
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun pipedApiHint(): String =
        settingsRepository.load().youtubePipedApiUrl

    private fun YoutubeUiState.withAuth(repo: SettingsRepository): YoutubeUiState {
        val s = repo.load()
        val name = s.youtubeAccountName
            .ifBlank { s.youtubePipedUsername }
            .ifBlank { if (s.isYoutubeTvLinked) "YouTube" else "" }
        return copy(loggedIn = s.isYoutubeLoggedIn, username = name)
    }
}

class YoutubeViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val repository: YoutubeRepository,
    private val historyStore: YoutubeWatchHistoryStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(YoutubeViewModel::class.java)) {
            return YoutubeViewModel(settingsRepository, repository, historyStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
