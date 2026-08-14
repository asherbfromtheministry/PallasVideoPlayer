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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vizvag.shieldvideo.data.youtube.YoutubeStreamInfo

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
    // Freeze initial candidates per video id — quality swaps go through YoutubePlaybackController
    // and must not re-trigger this binder (that caused Source errors / session kills).
    var attempt by remember(info?.id) { mutableIntStateOf(0) }
    val sources = remember(info?.id) {
        if (info == null) emptyList()
        else listOf(info.playback) + info.playbackFallbacks
    }
    val controller = com.vizvag.shieldvideo.ShieldVideoApp.instance.youtubePlayback

    DisposableEffect(player, info?.id) {
        controller.onQualitySwitchFailed = { msg ->
            onError(msg)
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Quality rollback is owned by YoutubePlaybackController.
                if (controller.isSwitchingQuality()) return
                val next = attempt + 1
                if (next < sources.size && controller.playFallback(next)) {
                    attempt = next
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
        onDispose {
            player.removeListener(listener)
            if (controller.onQualitySwitchFailed != null) {
                controller.onQualitySwitchFailed = null
            }
        }
    }

    LaunchedEffect(info?.id) {
        attempt = 0
        if (info == null || sources.isEmpty()) {
            controller.stop()
            onError(null)
            return@LaunchedEffect
        }
        onError(null)
        controller.applyPreferredResolutionFromSettings()
        controller.playStream(info)
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
                    // Compose owns D-pad focus for pause chrome / quality picker.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
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
                view.isFocusable = false
                view.isFocusableInTouchMode = false
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                view.keepScreenOn = false
                view.player = null
            },
        )
    }
}
