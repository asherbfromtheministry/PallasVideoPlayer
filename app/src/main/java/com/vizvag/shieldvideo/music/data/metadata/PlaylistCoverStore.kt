package com.vizvag.shieldvideo.music.data.metadata

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.player.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Per-track playlist art, preloaded whenever the queue changes.
 *
 * Resolution order matches the player track tile:
 * 1. Deezer / iTunes track art (artist + title)
 * 2. Local NAS folder cover
 * 3. Remote album art
 *
 * Coil is warmed at playlist thumb size so rows paint instantly when the drawer opens.
 */
class PlaylistCoverStore(
    private val context: Context,
    private val queueManager: QueueManager,
    private val coverArtCache: CoverArtCache,
    private val albumArtLookup: AlbumArtLookup,
    private val scope: CoroutineScope,
) {
    private val _covers = MutableStateFlow<Map<String, Any>>(emptyMap())
    val covers: StateFlow<Map<String, Any>> = _covers.asStateFlow()

    private var preloadJob: Job? = null
    private val warmedModels = mutableSetOf<String>()
    private val failedKeys = mutableSetOf<String>()

    init {
        scope.launch {
            queueManager.queue.collect { tracks -> preloadQueue(tracks) }
        }
    }

    fun coverKey(track: TrackEntity): String =
        "tr:${track.id}|${track.nasPath.replace('\\', '/').lowercase()}"

    /** Inject already-resolved art (e.g. now-playing track tile) into the playlist cache. */
    fun putCover(track: TrackEntity, art: Any?) {
        if (art == null) return
        val key = coverKey(track)
        if (_covers.value[key] == art) return
        _covers.value = _covers.value + (key to art)
        synchronized(failedKeys) { failedKeys.remove(key) }
        scope.launch { warmCoil(art) }
    }

    private fun preloadQueue(tracks: List<TrackEntity>) {
        preloadJob?.cancel()
        val keysInQueue = tracks.map(::coverKey).toSet()
        // Drop covers for tracks no longer in the queue (clear / replace album).
        val pruned = _covers.value.filterKeys { it in keysInQueue }
        if (pruned.size != _covers.value.size) {
            _covers.value = pruned
        }
        synchronized(failedKeys) {
            failedKeys.retainAll(keysInQueue)
        }
        if (tracks.isEmpty()) return

        preloadJob = scope.launch {
            val gate = Semaphore(10)
            val currentIdx = queueManager.currentIndex.value
            val ordered = tracks.withIndex().sortedBy { (i, _) ->
                when {
                    i == currentIdx -> 0
                    i > currentIdx -> 1
                    else -> 2
                }
            }
            ordered.map { (_, track) ->
                async {
                    gate.withPermit { loadTrackArt(track) }
                }
            }.awaitAll()
        }
    }

    private suspend fun loadTrackArt(track: TrackEntity) {
        val key = coverKey(track)
        if (_covers.value[key] != null) return
        synchronized(failedKeys) {
            if (key in failedKeys) return
        }
        val art = runCatching { resolveTrackArt(track) }.getOrNull()
        if (art == null) {
            synchronized(failedKeys) { failedKeys.add(key) }
            return
        }
        if (_covers.value[key] == null) {
            _covers.value = _covers.value + (key to art)
        }
        warmCoil(art)
    }

    private suspend fun resolveTrackArt(track: TrackEntity): Any? {
        val artist = track.artistName.trim()
            .ifBlank { track.albumArtist.orEmpty().trim() }
        val title = track.title.trim()

        // 1) Track art — same source as the player's sharp left tile.
        if (artist.isNotBlank() && title.isNotBlank()) {
            albumArtLookup.resolveTrackArtUrl(artist, title)?.let { return it }
        }

        // 2) Local NAS folder cover.
        runCatching { coverArtCache.coverFileForTrack(track) }.getOrNull()?.let { return it }

        // 3) Remote album art fallback.
        val album = track.albumTitle.trim()
        if (album.isNotBlank()) {
            albumArtLookup.resolveCoverUrl(
                localUrl = null,
                artist = artist,
                album = album,
                trackTitle = title,
                albumArtist = track.albumArtist,
                nasPath = track.nasPath,
            )?.let { return it }
        }
        return null
    }

    private suspend fun warmCoil(model: Any) {
        val token = when (model) {
            is String -> model
            is java.io.File -> model.absolutePath
            else -> model.toString()
        }
        synchronized(warmedModels) {
            if (token in warmedModels) return
            warmedModels.add(token)
        }
        withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(Size(PLAYLIST_THUMB_PX, PLAYLIST_THUMB_PX))
                        .build(),
                )
            }
        }
    }

    companion object {
        /** ~28dp playlist thumb at 4×. */
        private const val PLAYLIST_THUMB_PX = 112
    }
}
