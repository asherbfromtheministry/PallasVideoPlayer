package com.vizvag.shieldvideo.music.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.music.data.LibraryRepository
import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.lyrics.LyricLine
import com.vizvag.shieldvideo.music.data.lyrics.LrcParser
import com.vizvag.shieldvideo.music.data.lyrics.LyricsRepository
import com.vizvag.shieldvideo.music.player.PlayerController
import com.vizvag.shieldvideo.music.player.QueueManager
import com.vizvag.shieldvideo.music.player.StreamUrlBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playerController: PlayerController,
) : ViewModel() {
    private val artistId: String = savedStateHandle["artistId"] ?: ""

    val artist = kotlinx.coroutines.flow.flow {
        emit(libraryRepository.getArtist(artistId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val albums = libraryRepository.observeAlbumsByArtist(artistId).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )

    fun playAlbum(album: AlbumWithArtist) {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByAlbum(album.albumId)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
        }
    }

    fun playArtist() {
        viewModelScope.launch {
            val tracks = libraryRepository.getTracksByArtist(artistId)
            if (tracks.isNotEmpty()) playerController.playTracks(tracks)
        }
    }
}

class AlbumDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playerController: PlayerController,
    private val streamUrlBuilder: StreamUrlBuilder,
) : ViewModel() {
    private val albumId: String = savedStateHandle["albumId"] ?: ""

    val album = kotlinx.coroutines.flow.flow {
        emit(libraryRepository.getAlbum(albumId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracks = libraryRepository.observeTracksByAlbum(albumId).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )

    private val _coverUrl = MutableStateFlow<String?>(null)
    val coverUrl = _coverUrl.asStateFlow()

    init {
        viewModelScope.launch {
            val cover = album.value?.coverPath
            _coverUrl.value = streamUrlBuilder.buildArtUrl(cover)
        }
    }

    fun playAll(shuffle: Boolean = false) {
        viewModelScope.launch {
            var list = libraryRepository.getTracksByAlbum(albumId)
            if (shuffle) list = list.shuffled()
            if (list.isNotEmpty()) playerController.playTracks(list)
        }
    }

    fun playTrack(track: TrackEntity, index: Int) {
        viewModelScope.launch {
            val list = libraryRepository.getTracksByAlbum(albumId)
            val start = list.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: index
            if (list.isNotEmpty()) playerController.playTracks(list, start)
        }
    }
}

class NowPlayingViewModel constructor(
    private val playerController: PlayerController,
    private val queueManager: QueueManager,
    private val lyricsRepository: LyricsRepository,
    private val streamUrlBuilder: StreamUrlBuilder,
) : ViewModel() {

    val playerState = playerController.uiState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        com.vizvag.shieldvideo.music.player.PlayerUiState(),
    )
    val queue = queueManager.queue.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )
    val currentIndex = queueManager.currentIndex.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), -1,
    )

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics = _lyrics.asStateFlow()

    private val _coverUrl = MutableStateFlow<String?>(null)
    val coverUrl = _coverUrl.asStateFlow()

    init {
        viewModelScope.launch {
            playerState.collect { state ->
                state.track?.let { track ->
                    _lyrics.value = lyricsRepository.getLyrics(track.lyricsPath)
                    val album = track.albumTitle
                    _coverUrl.value = streamUrlBuilder.buildArtUrl(
                        track.nasPath.substringBeforeLast('/').let { "$it/folder.jpg" },
                    )
                }
            }
        }
    }

    fun currentLyricIndex(): Int =
        LrcParser.currentLineIndex(_lyrics.value, playerState.value.positionMs)

    fun togglePlayPause() = playerController.togglePlayPause()
    fun seekTo(ms: Long) = playerController.seekTo(ms)
    fun skipNext() { viewModelScope.launch { playerController.playNext() } }
    fun skipPrevious() { viewModelScope.launch { playerController.playPrevious() } }
    fun removeFromQueue(index: Int) {
        queueManager.removeAt(index)
        viewModelScope.launch { queueManager.persist() }
    }
    fun moveQueueItem(from: Int, to: Int) {
        queueManager.move(from, to)
        viewModelScope.launch { queueManager.persist() }
    }
}
