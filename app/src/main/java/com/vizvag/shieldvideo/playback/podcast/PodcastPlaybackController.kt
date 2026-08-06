package com.vizvag.shieldvideo.playback.podcast

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vizvag.shieldvideo.data.podcast.PodcastEpisode
import com.vizvag.shieldvideo.data.podcast.PodcastShow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PodcastPlaybackState(
    val showId: String = "",
    val showTitle: String = "",
    val episodeGuid: String = "",
    val episodeTitle: String = "",
    val audioUrl: String = "",
    val imageUrl: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * App-scoped Podcasts ExoPlayer — quit must silence (MainActivity.onStop).
 */
class PodcastPlaybackController(context: Context) {
    private val appContext = context.applicationContext

    val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        playWhenReady = true
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncPosition()
            }
        })
    }

    private val _state = MutableStateFlow(PodcastPlaybackState())
    val state: StateFlow<PodcastPlaybackState> = _state.asStateFlow()

    fun playEpisode(
        show: PodcastShow,
        episode: PodcastEpisode,
        startPositionMs: Long = 0L,
    ) {
        if (episode.audioUrl.isBlank()) return
        _state.value = PodcastPlaybackState(
            showId = show.id,
            showTitle = show.title,
            episodeGuid = episode.guid,
            episodeTitle = episode.title,
            audioUrl = episode.audioUrl,
            imageUrl = episode.imageUrl.ifBlank { show.imageUrl },
            isPlaying = true,
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = (episode.durationSec * 1000L).coerceAtLeast(0L),
        )
        player.setMediaItem(MediaItem.fromUri(episode.audioUrl))
        player.prepare()
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.play()
        publishHaNowPlaying()
    }

    fun play() {
        if (player.mediaItemCount > 0) {
            player.play()
        }
        publishHaNowPlaying()
    }

    fun pause() {
        player.pause()
        publishHaNowPlaying()
    }

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo(target.coerceAtMost(dur))
        syncPosition()
        publishHaNowPlaying()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        syncPosition()
        publishHaNowPlaying()
    }

    fun syncPosition() {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0L } ?: _state.value.durationMs
        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                positionMs = pos,
                durationMs = dur.coerceAtLeast(0L),
            )
        }
    }

    fun stop() {
        runCatching {
            player.volume = 0f
            player.pause()
            player.stop()
            player.clearMediaItems()
            player.volume = 1f
        }
        _state.value = PodcastPlaybackState()
        clearHaNowPlaying()
    }

    fun isActive(): Boolean =
        _state.value.episodeGuid.isNotBlank() || player.mediaItemCount > 0

    private fun publishHaNowPlaying() {
        runCatching {
            (appContext as? com.vizvag.shieldvideo.ShieldVideoApp)
                ?: com.vizvag.shieldvideo.ShieldVideoApp.instance
        }.getOrNull()?.publishPodcastNowPlayingToHa()
    }

    private fun clearHaNowPlaying() {
        runCatching {
            (appContext as? com.vizvag.shieldvideo.ShieldVideoApp)
                ?: com.vizvag.shieldvideo.ShieldVideoApp.instance
        }.getOrNull()?.clearPodcastNowPlayingToHa()
    }
}
