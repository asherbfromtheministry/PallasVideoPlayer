package com.vizvag.shieldvideo.ui.iptv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vizvag.shieldvideo.R
import com.vizvag.shieldvideo.data.iptv.IptvChannel
import com.vizvag.shieldvideo.ui.theme.CyanAccent
import com.vizvag.shieldvideo.ui.theme.TextMuted
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val IPTV_USER_AGENT =
    "VLC/3.0.20 LibVLC/3.0.20 Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

/**
 * Owns a single ExoPlayer for Live TV preview / fullscreen so channel zaps reuse one instance.
 * Pauses/stops on background so audio does not leak after Home / app close.
 */
@Composable
fun rememberIptvExoPlayer(): ExoPlayer {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            volume = 1f
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    player.playWhenReady = false
                    player.pause()
                    player.stop()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (player.mediaItemCount > 0) {
                        player.prepare()
                        player.playWhenReady = true
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }
    return player
}

data class AudioTrackDetail(
    val label: String?,
    val language: String?,
    val codec: String?,
    val channelCount: Int,
    val sampleRate: Int,
    val bitrate: Int,
    val selected: Boolean,
    val supported: Boolean
)

data class AudioTrackStatus(
    val typeLabel: String? = null,
    val trackCount: Int = 0,
    val tracks: List<AudioTrackDetail> = emptyList(),
    val notice: String? = null
)

/** Reports the selected audio layout and explains missing/unsupported audio. */
@Composable
fun rememberAudioTrackStatus(player: ExoPlayer, channelId: String?): AudioTrackStatus {
    var status by remember { mutableStateOf(AudioTrackStatus()) }
    DisposableEffect(player, channelId) {
        status = AudioTrackStatus()
        fun evaluate(tracks: Tracks) {
            if (tracks.groups.isEmpty()) return
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val notice = when {
                audioGroups.isEmpty() -> "No audio track in stream"
                audioGroups.none { it.isSupported } -> {
                    val mimes = audioGroups.flatMap { group ->
                        (0 until group.length).map { i ->
                            group.getTrackFormat(i).sampleMimeType
                                ?.substringAfter("audio/")?.uppercase() ?: "?"
                        }
                    }.distinct()
                    "Audio codec not supported (${mimes.joinToString()})"
                }
                else -> null
            }
            val selectedFormat = audioGroups.firstNotNullOfOrNull { group ->
                (0 until group.length)
                    .firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it) }
            }
            val fallbackFormat = audioGroups.firstNotNullOfOrNull { group ->
                (0 until group.length)
                    .firstOrNull { group.isTrackSupported(it) }
                    ?.let { group.getTrackFormat(it) }
            }
            val format = selectedFormat ?: fallbackFormat
            val trackDetails = audioGroups.flatMap { group ->
                (0 until group.length).map { index ->
                    val item = group.getTrackFormat(index)
                    AudioTrackDetail(
                        label = item.label,
                        language = item.language,
                        codec = item.codecs ?: item.sampleMimeType?.substringAfter("audio/"),
                        channelCount = item.channelCount,
                        sampleRate = item.sampleRate,
                        bitrate = item.averageBitrate.takeIf { it != Format.NO_VALUE }
                            ?: item.peakBitrate.takeIf { it != Format.NO_VALUE }
                            ?: Format.NO_VALUE,
                        selected = group.isTrackSelected(index),
                        supported = group.isTrackSupported(index)
                    )
                }
            }
            val typeLabel = when {
                format == null -> null
                format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
                format.channelCount == 1 -> "Mono"
                format.channelCount == 2 -> "Stereo"
                format.channelCount == 3 -> "3.0"
                format.channelCount == 4 -> "Quad"
                format.channelCount == 5 -> "5.0"
                format.channelCount == 6 -> "5.1"
                format.channelCount == 7 -> "6.1"
                format.channelCount == 8 -> "7.1"
                format.channelCount > 0 -> "${format.channelCount}ch"
                else -> null
            }
            status = AudioTrackStatus(
                typeLabel = typeLabel,
                trackCount = audioGroups.sumOf { it.length },
                tracks = trackDetails,
                notice = notice
            )
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = evaluate(tracks)
        }
        player.addListener(listener)
        evaluate(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }
    return status
}

/**
 * Total bytes downloaded for the main Live TV stream. Live playback consumes at the
 * stream's real rate, so bytes-per-second over a window ≈ the total mux bitrate —
 * IPTV containers almost never declare one.
 */
private val iptvStreamBytes = AtomicLong(0)

private class CountingDataSource(private val upstream: DataSource) : DataSource by upstream {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read > 0) iptvStreamBytes.addAndGet(read.toLong())
        return read
    }
}

