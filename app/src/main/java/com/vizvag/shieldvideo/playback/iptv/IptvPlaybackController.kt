package com.vizvag.shieldvideo.playback.iptv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.util.UnstableApi
import com.vizvag.shieldvideo.ui.iptv.buildIptvExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IptvPlaybackState(
    val channelId: String = "",
    val channelName: String = "",
    val streamUrl: String = "",
    val isPlaying: Boolean = false,
)

/**
 * App-scoped Live TV player for remote control + shared screen binding.
 */
@OptIn(UnstableApi::class)
class IptvPlaybackController(context: Context) {
    private val appContext = context.applicationContext

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("VLC/3.0.20 LibVLC/3.0.20 Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    var player: ExoPlayer = buildIptvExoPlayer(appContext)
        private set

    private val _state = MutableStateFlow(IptvPlaybackState())
    val state: StateFlow<IptvPlaybackState> = _state.asStateFlow()

    init {
        attachListener(player)
    }

    private fun attachListener(p: ExoPlayer) {
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }
        })
    }

    fun playChannel(channelId: String, channelName: String, streamUrl: String) {
        if (streamUrl.isBlank()) return
        _state.value = IptvPlaybackState(
            channelId = channelId,
            channelName = channelName,
            streamUrl = streamUrl,
            isPlaying = true,
        )
        val mediaItem = MediaItem.fromUri(streamUrl)
        val source = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    fun notePlaying(channelId: String, channelName: String, streamUrl: String) {
        _state.value = IptvPlaybackState(
            channelId = channelId,
            channelName = channelName,
            streamUrl = streamUrl,
            isPlaying = player.isPlaying || player.playWhenReady,
        )
    }

    fun play() {
        if (player.mediaItemCount > 0) {
            player.playWhenReady = true
            player.play()
        } else {
            val s = _state.value
            if (s.streamUrl.isNotBlank()) {
                playChannel(s.channelId, s.channelName, s.streamUrl)
            }
        }
    }

    fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun stop() {
        runCatching {
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
        _state.value = IptvPlaybackState()
    }

    /** Recreate native decoder state after many zaps (matches Compose recycle behaviour). */
    fun recyclePlayer() {
        val previous = _state.value
        runCatching {
            player.release()
        }
        player = buildIptvExoPlayer(appContext)
        attachListener(player)
        if (previous.streamUrl.isNotBlank()) {
            playChannel(previous.channelId, previous.channelName, previous.streamUrl)
        }
    }

    fun isActive(): Boolean = _state.value.channelId.isNotBlank() || player.mediaItemCount > 0
}
