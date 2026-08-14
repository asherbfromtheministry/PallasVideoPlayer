package com.vizvag.shieldvideo.playback.youtube

import android.content.Context
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.settings.YoutubePreferredResolution
import com.vizvag.shieldvideo.data.youtube.YoutubeDefaults
import com.vizvag.shieldvideo.data.youtube.YoutubePlayback
import com.vizvag.shieldvideo.data.youtube.YoutubeQualityOption
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
class YoutubePlaybackController(
    context: Context,
    private val settingsRepository: SettingsRepository? = null,
) {
    private val appContext = context.applicationContext

    private var activeUserAgent: String = YoutubeDefaults.PLAYBACK_USER_AGENT

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(activeUserAgent)
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

    private fun applyPlaybackUserAgent(ua: String) {
        val next = ua.ifBlank { YoutubeDefaults.PLAYBACK_USER_AGENT }
        if (next == activeUserAgent) return
        activeUserAgent = next
        httpFactory.setUserAgent(next)
    }

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        parameters = buildUponParameters()
            .setPreferredAudioLanguage("en")
            .setForceHighestSupportedBitrate(true)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true)
            .build()
    }

    val player: ExoPlayer = run {
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
    }
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val restore = pendingRestore ?: return
                    pendingRestore = null
                    switchingQuality = false
                    suppressBinderErrorsUntilMs = System.currentTimeMillis() + 3_000L
                    applyPlayback(restore.playback, restore.positionMs, restore.playWhenReady)
                    // Always soft — never forward raw "Source error" (that kills the session).
                    onQualitySwitchFailed?.invoke("Couldn't switch quality — kept previous")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && switchingQuality) {
                        switchingQuality = false
                        pendingRestore = null
                        currentPlayback = lastAppliedPlayback
                    }
                }
            })
        }

    private val _state = MutableStateFlow(YoutubePlaybackState())
    val state: StateFlow<YoutubePlaybackState> = _state.asStateFlow()

    private var currentInfo: YoutubeStreamInfo? = null
    private var currentPlayback: YoutubePlayback? = null
    private var lastAppliedPlayback: YoutubePlayback? = null
    private var switchingQuality = false
    private var pendingRestore: RestorePoint? = null
    private var suppressBinderErrorsUntilMs = 0L
    private var usingAdaptiveManifest = false
    var onQualitySwitchFailed: ((String) -> Unit)? = null

    fun isSwitchingQuality(): Boolean =
        switchingQuality ||
            pendingRestore != null ||
            System.currentTimeMillis() < suppressBinderErrorsUntilMs

    private data class RestorePoint(
        val playback: YoutubePlayback,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private fun preferredMaxHeight(): Int =
        settingsRepository?.load()?.youtubePreferredResolution?.maxHeight
            ?: YoutubePreferredResolution.Auto.maxHeight

    fun playStream(info: YoutubeStreamInfo) {
        applyPlaybackUserAgent(info.playbackUserAgent)
        currentInfo = info
        switchingQuality = false
        pendingRestore = null
        suppressBinderErrorsUntilMs = 0L
        val maxH = preferredMaxHeight()
        val preferred = preferredPlayback(info, maxH)
        currentPlayback = preferred
        usingAdaptiveManifest = preferred is YoutubePlayback.Dash || preferred is YoutubePlayback.Hls
        _state.value = YoutubePlaybackState(
            videoId = info.id,
            title = info.title,
            uploader = info.uploader,
            isPlaying = true,
            durationMs = info.durationSec * 1000L,
        )
        applyPreferredVideoCap(maxH, preferred)
        applyPlayback(preferred, positionMs = 0L, playWhenReady = true)
    }

    private fun preferredPlayback(info: YoutubeStreamInfo, maxHeight: Int): YoutubePlayback {
        if (maxHeight > 0 &&
            info.playback is YoutubePlayback.SeparateTracks &&
            info.qualities.isNotEmpty()
        ) {
            val capped = info.qualities
                .filter { it.height <= maxHeight }
                .maxByOrNull { it.height }
                ?: info.qualities.minByOrNull { it.height }
            if (capped != null) return capped.playback
        }
        return info.playback
    }

    private fun applyPreferredVideoCap(maxHeight: Int, playback: YoutubePlayback) {
        val adaptive = playback is YoutubePlayback.Dash || playback is YoutubePlayback.Hls
        if (maxHeight > 0 && adaptive) {
            setMaxVideoHeight(maxHeight)
        } else {
            clearVideoSizeCap()
        }
    }

    /** Re-apply Settings → YouTube preferred resolution (AUTO = uncapped). */
    fun applyPreferredResolutionFromSettings() {
        val maxH = preferredMaxHeight()
        val playback = currentPlayback ?: currentInfo?.playback
        if (playback != null) {
            applyPreferredVideoCap(maxH, playback)
        } else if (maxH > 0) {
            setMaxVideoHeight(maxH)
        } else {
            clearVideoSizeCap()
        }
    }

    fun playFallback(index: Int): Boolean {
        val info = currentInfo ?: return false
        val all = listOf(info.playback) + info.playbackFallbacks
        val playback = all.getOrNull(index) ?: return false
        usingAdaptiveManifest = playback is YoutubePlayback.Dash || playback is YoutubePlayback.Hls
        applyPreferredVideoCap(preferredMaxHeight(), playback)
        applyPlayback(playback, player.currentPosition.coerceAtLeast(0L), player.playWhenReady)
        currentPlayback = playback
        return true
    }

    /**
     * Select a listed quality height. Uses track-selector caps on DASH/HLS (SmartTube-style);
     * falls back to SeparateTracks only when no adaptive manifest is available.
     */
    fun selectQuality(option: YoutubeQualityOption): Boolean {
        val info = currentInfo ?: return false
        val currentH = player.videoFormat?.height ?: 0
        if (currentH > 0 && kotlin.math.abs(option.height - currentH) <= 16) {
            return false
        }

        val all = listOfNotNull(currentPlayback) + listOf(info.playback) + info.playbackFallbacks
        val dashOrHls = all.firstOrNull {
            (it is YoutubePlayback.Dash && it.url.startsWith("http")) || it is YoutubePlayback.Hls
        }
        if (dashOrHls != null) {
            if (currentPlayback !is YoutubePlayback.Dash && currentPlayback !is YoutubePlayback.Hls) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val wasPlaying = player.playWhenReady
                // Don't treat manifest bootstrap as a fatal quality failure.
                switchingQuality = false
                pendingRestore = null
                suppressBinderErrorsUntilMs = System.currentTimeMillis() + 3_000L
                usingAdaptiveManifest = true
                applyPlayback(dashOrHls, pos, wasPlaying)
                currentPlayback = dashOrHls
            }
            setMaxVideoHeight(option.height)
            return true
        }

        // No DASH: swap SeparateTracks video, keep working audio.
        val target = option.playback
        val workingAudio = (currentPlayback as? YoutubePlayback.SeparateTracks)?.audioUrl
            ?: (lastAppliedPlayback as? YoutubePlayback.SeparateTracks)?.audioUrl
        val playback = if (!workingAudio.isNullOrBlank()) {
            target.copy(audioUrl = workingAudio)
        } else {
            target
        }
        switchPlayback(playback, resume = true)
        return true
    }

    /** Swap to another discrete quality while keeping position; roll back on Source error. */
    fun switchPlayback(playback: YoutubePlayback, resume: Boolean = true) {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val previous = currentPlayback ?: lastAppliedPlayback
        if (previous != null && previous != playback) {
            pendingRestore = RestorePoint(previous, pos, wasPlaying)
            switchingQuality = true
            suppressBinderErrorsUntilMs = System.currentTimeMillis() + 3_000L
        }
        usingAdaptiveManifest = playback is YoutubePlayback.Dash || playback is YoutubePlayback.Hls
        if (!usingAdaptiveManifest) clearVideoSizeCap()
        applyPlayback(playback, if (resume) pos else 0L, wasPlaying)
        currentInfo = currentInfo?.copy(playback = playback)
    }

    fun setMaxVideoHeight(height: Int) {
        if (height <= 0) {
            clearVideoSizeCap()
            return
        }
        // Cap height but still pick the best bitrate at/under that rung.
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage("en")
            .setForceHighestSupportedBitrate(true)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setMaxVideoSize(Int.MAX_VALUE, height)
            .setViewportSize(Int.MAX_VALUE, height, false)
            .build()
    }

    fun clearVideoSizeCap() {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage("en")
            .setForceHighestSupportedBitrate(true)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true)
            .build()
    }

    fun forceHighestVideoQuality() = clearVideoSizeCap()

    fun forceMaxQuality(): Boolean {
        val info = currentInfo ?: return false
        val best = info.qualities.maxByOrNull { it.height } ?: return false
        return selectQuality(best)
    }

    private fun applyPlayback(
        playback: YoutubePlayback,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        // Pot is applied at stream-fetch time. Re-appending here breaks ANDROID_VR URLs
        // that intentionally ship without pot=.
        lastAppliedPlayback = playback
        val source = mediaSourceFor(playback)
        player.setMediaSource(source)
        player.prepare()
        if (positionMs > 0L) player.seekTo(positionMs)
        player.playWhenReady = playWhenReady
    }

    private fun mediaSourceFor(playback: YoutubePlayback) = when (playback) {
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
            val video = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(playback.videoUrl))
            val audio = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(playback.audioUrl))
            // Align A/V timelines — default merge is a common Source-error trigger on YT.
            MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
                video,
                audio,
            )
        }
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
        switchingQuality = false
        pendingRestore = null
        suppressBinderErrorsUntilMs = 0L
        usingAdaptiveManifest = false
        runCatching {
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
        currentInfo = null
        currentPlayback = null
        lastAppliedPlayback = null
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
