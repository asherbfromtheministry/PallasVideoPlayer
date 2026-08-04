package com.vizvag.shieldvideo.ui.youtube

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vizvag.shieldvideo.data.youtube.YoutubePlayback
import com.vizvag.shieldvideo.data.youtube.YoutubeStreamInfo

private const val YT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Composable
fun rememberYoutubeExoPlayer(): ExoPlayer {
    val app = com.vizvag.shieldvideo.ShieldVideoApp.instance
    val player = app.youtubePlayback.player
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> app.youtubePlayback.pause()
                Lifecycle.Event.ON_STOP -> app.youtubePlayback.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return player
}

@OptIn(UnstableApi::class)
@Composable
fun BindYoutubeStream(
    player: ExoPlayer,
    info: YoutubeStreamInfo?,
    onError: (String?) -> Unit = {},
) {
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(YT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                )
            )
    }

    var attempt by remember(info?.id) { mutableIntStateOf(0) }
    val sources = remember(info?.id, info?.playback, info?.playbackFallbacks) {
        if (info == null) emptyList()
        else listOf(info.playback) + info.playbackFallbacks
    }

    DisposableEffect(player, info?.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val next = attempt + 1
                if (next < sources.size) {
                    attempt = next
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaSource(mediaSourceFor(httpFactory, sources[next]), true)
                    player.prepare()
                    player.playWhenReady = true
                    onError(null)
                } else {
                    onError(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: error.errorCodeName
                            ?: "Playback failed"
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) onError(null)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, info?.id, sources) {
        attempt = 0
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        if (info == null || sources.isEmpty()) {
            onError(null)
            return@LaunchedEffect
        }
        onError(null)
        player.setMediaSource(mediaSourceFor(httpFactory, sources.first()), true)
        player.prepare()
        player.playWhenReady = true
    }
}

@OptIn(UnstableApi::class)
private fun mediaSourceFor(
    httpFactory: DefaultHttpDataSource.Factory,
    playback: YoutubePlayback,
) = when (playback) {
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
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(videoBuilder.build()),
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(audioBuilder.build()),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun YoutubePlayerSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    this.useController = useController
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = player
                view.useController = useController
                view.keepScreenOn = true
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                view.keepScreenOn = false
                view.player = null
            },
        )
    }
}
