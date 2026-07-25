package com.vizvag.shieldvideo.music.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vizvag.shieldvideo.music.data.LibraryRepository
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val track: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
)

class PlayerController constructor(
    context: Context,
    private val queueManager: QueueManager,
    private val streamUrlBuilder: StreamUrlBuilder,
    private val libraryRepository: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Bumped on every intentional start and on [stop]. Async play work checks this so a
     * late URL fetch cannot restart audio after the user left Music.
     */
    private var playEpoch: Int = 0

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                )
                if (playbackState == Player.STATE_ENDED) {
                    // Only advance if we still have an active now-playing track (stop clears it).
                    if (_uiState.value.track != null) {
                        scope.launch { playNext() }
                    }
                }
            }
        })
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            queueManager.loadPersisted()
            // Restore current track metadata only — do not auto-start playback
            queueManager.currentTrack?.let { track ->
                _uiState.value = PlayerUiState(
                    track = track,
                    isPlaying = false,
                    durationMs = track.durationMs,
                )
            }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (_uiState.value.track == null) continue
                _uiState.value = _uiState.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.coerceAtLeast(0).takeIf { it > 0 }
                        ?: _uiState.value.durationMs,
                )
            }
        }
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        queueManager.setQueue(tracks, startIndex)
        val epoch = ++playEpoch
        scope.launch {
            queueManager.persist()
            playTrackInternal(tracks[startIndex], epoch = epoch)
        }
    }

    fun playTrack(track: TrackEntity) = playTracks(listOf(track))

    /** Play an index already in the current queue (does not replace the queue). */
    fun playQueueIndex(index: Int) {
        val list = queueManager.queue.value
        if (index !in list.indices) return
        queueManager.advanceTo(index)
        val epoch = ++playEpoch
        scope.launch {
            queueManager.persist()
            playTrackInternal(list[index], epoch = epoch)
        }
    }

    private suspend fun playTrackInternal(
        track: TrackEntity,
        persist: Boolean = true,
        epoch: Int = playEpoch,
    ) {
        if (epoch != playEpoch) return
        val url = streamUrlBuilder.buildStreamUrl(track.nasPath)
        if (epoch != playEpoch) return
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        if (epoch != playEpoch) {
            player.pause()
            player.stop()
            player.clearMediaItems()
            return
        }
        player.volume = 1f
        player.play()
        libraryRepository.recordPlay(track.id)
        if (epoch != playEpoch) {
            hardStopPlayer()
            return
        }
        _uiState.value = PlayerUiState(
            track = track,
            isPlaying = true,
            durationMs = track.durationMs,
        )
        if (persist) queueManager.persist()
    }

    suspend fun playNext() {
        if (_uiState.value.track == null) return
        val epoch = playEpoch
        val next = queueManager.nextIndex() ?: run {
            player.pause()
            return
        }
        if (epoch != playEpoch) return
        queueManager.advanceTo(next)
        queueManager.currentTrack?.let { playTrackInternal(it, epoch = epoch) }
    }

    suspend fun playPrevious() {
        if (_uiState.value.track == null) return
        val epoch = playEpoch
        if (player.currentPosition > 3000) {
            player.seekTo(0)
            return
        }
        val prev = queueManager.previousIndex() ?: return
        if (epoch != playEpoch) return
        queueManager.advanceTo(prev)
        queueManager.currentTrack?.let { playTrackInternal(it, epoch = epoch) }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.mediaItemCount > 0) {
            player.play()
            return
        }
        // Stopped (e.g. left Music) but queue still has tracks — resume from current/first
        val index = queueManager.currentIndex.value.takeIf { it >= 0 }
            ?: 0.takeIf { queueManager.queue.value.isNotEmpty() }
            ?: return
        playQueueIndex(index)
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** Push ID3 / Audio Station tags onto the current now-playing track + queue row. */
    fun applyTrackMetadata(updated: TrackEntity) {
        val current = _uiState.value.track ?: return
        val sameId = current.id == updated.id
        val samePath = current.nasPath.replace('\\', '/') == updated.nasPath.replace('\\', '/')
        if (!sameId && !samePath) return
        _uiState.value = _uiState.value.copy(
            track = updated,
            durationMs = updated.durationMs.takeIf { it > 0 } ?: _uiState.value.durationMs,
        )
        queueManager.updateTrackMetadata(updated)
    }

    /** Hard stop — used when leaving Music so audio cannot keep playing. */
    fun stop() {
        playEpoch++
        hardStopPlayer()
        _uiState.value = PlayerUiState()
    }

    private fun hardStopPlayer() {
        runCatching {
            player.volume = 0f
            player.pause()
            player.stop()
            player.clearMediaItems()
            player.volume = 1f
        }
    }

    fun pause() {
        player.pause()
    }
}