private class CountingDataSourceFactory(
    private val upstream: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CountingDataSource(upstream.createDataSource())
}

@Composable
fun BindIptvStream(player: ExoPlayer, channel: IptvChannel?, onError: (String?) -> Unit = {}) {
    val httpFactory = remember {
        CountingDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(IPTV_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setKeepPostFor302Redirects(true)
        )
    }
    val attempt = remember(channel?.id, channel?.streamUrl) { AtomicInteger(0) }

    DisposableEffect(player, channel?.id, channel?.streamUrl) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val url = channel?.streamUrl?.trim().orEmpty()
                val next = attempt.incrementAndGet()
                if (url.isNotBlank() && next <= 3) {
                    player.setMediaSource(mediaSourceFor(httpFactory, url, next), true)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    onError(error.message ?: error.errorCodeName)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    onError(null)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(channel?.id, channel?.streamUrl) {
        attempt.set(0)
        if (channel == null || channel.streamUrl.isBlank()) {
            onError(null)
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        onError(null)
        val url = channel.streamUrl.trim()
        player.setMediaSource(mediaSourceFor(httpFactory, url, 0), true)
        player.prepare()
        player.playWhenReady = true
    }
}

private fun mediaSourceFor(
    httpFactory: DataSource.Factory,
    url: String,
    attempt: Int
): MediaSource {
    val lower = url.lowercase()
    val looksHls = lower.contains(".m3u8") || lower.contains("/hls/")
    return when (attempt) {
        0 -> if (looksHls) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(url))
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(
                MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
            )
        }
        1 -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
        )
        2 -> HlsMediaSource.Factory(httpFactory).createMediaSource(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        )
        else -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(url))
    }
}

@Composable
fun IptvPlayerSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    /** SurfaceView renders direct-to-display (HDR passthrough) but ignores Compose clipping — fullscreen only. */
    useSurfaceView: Boolean = false
) {
    val layout = if (useSurfaceView) R.layout.iptv_player_view_surface else R.layout.iptv_player_view
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).apply {
                this.useController = useController
                this.player = player
                this.resizeMode = resizeMode
                // Prevent Android TV / Google TV ambient mode while Live TV is visible.
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setKeepContentOnPlayerReset(true)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = {
            it.player = player
            it.useController = useController
            it.resizeMode = resizeMode
        },
        onRelease = {
            it.keepScreenOn = false
            it.player = null
        },
        modifier = modifier
    )
}

/** Ground truth pulled from the decoder's output MediaFormat while rendering. */
private data class DecodedVideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    /** [android.media.MediaFormat] COLOR_TRANSFER_* value, 0 when unknown. */
    val colorTransfer: Int = 0
)

data class LiveVideoDetails(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val isHdr: Boolean = false,
    val codec: String? = null,
    val bitrate: Int = Format.NO_VALUE,
    /** Total stream (video+audio mux) bits/s measured from downloaded bytes; 0 until ready. */
    val measuredBitrate: Int = 0,
    val confirmed: Boolean = false
) {
    val badges: List<String>
        get() {
            if (!confirmed) return emptyList()
            val quality = when {
                height >= 2000 -> "4K"
                height >= 1040 -> "1080p"
                height >= 700 -> "720p"
                height > 0 -> "${height}p"
                else -> null
            }
            return buildList {
                quality?.let(::add)
                if (fps > 0) add("${fps}fps")
                add(if (isHdr) "HDR" else "SDR")
            }
        }
}

/**
 * Detailed live video information and confirmed badges for the playing stream.
 * IPTV containers frequently lie or omit data (declared 1080p/25 with no colour info
 * for a 4K50 HDR feed), so prefer what is actually rendered: resolution and colour
 * transfer from the decoder's output MediaFormat, frame rate measured from frame
 * presentation timestamps. Call once per player and share the result — only one
 * frame-metadata listener can be attached to a player.
 */
