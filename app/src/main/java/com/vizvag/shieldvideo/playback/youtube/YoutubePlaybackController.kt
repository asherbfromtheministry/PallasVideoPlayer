package com.vizvag.shieldvideo.playback.youtube

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.vizvag.shieldvideo.data.youtube.YoutubePlayback
import com.vizvag.shieldvideo.data.youtube.YoutubeStreamInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class YoutubePlaybackState(
    val videoId: String = "",
    val title: String = "",
    val uploader: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * App-scoped YouTube ExoPlayer for remote control.
 */
@OptIn(UnstableApi::class)
class YoutubePlaybackController(context: Context) {
    private val appContext = context.applicationContext

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(
            mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )

    val player: ExoPlayer = run {
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(appContext, renderersFactory).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }
            })
        }
    }

    private val _state = MutableStateFlow(YoutubePlaybackState())
    val state: StateFlow<YoutubePlaybackState> = _state.asStateFlow()

    private var currentInfo: YoutubeStreamInfo? = null

    fun playStream(info: YoutubeStreamInfo) {
        currentInfo = info
        _state.value = YoutubePlaybackState(
            videoId = info.id,
            title = info.title,
            uploader = info.uploader,
            isPlaying = true,
            durationMs = info.durationSec * 1000L,
        )
        applyPlayback(info.playback)
    }

    fun playFallback(index: Int): Boolean {
        val info = currentInfo ?: return false
        val all = listOf(info.playback) + info.playbackFallbacks
        val playback = all.getOrNull(index) ?: return false
        applyPlayback(playback)
        return true
    }

    private fun applyPlayback(playback: YoutubePlayback) {
        val source = when (playback) {
            is YoutubePlayback.Progressive -> {
                val item = MediaItem.Builder()
                    .setUri(playback.url)
                    .apply { playback.mimeType?.let { setMimeType(it) } }
                    .build()
                ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
            }
            is YoutubePlayback.Dash -> {
                DashMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(playback.url))
            }
            is YoutubePlayback.Hls -> {
                HlsMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(playback.url))
            }
            is YoutubePlayback.SeparateTracks -> {
                val videoBuilder = MediaItem.Builder().setUri(playback.videoUrl)
                playback.videoMime?.let { videoBuilder.setMimeType(it) }
                val audioBuilder = MediaItem.Builder().setUri(playback.audioUrl)
                playback.audioMime?.let { audioBuilder.setMimeType(it) }
                MergingMediaSource(
                    ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(videoBuilder.build()),
                    ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(audioBuilder.build()),
                )
            }
        }
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    fun play() {
        if (player.mediaItemCount > 0) player.play()
        else currentInfo?.let { playStream(it) }
    }

    fun pause() = player.pause()

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    fun stop() {
        runCatching {
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
        currentInfo = null
        _state.value = YoutubePlaybackState()
    }

    fun refreshPosition() {
        if (_state.value.videoId.isBlank()) return
        _state.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0).takeIf { d -> d > 0 }
                    ?: it.durationMs,
                isPlaying = player.isPlaying,
            )
        }
    }

    fun isActive(): Boolean = _state.value.videoId.isNotBlank() || player.mediaItemCount > 0
}
