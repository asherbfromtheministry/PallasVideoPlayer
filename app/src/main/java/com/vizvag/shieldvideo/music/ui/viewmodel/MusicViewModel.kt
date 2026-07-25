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
import com.vizvag.shieldvideo.music.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel(
    private val libraryRepository: LibraryRepository,
    libraryCache: com.vizvag.shieldvideo.music.data.MusicLibraryCache,
    private val musicSettings: MusicSettingsBridge,
    private val synologyApiClient: SynologyApiClient,
    private val musicIndex: com.vizvag.shieldvideo.music.data.MusicIndexController,
    private val streamUrlBuilder: com.vizvag.shieldvideo.music.player.StreamUrlBuilder,
    private val queueManager: com.vizvag.shieldvideo.music.player.QueueManager,
    val playerController: PlayerController,
    private val albumArtLookup: com.vizvag.shieldvideo.music.data.metadata.AlbumArtLookup,
    private val coverArtCache: com.vizvag.shieldvideo.music.data.metadata.CoverArtCache,
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
    val playerState = playerController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        com.vizvag.shieldvideo.music.player.PlayerUiState(),
    )
    val queue = queueManager.queue.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )
    val queueIndex = queueManager.currentIndex.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), -1,
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

    private val _connectionMessage = MutableStateFlow<String?>(null)
    val connectionMessage = _connectionMessage.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow(com.vizvag.shieldvideo.music.data.SearchResults())
    val searchResults = _searchResults.asStateFlow()

    private var tagLoadJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var statusClearJob: Job? = null
    private var queueTagJob: Job? = null
    /** Paths already ID3-enriched for the current queue session (avoid re-download loops). */
    private val queueTagsDone = mutableSetOf<String>()

    fun toggleLyrics() {
        val turningOn = !_lyricsVisible.value
        _lyricsVisible.value = turningOn
        if (turningOn) {
            playerState.value.track?.let { reloadLyrics(it) }
        }
    }

    private fun reloadLyrics(track: TrackEntity) {
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewModelScope.launch {
            val result = runCatching { lyricsRepository.getLyricsForTrack(track) }
                .onFailure {
                    android.util.Log.e("PallasLyrics", "lyrics load threw", it)
                    flashStatus("Lyrics error: ${it.message ?: it.javaClass.simpleName}")
                }
                .getOrDefault(
                    com.vizvag.shieldvideo.music.data.lyrics.LyricsLoadResult(emptyList()),
                )
            val cur = playerController.uiState.value.track
            if (cur?.id == track.id ||
                cur?.nasPath?.replace('\\', '/') == track.nasPath.replace('\\', '/')
            ) {
                _lyrics.value = result.lines
                _lyricsPath.value = result.lyricsPath
                if (result.lines.isEmpty()) {
                    flashStatus("No lyrics file found")
                }
            }
        }
    }

    fun currentLyricIndex(positionMs: Long = playerState.value.positionMs): Int =
        com.vizvag.shieldvideo.music.data.lyrics.LrcParser.currentLineIndex(_lyrics.value, positionMs)

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
        playerController.playTracks(listOf(track))
    }

    /** Play a NAS file — resolve index/ID3 metadata before starting (not the filename). */
    fun playFileEntry(entry: FileEntry) {
        viewModelScope.launch {
            val track = runCatching { libraryRepository.resolveTrack(entry) }
                .getOrElse { libraryRepository.trackFromFileEntry(entry) }
            playerController.playTracks(listOf(track))
        }
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isNotEmpty()) playerController.playTracks(tracks, startIndex)
    }

    fun playArtist(artist: ArtistEntity) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByArtist(artist.id)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
            else flashStatus("No tracks for ${artist.name}")
        }
    }

    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByAlbum(albumId)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
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
            playerController.playTracks(tracks)
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
                playerController.playTracks(tracks)
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
                playerController.playTracks(tracks)
                flashStatus("Playlist cleared · ${tracks.size} tracks")
            } else {
                enqueueAndMaybePlay(tracks)
            }
        }
    }

    fun addTrackToPlaylist(track: TrackEntity, replace: Boolean = false) {
        viewModelScope.launch {
            if (replace) {
                playerController.playTracks(listOf(track))
                flashStatus("Playlist cleared · 1 track")
            } else {
                enqueueAndMaybePlay(listOf(track))
            }
        }
    }

    fun addArtistToPlaylist(artist: ArtistEntity, replace: Boolean = false) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByArtist(artist.id)
            if (replace) {
                if (tracks.isEmpty()) {
                    flashStatus("No tracks for ${artist.name}")
                    return@launch
                }
                playerController.playTracks(tracks)
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
                playerController.playTracks(listOf(track))
                flashStatus("Playlist cleared · 1 track")
            } else {
                enqueueAndMaybePlay(listOf(track))
            }
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
        val wasEmpty = queueManager.queue.value.isEmpty()
        val insertAt = queueManager.queue.value.size
        val idle = playerController.uiState.value.track == null
        queueManager.addAllToEnd(tracks)
        queueManager.persist()
        if (wasEmpty || idle) {
            playerController.playQueueIndex(if (wasEmpty) 0 else insertAt)
        }
        flashStatus("Added ${tracks.size} to playlist")
    }

    fun playRandomAlbum() {
        viewModelScope.launch {
            val album = albums.value.randomOrNull() ?: return@launch
            val tracks = libraryRepository.getTracksByAlbum(album.albumId)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
        }
    }

    fun playRandomArtist() {
        viewModelScope.launch {
            val artist = artists.value.randomOrNull() ?: return@launch
            val tracks = libraryRepository.getTracksByArtist(artist.id)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
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
            if (tracks.isNotEmpty()) playerController.playTracks(tracks.shuffled())
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            playerController.stop()
            queueManager.clear()
            queueManager.persist()
        }
    }

    fun shufflePlaylist() {
        viewModelScope.launch {
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
            playerController.playTracks(picks)
            flashStatus("Random · ${picks.size} tracks")
        }
    }

    fun movePlaylistItem(from: Int, to: Int) {
        viewModelScope.launch {
            queueManager.move(from, to)
            queueManager.persist()
        }
    }

    fun removeFromPlaylist(index: Int) {
        viewModelScope.launch {
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
        playerController.stop()
    }

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            _searchResults.value = libraryRepository.search(query)
        }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun seekTo(ms: Long) = playerController.seekTo(ms)

    fun seekBy(deltaMs: Long) {
        val state = playerState.value
        val duration = state.durationMs.takeIf { it > 0 }
            ?: state.track?.durationMs
            ?: Long.MAX_VALUE
        val next = (state.positionMs + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        playerController.seekTo(next)
    }

    fun skipNext() {
        viewModelScope.launch { playerController.playNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playerController.playPrevious() }
    }

    suspend fun listFolder(path: String) = libraryRepository.listNasFolder(path)

    fun playQueueIndex(index: Int) {
        playerController.playQueueIndex(index)
    }

    suspend fun getArtist(id: String) = libraryRepository.getArtist(id)

    suspend fun getAlbum(id: String) = libraryRepository.getAlbum(id)

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