@Composable
fun rememberLiveVideoDetails(player: ExoPlayer, channelId: String?): LiveVideoDetails {
    var format by remember { mutableStateOf<Format?>(null) }
    var measuredFps by remember { mutableIntStateOf(0) }
    var measuredBps by remember { mutableIntStateOf(0) }
    var decoded by remember { mutableStateOf(DecodedVideoInfo()) }

    DisposableEffect(player, channelId) {
        format = player.videoFormat
        measuredFps = 0
        decoded = DecodedVideoInfo()

        val playerListener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                format = player.videoFormat ?: format
            }
        }
        player.addListener(playerListener)

        // onTracksChanged often fires before the video renderer has a format (leaving
        // codec/bitrate unknown); the renderer's input-format callback is authoritative.
        val analyticsListener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                videoFormat: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                format = videoFormat
            }
        }
        player.addAnalyticsListener(analyticsListener)

        // Runs on the playback thread, once per rendered frame.
        var lastPtsUs = -1L
        var accumUs = 0L
        var frames = 0
        val frameListener = VideoFrameMetadataListener { presentationTimeUs, _, _, mediaFormat ->
            if (mediaFormat != null) {
                val width = when {
                    mediaFormat.containsKey("crop-right") && mediaFormat.containsKey("crop-left") ->
                        mediaFormat.getInteger("crop-right") - mediaFormat.getInteger("crop-left") + 1
                    mediaFormat.containsKey(android.media.MediaFormat.KEY_WIDTH) ->
                        mediaFormat.getInteger(android.media.MediaFormat.KEY_WIDTH)
                    else -> 0
                }
                val height = when {
                    mediaFormat.containsKey("crop-bottom") && mediaFormat.containsKey("crop-top") ->
                        mediaFormat.getInteger("crop-bottom") - mediaFormat.getInteger("crop-top") + 1
                    mediaFormat.containsKey(android.media.MediaFormat.KEY_HEIGHT) ->
                        mediaFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                    else -> 0
                }
                val transfer = if (mediaFormat.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER)) {
                    mediaFormat.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER)
                } else {
                    0
                }
                val info = DecodedVideoInfo(width, height, transfer)
                if (info != decoded) decoded = info
            }

            // Average presentation-time deltas over ~1s of frames for the real frame rate.
            val deltaUs = presentationTimeUs - lastPtsUs
            lastPtsUs = presentationTimeUs
            if (deltaUs <= 0 || deltaUs > 200_000) {
                accumUs = 0
                frames = 0
                return@VideoFrameMetadataListener
            }
            accumUs += deltaUs
            frames++
            if (frames >= 30) {
                val fps = (1_000_000f * frames / accumUs).roundToInt()
                accumUs = 0
                frames = 0
                if (fps in 10..120 && fps != measuredFps) measuredFps = fps
            }
        }
        player.setVideoFrameMetadataListener(frameListener)

        onDispose {
            player.removeListener(playerListener)
            player.removeAnalyticsListener(analyticsListener)
            player.clearVideoFrameMetadataListener(frameListener)
        }
    }

    LaunchedEffect(player, channelId) {
        measuredBps = 0
        delay(3_000) // skip the initial buffering burst
        var lastBytes = iptvStreamBytes.get()
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(5_000)
            val bytes = iptvStreamBytes.get()
            val now = SystemClock.elapsedRealtime()
            val bps = ((bytes - lastBytes) * 8_000 / (now - lastAt).coerceAtLeast(1)).toInt()
            lastBytes = bytes
            lastAt = now
            if (bps > 0) measuredBps = bps
        }
    }

    val selected = format
    val confirmed = decoded.height > 0 && measuredFps > 0
    return LiveVideoDetails(
        width = decoded.width.takeIf { it > 0 } ?: selected?.width?.takeIf { it > 0 } ?: 0,
        height = decoded.height.takeIf { it > 0 } ?: selected?.height?.takeIf { it > 0 } ?: 0,
        fps = measuredFps.takeIf { it > 0 }
            ?: selected?.frameRate?.takeIf { it > 0f }?.roundToInt()
            ?: 0,
        isHdr =
        decoded.colorTransfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 ||
            decoded.colorTransfer == android.media.MediaFormat.COLOR_TRANSFER_HLG,
        codec = selected?.sampleMimeType?.substringAfter("video/") ?: selected?.codecs,
        bitrate = selected?.averageBitrate?.takeIf { it != Format.NO_VALUE }
            ?: selected?.peakBitrate?.takeIf { it != Format.NO_VALUE }
            ?: Format.NO_VALUE,
        measuredBitrate = measuredBps,
        confirmed = confirmed
    )
}

@Composable
fun ChannelPreviewFrame(
    player: ExoPlayer,
    channel: IptvChannel?,
    nowTitle: String?,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    /** When false, surface is detached so fullscreen can own the player. */
    attachPlayer: Boolean = true,
    previewError: String? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .border(2.dp, CyanAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .focusProperties { canFocus = false }
            .clickable(enabled = channel != null, onClick = onOpenFullscreen)
    ) {
        if (channel != null && attachPlayer) {
            IptvPlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (channel != null) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            val footer = buildString {
                if (!previewError.isNullOrBlank()) {
                    append("Preview error: $previewError")
                } else {
                    append("OK again / click preview → fullscreen")
                    if (!nowTitle.isNullOrBlank()) append("\nNow: $nowTitle")
                }
            }
            Text(
                text = footer,
                color = if (previewError != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a channel to preview",
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
