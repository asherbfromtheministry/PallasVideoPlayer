package com.vizvag.shieldvideo.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.music.data.LibraryRepository
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.metadata.TrackTagInfo
import com.vizvag.shieldvideo.music.data.settings.MusicSettingsBridge
import com.vizvag.shieldvideo.music.data.settings.NasSettings
import com.vizvag.shieldvideo.music.data.synology.FileEntry
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.music.player.PlayerController
import com.vizvag.shieldvideo.music.player.PlayerUiState
import com.vizvag.shieldvideo.playback.remote.MusicQueueAction
import com.vizvag.shieldvideo.playback.remote.MusicTrackRef
import com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode
import com.vizvag.shieldvideo.playback.remote.RemoteQueueItem
import com.vizvag.shieldvideo.playback.remote.RemoteStatus
import com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller
import com.vizvag.shieldvideo.playback.remote.RemoteTargetStore
import com.vizvag.shieldvideo.playback.remote.TransportAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.vizvag.shieldvideo.ShieldVideoApp

class MusicViewModel(
    private val libraryRepository: LibraryRepository,
    libraryCache: com.vizvag.shieldvideo.music.data.MusicLibraryCache,
    private val musicSettings: MusicSettingsBridge,
    private val settingsRepository: SettingsRepository,
    private val synologyApiClient: SynologyApiClient,
    private val musicIndex: com.vizvag.shieldvideo.music.data.MusicIndexController,
    private val streamUrlBuilder: com.vizvag.shieldvideo.music.player.StreamUrlBuilder,
    private val queueManager: com.vizvag.shieldvideo.music.player.QueueManager,
    val playerController: PlayerController,
    private val albumArtLookup: com.vizvag.shieldvideo.music.data.metadata.AlbumArtLookup,
    private val coverArtCache: com.vizvag.shieldvideo.music.data.metadata.CoverArtCache,
    private val playlistCoverStore: com.vizvag.shieldvideo.music.data.metadata.PlaylistCoverStore,
    private val lyricsRepository: com.vizvag.shieldvideo.music.data.lyrics.LyricsRepository,
) : ViewModel() {

    val settings = musicSettings.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), NasSettings(),
    )
    val artists = libraryCache.artists
    val albums = libraryCache.albums
    val tracks = libraryCache.tracks
    val recentTracks = libraryCache.recentTracks
    val indexState = libraryCache.indexState
    val libraryTracksReady = libraryCache.tracksReady

    private val _hueSyncReady = MutableStateFlow(false)
    val hueSyncReady = _hueSyncReady.asStateFlow()
    private val _hueSyncEnabled = MutableStateFlow(false)
    val hueSyncEnabled = _hueSyncEnabled.asStateFlow()

    private val _remoteStatus = MutableStateFlow<RemoteStatus?>(null)
    val controllingRemote = RemoteTargetStore.target

    init {
        refreshHueSyncState()
        viewModelScope.launch {
            settingsRepository.revision.collect { refreshHueSyncState() }
        }
        viewModelScope.launch {
            RemoteStatusPoller.status.collect { _remoteStatus.value = it }
        }
    }

    val playerState = combine(
        playerController.uiState,
        _remoteStatus,
        RemoteTargetStore.target,
    ) { local, remote, target ->
        when {
            target == null -> local
            remote != null && remoteLooksLikeMusic(remote) -> remote.toPlayerUiState()
            // Controlling a room: never show this tablet's local now-playing as if it were the TV.
            else -> PlayerUiState()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PlayerUiState(),
    )

    val queue = combine(
        queueManager.queue,
        _remoteStatus,
        RemoteTargetStore.target,
    ) { local, remote, target ->
        when {
            target == null -> local
            remote != null && remote.queue.isNotEmpty() -> remote.queue.map { it.toTrackEntity() }
            remote != null && remoteLooksLikeMusic(remote) -> remote.queue.map { it.toTrackEntity() }
            // Connected but room has no music queue yet — empty, not this device's leftover playlist.
            else -> emptyList()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val queueIndex = combine(
        queueManager.currentIndex,
        _remoteStatus,
        RemoteTargetStore.target,
    ) { local, remote, target ->
        when {
            target == null -> local
            remote != null && remoteLooksLikeMusic(remote) -> remote.queueIndex
            else -> -1
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        -1,
    )

    private val _coverUrl = MutableStateFlow<Any?>(null)
    /** Coil model — remote [String] URL or cached local [java.io.File]. */
    val coverUrl = _coverUrl.asStateFlow()

    private val _trackArtUrl = MutableStateFlow<Any?>(null)
    /** Coil model — remote [String] URL or cached local [java.io.File]. */
    val trackArtUrl = _trackArtUrl.asStateFlow()

    private val _artDetails = MutableStateFlow(ArtDetails())
    val artDetails = _artDetails.asStateFlow()

    private val _trackTags = MutableStateFlow<TrackTagInfo?>(null)
    val trackTags = _trackTags.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _lyrics = MutableStateFlow<List<com.vizvag.shieldvideo.music.data.lyrics.LyricLine>>(emptyList())
    val lyrics = _lyrics.asStateFlow()

    private val _lyricsPath = MutableStateFlow<String?>(null)
    val lyricsPath = _lyricsPath.asStateFlow()

    private val _lyricsVisible = MutableStateFlow(false)
    val lyricsVisible = _lyricsVisible.asStateFlow()

    private val _lyricsStatus = MutableStateFlow(LyricsStatus.Idle)
    val lyricsStatus = _lyricsStatus.asStateFlow()

    /** Added to playback position when picking the current lyric line (negative = lyrics earlier). */
    private val _lyricsOffsetMs = MutableStateFlow(0L)
    val lyricsOffsetMs = _lyricsOffsetMs.asStateFlow()

    private val _connectionMessage = MutableStateFlow<String?>(null)
    val connectionMessage = _connectionMessage.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow(com.vizvag.shieldvideo.music.data.SearchResults())
    val searchResults = _searchResults.asStateFlow()

    private var searchJob: Job? = null
    private var tagLoadJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var statusClearJob: Job? = null
    private var queueTagJob: Job? = null
    /** Paths already ID3-enriched for the current queue session (avoid re-download loops). */
    private val queueTagsDone = mutableSetOf<String>()
    /** Tracks where the user declined an online lyrics lookup this session. */
    private val onlineLyricsDeclined = mutableSetOf<String>()
    private val libraryTrackById = MutableStateFlow<Map<String, TrackEntity>>(emptyMap())
    private val libraryTrackByPath = MutableStateFlow<Map<String, TrackEntity>>(emptyMap())

    val playlistCoverCache = playlistCoverStore.covers

    fun toggleLyrics() {
        val turningOn = !_lyricsVisible.value
        _lyricsVisible.value = turningOn
        if (turningOn) {
            playerState.value.track?.let { reloadLyrics(it) }
        }
    }

    fun acceptOnlineLyrics() {
        val track = playerState.value.track ?: return
        if (_lyrics.value.isNotEmpty()) return
        val key = track.id.ifBlank { track.nasPath }
        onlineLyricsDeclined.remove(key)
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewModelScope.launch {
            _lyricsStatus.value = LyricsStatus.FetchingOnline
            val tags = _trackTags.value
            val durationMs = playerState.value.durationMs.takeIf { it > 0 }
                ?: track.durationMs
            val result = runCatching {
                lyricsRepository.getOnlineLyrics(
                    track = track,
                    titleOverride = tags?.title,
                    artistOverride = tags?.artist,
                    albumOverride = tags?.album,
                    durationMsOverride = durationMs,
                )
            }.onFailure {
                android.util.Log.e("PallasLyrics", "online lyrics threw", it)
            }.getOrDefault(
                com.vizvag.shieldvideo.music.data.lyrics.LyricsLoadResult(emptyList()),
            )
            val cur = playerController.uiState.value.track
            if (cur?.id != track.id &&
                cur?.nasPath?.replace('\\', '/') != track.nasPath.replace('\\', '/')
            ) {
                return@launch
            }
            if (result.lines.isNotEmpty()) {
                _lyrics.value = result.lines
                _lyricsPath.value = result.lyricsPath
                _lyricsStatus.value = LyricsStatus.Available
            } else {
                _lyrics.value = emptyList()
                _lyricsPath.value = null
                _lyricsStatus.value = LyricsStatus.NotFound
            }
        }
    }

    fun declineOnlineLyrics() {
        val track = playerState.value.track ?: return
        onlineLyricsDeclined += track.id.ifBlank { track.nasPath }
        _lyricsStatus.value = LyricsStatus.NotFound
    }

    fun nudgeLyricsOffset(deltaMs: Long) {
        _lyricsOffsetMs.value =
            (_lyricsOffsetMs.value + deltaMs).coerceIn(-30_000L, 30_000L)
    }

    fun resetLyricsOffset() {
        _lyricsOffsetMs.value = 0L
    }

    private fun reloadLyrics(track: TrackEntity) {
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewModelScope.launch {
            _lyricsStatus.value = LyricsStatus.Loading
            val result = runCatching { lyricsRepository.getLyricsForTrack(track) }
                .onFailure {
                    android.util.Log.e("PallasLyrics", "lyrics load threw", it)
                }
                .getOrDefault(
                    com.vizvag.shieldvideo.music.data.lyrics.LyricsLoadResult(emptyList()),
                )
            val cur = playerController.uiState.value.track
            if (cur?.id != track.id &&
                cur?.nasPath?.replace('\\', '/') != track.nasPath.replace('\\', '/')
            ) {
                return@launch
            }
            if (result.lines.isNotEmpty()) {
                _lyrics.value = result.lines
                _lyricsPath.value = result.lyricsPath
                _lyricsStatus.value = LyricsStatus.Available
            } else {
                _lyrics.value = emptyList()
                _lyricsPath.value = null
                val key = track.id.ifBlank { track.nasPath }
                _lyricsStatus.value = when {
                    !_lyricsVisible.value -> LyricsStatus.Idle
                    key in onlineLyricsDeclined -> LyricsStatus.NotFound
                    else -> LyricsStatus.OfferOnline
                }
            }
        }
    }

    fun currentLyricIndex(positionMs: Long = playerState.value.positionMs): Int =
        com.vizvag.shieldvideo.music.data.lyrics.LrcParser.currentLineIndex(_lyrics.value, positionMs)

    enum class LyricsStatus {
        Idle,
        Loading,
        Available,
        OfferOnline,
        FetchingOnline,
        NotFound,
    }

    init {
        musicSettings.refresh()
        // Do NOT call musicIndex.ensureFresh here — opening Music must not re-pull
        // the NAS index. App start + the background loop keep the Room cache fresh;
        // the in-memory MusicLibraryCache is already warm for instant search.
        viewModelScope.launch {
            // Position ticks must NOT clear art — only react when the track identity changes.
            playerState
                .map { it.track }
                .distinctUntilChangedBy { it?.id }
                .collect { track ->
                    if (track == null) {
                        _coverUrl.value = null
                        _trackArtUrl.value = null
                        _artDetails.value = ArtDetails()
                        _trackTags.value = null
                        _lyrics.value = emptyList()
                        _lyricsPath.value = null
                        _lyricsStatus.value = LyricsStatus.Idle
                        _lyricsOffsetMs.value = 0L
                        tagLoadJob?.cancel()
                        lyricsLoadJob?.cancel()
                        return@collect
                    }
                    // Drop previous track art immediately so the UI never sticks on the old cover.
                    _coverUrl.value = null
                    _trackArtUrl.value = null
                    _trackTags.value = TrackTagInfo.fromTrack(track)
                    _artDetails.value = ArtDetails(
                        localCoverPath = track.nasPath.substringBeforeLast('/') + "/folder.jpg",
                    )
                    _lyrics.value = emptyList()
                    _lyricsPath.value = null
                    _lyricsStatus.value = LyricsStatus.Idle
                    _lyricsOffsetMs.value = 0L
                    reloadLyrics(track)
                    tagLoadJob?.cancel()
                    tagLoadJob = viewModelScope.launch {
                        // Live ID3 first — display tags from the file, not the path/filename.
                        val rich = runCatching {
                            libraryRepository.readTagsForPath(track.nasPath)
                        }.onFailure {
                            android.util.Log.w(
                                "PallasMusic",
                                "ID3 read failed for ${track.nasPath}: ${it.message}",
                            )
                        }.getOrNull()
                        if (rich == null) {
                            android.util.Log.w(
                                "PallasMusic",
                                "ID3 empty/unreadable for ${track.nasPath}",
                            )
                        }
                        fun stillCurrent(): Boolean {
                            val cur = playerController.uiState.value.track ?: return false
                            return cur.id == track.id ||
                                cur.nasPath.replace('\\', '/') == track.nasPath.replace('\\', '/')
                        }
                        if (!stillCurrent()) return@launch

                        val mr = com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
                        if (rich != null &&
                            !rich.title.isNullOrBlank() &&
                            !mr.isPlaceholderArtist(rich.artist)
                        ) {
                            // ID3 present — use it as-is. Do not humanize, split, or online-rewrite.
                            _trackTags.value = TrackTagInfo.fromTrack(track).mergePreferringRich(rich)
                            val enriched = libraryRepository.mergeTagsIntoTrack(track, rich)
                            playerController.applyTrackMetadata(enriched)
                            runCatching { libraryRepository.upsertTrack(enriched) }
                        } else {
                            val display = if (rich != null) {
                                TrackTagInfo.fromTrack(track).mergePreferringRich(rich)
                            } else {
                                TrackTagInfo.fromTrack(track)
                            }
                            val recoveredArtist = mr.resolveDisplayArtist(
                                artist = display.artist,
                                albumArtist = display.albumArtist ?: track.albumArtist,
                                nasPath = track.nasPath,
                                albumTitle = display.album ?: track.albumTitle,
                            )
                            val displayFixed = if (
                                recoveredArtist != null &&
                                mr.isPlaceholderArtist(display.artist)
                            ) {
                                display.copy(artist = recoveredArtist)
                            } else {
                                display
                            }
                            _trackTags.value = displayFixed
                            if (rich != null || (
                                    recoveredArtist != null &&
                                        mr.isPlaceholderArtist(track.artistName)
                                    )
                            ) {
                                val enriched = libraryRepository.mergeTagsIntoTrack(
                                    track,
                                    displayFixed,
                                )
                                playerController.applyTrackMetadata(enriched)
                                if (!mr.isPlaceholderArtist(enriched.artistName)) {
                                    runCatching { libraryRepository.upsertTrack(enriched) }
                                }
                            }
                        }

                        val latest = _trackTags.value
                        val trackArtist = mr.resolveDisplayArtist(
                            artist = latest?.artist,
                            albumArtist = latest?.albumArtist ?: track.albumArtist,
                            nasPath = track.nasPath,
                            albumTitle = latest?.album ?: track.albumTitle,
                        ) ?: track.artistName
                        val albumArtist = latest?.albumArtist?.takeIf {
                            it.isNotBlank() && !mr.isPlaceholderArtist(it)
                        } ?: track.albumArtist?.takeIf {
                            it.isNotBlank() && !mr.isPlaceholderArtist(it)
                        }
                        val trackTitle = latest?.title?.takeIf { it.isNotBlank() } ?: track.title
                        val albumTitle = latest?.album?.takeIf { it.isNotBlank() } ?: track.albumTitle

                        val folder = track.nasPath.substringBeforeLast('/')
                        val albumCover = runCatching {
                            libraryRepository.getAlbum(track.albumId)?.coverPath
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        val artPath = albumCover ?: "$folder/folder.jpg"

                        // Authenticated NAS download → disk file Coil can actually display.
                        val preferLocal = albumArtLookup.folderLooksLikeAlbum(track.nasPath, albumTitle)
                        val localFile = if (preferLocal) {
                            runCatching { coverArtCache.coverFileForTrack(track) }.getOrNull()
                        } else {
                            if (albumTitle.isBlank()) {
                                runCatching { coverArtCache.coverFileForTrack(track) }.getOrNull()
                            } else {
                                null
                            }
                        }
                        if (localFile != null && stillCurrent()) {
                            _coverUrl.value = localFile
                            if (_trackArtUrl.value == null) {
                                _trackArtUrl.value = localFile
                            }
                        }

                        var resolvedAlbum: String? = null
                        var resolvedTrackArt: String? = null

                        val albumJob = launch {
                            resolvedAlbum = albumArtLookup.resolveCoverUrl(
                                localUrl = null,
                                artist = trackArtist,
                                album = albumTitle,
                                trackTitle = trackTitle,
                                albumArtist = albumArtist,
                                nasPath = track.nasPath,
                            )
                        }
                        val trackJob = launch {
                            resolvedTrackArt = albumArtLookup.resolveTrackArtUrl(
                                artist = trackArtist,
                                trackTitle = trackTitle,
                            )
                        }
                        albumJob.join()
                        trackJob.join()
                        if (!stillCurrent()) return@launch

                        val albumSource = when {
                            localFile != null -> "Local NAS · $artPath"
                            else -> describeArtSource(resolvedAlbum, null, artPath)
                        }
                        val trackSource = when {
                            !resolvedTrackArt.isNullOrBlank() ->
                                describeArtSource(resolvedTrackArt, null, null)
                            localFile != null -> "Same as album · Local NAS"
                            !resolvedAlbum.isNullOrBlank() ->
                                "Same as album · ${describeArtSource(resolvedAlbum, null, artPath)}"
                            else -> "Not found"
                        }

                        if (localFile == null && !resolvedAlbum.isNullOrBlank()) {
                            _coverUrl.value = resolvedAlbum
                        }
                        when {
                            !resolvedTrackArt.isNullOrBlank() ->
                                _trackArtUrl.value = resolvedTrackArt
                            _trackArtUrl.value == null && localFile != null ->
                                _trackArtUrl.value = localFile
                            _trackArtUrl.value == null && !resolvedAlbum.isNullOrBlank() ->
                                _trackArtUrl.value = resolvedAlbum
                            _trackArtUrl.value == null ->
                                _trackArtUrl.value = _coverUrl.value
                        }
                        _artDetails.value = ArtDetails(
                            albumArtUrl = artModelLabel(_coverUrl.value),
                            albumArtSource = albumSource,
                            trackArtUrl = artModelLabel(_trackArtUrl.value),
                            trackArtSource = trackSource,
                            localCoverPath = artPath,
                        )
                    }
                }
        }

        // Playlist / queue: live-read ID3 for every queued track (not only now-playing).
        viewModelScope.launch {
            tracks.collect { list ->
                libraryTrackById.value = list.associateBy { it.id }
                libraryTrackByPath.value = buildLibraryPathIndex(list)
            }
        }
        viewModelScope.launch {
            combine(queue, tracks) { queued, library -> queued to library }
                .collect { (queued, library) ->
                    if (queued.isEmpty() || library.isEmpty()) return@collect
                    repairQueueDurations(queued)
                }
        }
        viewModelScope.launch {
            // Prefer the sharp track tile (same art playlist rows use), not album backdrop.
            combine(trackArtUrl, coverUrl, playerState) { trackArt, albumArt, state ->
                Triple(state.track, trackArt ?: albumArt, state)
            }.collect { (track, art, _) ->
                if (track != null && art != null) playlistCoverStore.putCover(track, art)
            }
        }
        viewModelScope.launch {
            queueManager.queue
                .map { list -> list.map { it.nasPath.replace('\\', '/') } }
                .distinctUntilChanged()
                .collect { paths ->
                    queueTagsDone.retainAll(paths.toSet())
                    queueTagJob?.cancel()
                    queueTagJob = viewModelScope.launch {
                        val snapshot = queueManager.queue.value
                        for (track in snapshot) {
                            val pathKey = track.nasPath.replace('\\', '/')
                            if (pathKey in queueTagsDone) continue
                            val rich = runCatching {
                                libraryRepository.readTagsForPath(track.nasPath)
                            }.getOrNull()
                            if (rich == null || rich.title.isNullOrBlank()) {
                                queueTagsDone.add(pathKey)
                                continue
                            }
                            val enriched = libraryRepository.mergeTagsIntoTrack(track, rich)
                            queueManager.updateTrackMetadata(enriched)
                            runCatching { libraryRepository.upsertTrack(enriched) }
                            queueTagsDone.add(pathKey)
                            val cur = playerController.uiState.value.track
                            if (cur != null && (
                                    cur.id == track.id ||
                                        cur.nasPath.replace('\\', '/') == pathKey
                                    )
                            ) {
                                playerController.applyTrackMetadata(enriched)
                                _trackTags.value =
                                    TrackTagInfo.fromTrack(enriched).mergePreferringRich(rich)
                            }
                        }
                    }
                }
        }
    }

    private fun artModelLabel(model: Any?): String? = when (model) {
        is String -> model.takeIf { it.isNotBlank() }
        is java.io.File -> model.absolutePath
        else -> model?.toString()
    }

    private fun flashStatus(message: String) {
        _statusMessage.value = message
        statusClearJob?.cancel()
        statusClearJob = viewModelScope.launch {
            delay(3500)
            if (_statusMessage.value == message) _statusMessage.value = null
        }
    }

    private fun refreshHueSyncState() {
        _hueSyncEnabled.value = settingsRepository.isHueSyncEnabled()
        _hueSyncReady.value = settingsRepository.isHueSyncReady()
    }

    /** Toggle Music/Radio Hue sync (persists immediately). Hint if bridge/lights not set up. */
    fun toggleHueSync() {
        val next = settingsRepository.toggleHueSync()
        if (next == null) {
            flashStatus("Set up Hue in Settings → Integrations")
            return
        }
        _hueSyncEnabled.value = next
        _hueSyncReady.value = true
        flashStatus(if (next) "Hue sync on" else "Hue sync off")
    }

    fun testConnection(settings: NasSettings) {
        viewModelScope.launch {
            _connectionMessage.value = synologyApiClient.testConnection(settings)
                .fold({ it }, { "Failed: ${it.message}" })
        }
    }

    fun indexNow() {
        musicIndex.syncNow()
    }

    fun playTrack(track: TrackEntity) {
        viewModelScope.launch {
            runCatching {
                com.vizvag.shieldvideo.playback.remote.RemotePlayBridge.playMusicTracks(listOf(track)) {
                    playerController.playTracks(listOf(track))
                }
            }.onFailure { flashStatus(it.message ?: "Remote play failed") }
        }
    }

    /** Play a NAS file — resolve index/ID3 metadata before starting (not the filename). */
    fun playFileEntry(entry: FileEntry) {
        viewModelScope.launch {
            val track = runCatching { libraryRepository.resolveTrack(entry) }
                .getOrElse { libraryRepository.trackFromFileEntry(entry) }
            runCatching {
                com.vizvag.shieldvideo.playback.remote.RemotePlayBridge.playMusicTracks(listOf(track)) {
                    playerController.playTracks(listOf(track))
                }
            }.onFailure { flashStatus(it.message ?: "Remote play failed") }
        }
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val device = RemoteTargetStore.current()
                if (device != null) {
                    val refs = tracks.map {
                        MusicTrackRef(
                            id = it.id,
                            nasPath = it.nasPath,
                            title = it.title,
                            artistName = it.artistName,
                            albumTitle = it.albumTitle,
                            durationMs = it.durationMs,
                        )
                    }
                    val status = ShieldVideoApp.instance.remoteClient
                        .playMusic(device, refs, startIndex)
                        .getOrThrow()
                    _remoteStatus.value = status
                    RemoteStatusPoller.publish(status)
                    RemoteStatusPoller.kick()
                } else {
                    playerController.playTracks(tracks, startIndex)
                }
            }.onFailure { flashStatus(it.message ?: "Remote play failed") }
        }
    }

    fun playArtist(artist: ArtistEntity) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksForArtistBrowse(artist)
            if (tracks.isNotEmpty()) playTracks(tracks)
            else flashStatus("No tracks for ${artist.name}")
        }
    }

    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByAlbum(albumId)
            if (tracks.isNotEmpty()) playTracks(tracks)
            else flashStatus("No tracks in album")
        }
    }

    fun playFolder(folderPath: String) {
        viewModelScope.launch {
            flashStatus("Loading folder…")
            val tracks = runCatching { libraryRepository.getTracksUnderFolder(folderPath) }
                .getOrElse {
                    flashStatus("Failed: ${it.message}")
                    return@launch
                }
            if (tracks.isEmpty()) {
                flashStatus("No audio files in this folder")
                return@launch
            }
            playTracks(tracks)
            flashStatus("Playing ${tracks.size} tracks")
        }
    }

    fun addFolderToPlaylist(folderPath: String, replace: Boolean = false) {
        viewModelScope.launch {
            flashStatus(if (replace) "Replacing playlist…" else "Adding folder…")
            val tracks = runCatching { libraryRepository.getTracksUnderFolder(folderPath) }
                .getOrElse {
                    flashStatus("Failed: ${it.message}")
                    return@launch
                }
            if (replace) {
                if (tracks.isEmpty()) {
                    flashStatus("No audio files in this folder")
                    return@launch
                }
                playTracks(tracks)
                flashStatus("Playlist cleared · ${tracks.size} tracks")
            } else {
                enqueueAndMaybePlay(tracks)
            }
        }
    }

    fun addAlbumToPlaylist(albumId: String, replace: Boolean = false) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByAlbum(albumId)
            android.util.Log.i(
                "PallasMusic",
                "addAlbum order: " + tracks.take(12).joinToString { t ->
                    "D${t.discNumber ?: 1}/T${t.trackNumber ?: '?'} ${t.title}"
                },
            )
            if (replace) {
                if (tracks.isEmpty()) {
                    flashStatus("No tracks in album")
                    return@launch
                }
                playTracks(tracks)
                flashStatus("Playlist cleared · ${tracks.size} tracks")
            } else {
                enqueueAndMaybePlay(tracks)
            }
        }
    }

    fun addTrackToPlaylist(track: TrackEntity, replace: Boolean = false) {
        viewModelScope.launch {
            if (replace) {
                playTracks(listOf(track))
                flashStatus("Playlist cleared · 1 track")
            } else {
                enqueueAndMaybePlay(listOf(track))
            }
        }
    }

    fun addArtistToPlaylist(artist: ArtistEntity, replace: Boolean = false) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksForArtistBrowse(artist)
            if (replace) {
                if (tracks.isEmpty()) {
                    flashStatus("No tracks for ${artist.name}")
                    return@launch
                }
                playTracks(tracks)
                flashStatus("Playlist cleared · ${tracks.size} tracks")
            } else {
                enqueueAndMaybePlay(tracks)
            }
        }
    }

    fun addFileEntryToPlaylist(entry: FileEntry, replace: Boolean = false) {
        viewModelScope.launch {
            val track = runCatching { libraryRepository.resolveTrack(entry) }
                .getOrElse { libraryRepository.trackFromFileEntry(entry) }
            if (replace) {
                playTracks(listOf(track))
                flashStatus("Playlist cleared · 1 track")
            } else {
                enqueueAndMaybePlay(listOf(track))
            }
        }
    }

    fun downloadTrackToDevice(track: TrackEntity) {
        downloadPathsToDevice(listOf(track.nasPath), label = track.title)
    }

    fun downloadAlbumToDevice(albumId: String) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByAlbum(albumId)
            if (tracks.isEmpty()) {
                flashStatus("No tracks in album")
                return@launch
            }
            downloadPathsToDevice(tracks.map { it.nasPath }, label = tracks.first().albumTitle)
        }
    }

    fun downloadArtistToDevice(artist: ArtistEntity) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksForArtistBrowse(artist)
            if (tracks.isEmpty()) {
                flashStatus("No tracks for ${artist.name}")
                return@launch
            }
            downloadPathsToDevice(tracks.map { it.nasPath }, label = artist.name)
        }
    }

    fun downloadFolderToDevice(folderPath: String) {
        viewModelScope.launch {
            flashStatus("Preparing download…")
            val tracks = runCatching { libraryRepository.getTracksUnderFolder(folderPath) }
                .getOrElse {
                    flashStatus("Failed: ${it.message}")
                    return@launch
                }
            if (tracks.isEmpty()) {
                flashStatus("No audio files in this folder")
                return@launch
            }
            downloadPathsToDevice(
                tracks.map { it.nasPath },
                label = folderPath.substringAfterLast('/'),
            )
        }
    }

    fun downloadFileEntryToDevice(entry: FileEntry) {
        downloadPathsToDevice(listOf(entry.path), label = entry.name)
    }

    private fun downloadPathsToDevice(paths: List<String>, label: String) {
        val unique = paths
            .map { it.replace('\\', '/').trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (unique.isEmpty()) {
            flashStatus("Nothing to download")
            return
        }
        viewModelScope.launch {
            val appCtx = ShieldVideoApp.instance.applicationContext
            var ok = 0
            var fail = 0
            unique.forEachIndexed { i, path ->
                flashStatus("Downloading ${i + 1}/${unique.size}…")
                runCatching {
                    com.vizvag.shieldvideo.music.data.MusicDownloadStore.downloadNasFile(
                        context = appCtx,
                        api = synologyApiClient,
                        nasPath = path,
                    )
                }.onSuccess {
                    ok++
                }.onFailure {
                    android.util.Log.w("PallasMusic", "Download failed for $path: ${it.message}")
                    fail++
                }
            }
            flashStatus(
                when {
                    ok == 0 -> "Download failed${if (fail > 0) " ($fail)" else ""}"
                    fail == 0 && ok == 1 -> "Saved to Music · $label"
                    fail == 0 -> "Saved $ok tracks to Music"
                    else -> "Saved $ok · $fail failed"
                },
            )
        }
    }

    /**
     * Append tracks to the playlist. If nothing is currently playing (or the
     * queue was empty), start playback at the first newly added track.
     * Leaving Music stops audio but keeps the queue — so "add" must still start
     * when the player is idle.
     */
    private suspend fun enqueueAndMaybePlay(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) {
            flashStatus("Nothing to add — no audio found")
            return
        }
        runCatching {
            val device = RemoteTargetStore.current()
            if (device != null) {
                val refs = tracks.map {
                    MusicTrackRef(
                        id = it.id,
                        nasPath = it.nasPath,
                        title = it.title,
                        artistName = it.artistName,
                        albumTitle = it.albumTitle,
                        durationMs = it.durationMs,
                    )
                }
                val status = ShieldVideoApp.instance.remoteClient
                    .musicQueue(device, MusicQueueAction.Add, tracks = refs)
                    .getOrThrow()
                _remoteStatus.value = status
            } else {
                val wasEmpty = queueManager.queue.value.isEmpty()
                val insertAt = queueManager.queue.value.size
                // Idle = nothing prepared (left Music / never started). Track metadata may
                // still be set so the UI can show the current song while stopped.
                val idle = !playerController.uiState.value.isPlaying &&
                    playerController.player.mediaItemCount == 0
                queueManager.addAllToEnd(tracks)
                queueManager.persist()
                if (wasEmpty || idle) {
                    playerController.playQueueIndex(if (wasEmpty) 0 else insertAt)
                }
            }
        }.onFailure {
            flashStatus(it.message ?: "Remote queue failed")
            return
        }
        flashStatus("Added ${tracks.size} to playlist")
    }

    fun playRandomAlbum() {
        viewModelScope.launch {
            val album = albums.value.randomOrNull() ?: return@launch
            val tracks = libraryRepository.getTracksByAlbum(album.albumId)
            if (tracks.isNotEmpty()) playTracks(tracks)
        }
    }

    fun playRandomArtist() {
        viewModelScope.launch {
            val artist = artists.value.randomOrNull() ?: return@launch
            val tracks = libraryRepository.getTracksByArtist(artist.id)
            if (tracks.isNotEmpty()) playTracks(tracks)
        }
    }

    fun playRandomGenre() {
        viewModelScope.launch {
            val tagged = albums.value.filter { !it.genre.isNullOrBlank() }
            val byGenre = if (tagged.isNotEmpty()) {
                tagged.groupBy { it.genre!! }
            } else {
                val roots = settings.value.musicPaths
                albums.value
                    .mapNotNull { album ->
                        val root = NasSettings.rootForPath(album.folderPath.orEmpty(), roots)
                        libraryRepository.genreLabel(album.folderPath, root)?.let { it to album }
                    }
                    .groupBy({ it.first }, { it.second })
            }
            val genre = byGenre.keys.randomOrNull()
            val tracks = if (genre != null) {
                byGenre.getValue(genre).flatMap { libraryRepository.getTracksByAlbum(it.albumId) }
            } else {
                val album = albums.value.randomOrNull() ?: return@launch
                libraryRepository.getTracksByAlbum(album.albumId)
            }
            if (tracks.isNotEmpty()) playTracks(tracks.shuffled())
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                ShieldVideoApp.instance.remoteClient
                    .musicQueue(device, MusicQueueAction.Clear)
                    .onSuccess { _remoteStatus.value = it }
                return@launch
            }
            playerController.stop()
            queueManager.clear()
            queueManager.persist()
        }
    }

    fun shufflePlaylist() {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                val q = queue.value
                if (q.isEmpty()) {
                    flashStatus("Playlist is empty")
                    return@launch
                }
                val currentId = q.getOrNull(queueIndex.value)?.id
                val current = q.firstOrNull { it.id == currentId }
                val rest = q.filter { it.id != currentId }.shuffled()
                val next = if (current != null) listOf(current) + rest else rest
                playTracks(next, 0)
                return@launch
            }
            queueManager.shuffleKeepingCurrent()
            queueManager.persist()
        }
    }

    /** Clear playlist and play [count] random library tracks. */
    fun playRandomTracks(count: Int = 100) {
        viewModelScope.launch {
            val all = libraryRepository.getAllTracks()
            if (all.isEmpty()) {
                flashStatus("No songs in library")
                return@launch
            }
            val picks = all.shuffled().take(count.coerceAtLeast(1))
            playTracks(picks)
            flashStatus("Random · ${picks.size} tracks")
        }
    }

    fun movePlaylistItem(from: Int, to: Int) {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                ShieldVideoApp.instance.remoteClient
                    .musicQueue(device, MusicQueueAction.Move, from = from, to = to)
                    .onSuccess { _remoteStatus.value = it }
                return@launch
            }
            queueManager.move(from, to)
            queueManager.persist()
        }
    }

    fun removeFromPlaylist(index: Int) {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                ShieldVideoApp.instance.remoteClient
                    .musicQueue(device, MusicQueueAction.Remove, index = index)
                    .onSuccess { _remoteStatus.value = it }
                return@launch
            }
            val current = queueManager.currentIndex.value
            queueManager.removeAt(index)
            queueManager.persist()
            when {
                queueManager.queue.value.isEmpty() -> playerController.stop()
                index == current -> {
                    val track = queueManager.currentTrack
                    if (track != null) playerController.playTracks(queueManager.queue.value, queueManager.currentIndex.value)
                    else playerController.stop()
                }
            }
        }
    }

    fun stopPlayback() {
        val device = RemoteTargetStore.current()
        if (device != null) {
            viewModelScope.launch {
                ShieldVideoApp.instance.remoteClient
                    .transport(device, TransportAction.Stop)
                    .onSuccess { _remoteStatus.value = it }
            }
            return
        }
        playerController.stop()
    }

    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = com.vizvag.shieldvideo.music.data.SearchResults()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(280)
            _searchResults.value = libraryRepository.search(query)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = com.vizvag.shieldvideo.music.data.SearchResults()
    }

    fun togglePlayPause() {
        val device = RemoteTargetStore.current()
        if (device != null) {
            viewModelScope.launch {
                val client = ShieldVideoApp.instance.remoteClient
                // Use last polled status — do not block play on an extra status round-trip.
                val remote = _remoteStatus.value
                val queueSize = remote?.queue?.size ?: 0
                val looksMusic = remote != null && remoteLooksLikeMusic(remote)
                when {
                    queueSize > 0 || (looksMusic && !remote.title.isNullOrBlank()) -> {
                        val action = when {
                            remote?.isPlaying == true -> TransportAction.Pause
                            else -> TransportAction.Play
                        }
                        client.transport(device, action)
                            .onSuccess {
                                _remoteStatus.value = it
                                RemoteStatusPoller.publish(it)
                                RemoteStatusPoller.kick()
                            }
                            .onFailure { flashStatus(it.message ?: "Remote play failed") }
                    }
                    else -> {
                        // Stale/empty local view of the room — still try Toggle, then refresh.
                        client.transport(device, TransportAction.Toggle)
                            .onSuccess {
                                _remoteStatus.value = it
                                RemoteStatusPoller.publish(it)
                                RemoteStatusPoller.kick()
                                if (it.mode != RemotePlaybackMode.Music && it.queue.isEmpty()) {
                                    flashStatus(
                                        "Browse or Random to play on ${device.deviceId.ifBlank { "this room" }}",
                                    )
                                }
                            }
                            .onFailure { flashStatus(it.message ?: "Remote play failed") }
                    }
                }
            }
            return
        }
        playerController.togglePlayPause()
    }

    fun seekTo(ms: Long) {
        val device = RemoteTargetStore.current()
        if (device != null) {
            viewModelScope.launch {
                ShieldVideoApp.instance.remoteClient
                    .transport(device, TransportAction.Seek, positionMs = ms)
                    .onSuccess {
                        _remoteStatus.value = it
                        RemoteStatusPoller.publish(it)
                        RemoteStatusPoller.kick()
                    }
            }
            return
        }
        playerController.seekTo(ms)
    }

    fun seekBy(deltaMs: Long) {
        val state = playerState.value
        val duration = state.durationMs.takeIf { it > 0 }
            ?: state.track?.durationMs
            ?: Long.MAX_VALUE
        val next = (state.positionMs + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        seekTo(next)
    }

    fun skipNext() {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                ShieldVideoApp.instance.remoteClient
                    .transport(device, TransportAction.Next)
                    .onSuccess {
                        _remoteStatus.value = it
                        RemoteStatusPoller.publish(it)
                        RemoteStatusPoller.kick()
                    }
                return@launch
            }
            playerController.playNext()
        }
    }

    fun skipPrevious() {
        viewModelScope.launch {
            val device = RemoteTargetStore.current()
            if (device != null) {
                ShieldVideoApp.instance.remoteClient
                    .transport(device, TransportAction.Previous)
                    .onSuccess {
                        _remoteStatus.value = it
                        RemoteStatusPoller.publish(it)
                        RemoteStatusPoller.kick()
                    }
                return@launch
            }
            playerController.playPrevious()
        }
    }

    suspend fun listFolder(path: String) = libraryRepository.listNasFolder(path)

    fun playQueueIndex(index: Int) {
        val device = RemoteTargetStore.current()
        if (device != null) {
            viewModelScope.launch {
                ShieldVideoApp.instance.remoteClient
                    .musicQueue(device, MusicQueueAction.PlayIndex, index = index)
                    .onSuccess {
                        _remoteStatus.value = it
                        RemoteStatusPoller.publish(it)
                        RemoteStatusPoller.kick()
                    }
            }
            return
        }
        playerController.playQueueIndex(index)
    }

    /** True when this Music UI is driving another device on the LAN. */
    fun isRemoteSession(): Boolean = RemoteTargetStore.isControllingRemote()

    private fun remoteLooksLikeMusic(remote: RemoteStatus): Boolean =
        remote.mode == RemotePlaybackMode.Music || remote.queue.isNotEmpty()

    private fun RemoteStatus.toPlayerUiState(): PlayerUiState {
        val item = queue.getOrNull(queueIndex) ?: queue.firstOrNull()
        val track = item?.toTrackEntity()
            ?: TrackEntity(
                id = "remote-now",
                albumId = "",
                artistId = "",
                title = title.ifBlank { "Remote" },
                artistName = subtitle,
                albumTitle = "",
                durationMs = durationMs,
                nasPath = "",
            )
        return PlayerUiState(
            track = track,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs.takeIf { it > 0 } ?: track.durationMs,
        )
    }

    private fun RemoteQueueItem.toTrackEntity(): TrackEntity =
        TrackEntity(
            id = id.ifBlank { nasPath.ifBlank { title } },
            albumId = "",
            artistId = "",
            title = title,
            artistName = artist,
            albumTitle = "",
            durationMs = durationMs,
            nasPath = nasPath,
        )

    suspend fun getArtist(id: String) = libraryRepository.getArtist(id)

    suspend fun getAlbum(id: String) = libraryRepository.getAlbum(id)

    /** Per-track duration for playlist rows — file size + bitrate beats stored metadata. */
    fun resolveQueueTrackDuration(track: TrackEntity): Long {
        val library = findLibraryTrack(track)
        return bestTrackDurationMs(library ?: track)
            .takeIf { it > 0 }
            ?: bestTrackDurationMs(track)
    }

    private fun findLibraryTrack(track: TrackEntity): TrackEntity? {
        libraryTrackById.value[track.id]?.let { return it }
        for (key in trackPathKeys(track.nasPath)) {
            libraryTrackByPath.value[key]?.let { return it }
        }
        return null
    }

    private fun bestTrackDurationMs(track: TrackEntity): Long {
        estimateTrackDurationMs(track).takeIf { it > 0 }?.let { return it }
        return track.durationMs.coerceAtLeast(0L)
    }

    private fun estimateTrackDurationMs(track: TrackEntity): Long {
        val bitrate = track.bitrateKbps?.takeIf { it > 0 } ?: return 0L
        val size = track.fileSize.takeIf { it > 0 } ?: return 0L
        return (size * 8L * 1000L) / (bitrate * 1000L)
    }

    private fun trackPathKeys(path: String): List<String> {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return emptyList()
        return linkedSetOf(
            normalized.lowercase(),
            normalized.trimStart('/').lowercase(),
            "/${normalized.trimStart('/')}".lowercase(),
        ).toList()
    }

    private fun buildLibraryPathIndex(tracks: List<TrackEntity>): Map<String, TrackEntity> {
        val out = LinkedHashMap<String, TrackEntity>(tracks.size * 3)
        for (track in tracks) {
            for (key in trackPathKeys(track.nasPath)) {
                out.putIfAbsent(key, track)
            }
        }
        return out
    }

    private fun repairQueueDurations(current: List<TrackEntity>) {
        var changed = false
        val repaired = current.map { track ->
            val lib = findLibraryTrack(track) ?: return@map track
            val duration = bestTrackDurationMs(lib).takeIf { it > 0 } ?: track.durationMs
            val merged = track.copy(
                fileSize = lib.fileSize.takeIf { it > 0 } ?: track.fileSize,
                bitrateKbps = lib.bitrateKbps ?: track.bitrateKbps,
                durationMs = duration,
            )
            if (merged != track) changed = true
            merged
        }
        if (changed) {
            queueManager.replaceAll(repaired)
            viewModelScope.launch { queueManager.persist() }
        }
    }

    /** Local NAS folder cover as a Coil [java.io.File] model (authenticated download + cache). */
    suspend fun resolveLocalCoverModel(track: TrackEntity): Any? =
        runCatching { coverArtCache.coverFileForTrack(track) }.getOrNull()

    suspend fun getTrackByPath(path: String) = libraryRepository.getTrackByPath(path)

    fun trackFromFileEntry(entry: FileEntry): TrackEntity =
        libraryRepository.trackFromFileEntry(entry)

    fun libraryAlbumsForArtist(artistId: String) =
        libraryRepository.observeAlbumsByArtist(artistId)

    fun libraryAlbumsForYear(year: Int) =
        libraryRepository.observeAlbumsByYear(year)

    fun libraryAlbumsForGenre(genre: String) =
        libraryRepository.observeAlbumsByGenre(genre)

    fun libraryAlbumsForComposer(composer: String) =
        libraryRepository.observeAlbumsByComposer(composer)

    fun libraryAlbumsForAlbumArtist(albumArtist: String) =
        libraryRepository.observeAlbumsByAlbumArtist(albumArtist)

    fun libraryAlbumsForMood(mood: String) =
        libraryRepository.observeAlbumsByMood(mood)

    fun libraryAlbumsForGrouping(grouping: String) =
        libraryRepository.observeAlbumsByGrouping(grouping)

    fun libraryTracksForAlbum(albumId: String) =
        libraryRepository.observeTracksByAlbum(albumId)

    fun libraryTracksForArtist(artistId: String) =
        libraryRepository.observeTracksByArtist(artistId)

    companion object {
        fun describeArtSource(
            url: String?,
            localUrl: String?,
            localPath: String?,
        ): String {
            if (url.isNullOrBlank()) return "Not found"
            if (!localUrl.isNullOrBlank() && url == localUrl) {
                return "Local NAS · ${localPath ?: "folder cover"}"
            }
            val u = url.lowercase()
            return when {
                "coverartarchive" in u || "archive.org" in u -> "Cover Art Archive / MusicBrainz"
                "deezer" in u -> "Deezer"
                "mzstatic" in u || "itunes" in u || "apple.com" in u -> "iTunes"
                "webapi" in u || "entry.cgi" in u || "filestation" in u ->
                    "Local NAS · ${localPath ?: "cover"}"
                else -> "Remote"
            }
        }
    }
}

data class ArtDetails(
    val albumArtUrl: String? = null,
    val albumArtSource: String = "—",
    val trackArtUrl: String? = null,
    val trackArtSource: String = "—",
    val localCoverPath: String? = null,
)
