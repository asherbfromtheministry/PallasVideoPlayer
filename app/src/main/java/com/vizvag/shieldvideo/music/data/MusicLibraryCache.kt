package com.vizvag.shieldvideo.music.data

import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.LibraryIndexStateEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * App-scoped in-memory mirror of the Room music library.
 *
 * Loaded once when the process starts and kept warm so Radio → Music song/artist
 * lookups are instant. Avoids re-querying ~20k tracks every time MusicScreen
 * (and its ViewModel) is created.
 */
class MusicLibraryCache(
    libraryRepository: LibraryRepository,
    scope: CoroutineScope,
) {
    val artists: StateFlow<List<ArtistEntity>> =
        libraryRepository.observeArtists()
            .keepLastNonEmpty()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<AlbumWithArtist>> =
        libraryRepository.observeAlbums()
            .keepLastNonEmpty()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val tracks: StateFlow<List<TrackEntity>> =
        libraryRepository.observeTracks()
            .keepLastNonEmpty()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentTracks: StateFlow<List<TrackEntity>> =
        libraryRepository.observeRecent()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val indexState: StateFlow<LibraryIndexStateEntity?> =
        libraryRepository.observeIndexState()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** True once Room has delivered tracks, or a confirmed empty index. */
    val tracksReady: StateFlow<Boolean> =
        combine(tracks, indexState) { trackList, state ->
            when {
                trackList.isNotEmpty() -> true
                state == null -> false
                state.isIndexing -> false
                state.lastIndexedAt > 0L && state.trackCount == 0 -> true
                else -> false
            }
        }.stateIn(scope, SharingStarted.Eagerly, false)
}

/** During a library rewrite Room may briefly emit empty between deleteAll and insertAll. */
private fun <T> Flow<List<T>>.keepLastNonEmpty(): Flow<List<T>> =
    runningFold(emptyList()) { prev, next ->
        if (next.isEmpty() && prev.isNotEmpty()) prev else next
    }
