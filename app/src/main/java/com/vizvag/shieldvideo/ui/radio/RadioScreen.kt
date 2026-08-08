package com.vizvag.shieldvideo.ui.radio
import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.radio.BbcRadioMetadataRepository
import com.vizvag.shieldvideo.data.radio.CustomRadioStationConfig
import com.vizvag.shieldvideo.data.radio.RadioNowPlaying
import com.vizvag.shieldvideo.data.radio.RadioStation
import com.vizvag.shieldvideo.data.radio.RadioStations
import com.vizvag.shieldvideo.data.radio.RadioTrackHistory
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.playback.RadioRecordingService
import com.vizvag.shieldvideo.ui.components.AmbientBackdrop
import com.vizvag.shieldvideo.ui.components.GlassControlButton
import com.vizvag.shieldvideo.ui.components.GlassSelectedAlpha
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.RailPlayerVisibility
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AudioAccent
import com.vizvag.shieldvideo.ui.theme.AudioBackground
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.AudioScreenTheme
import com.vizvag.shieldvideo.ui.theme.AudioSurface
import com.vizvag.shieldvideo.ui.theme.AudioText
import com.vizvag.shieldvideo.ui.theme.AudioTextMuted
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.RadioChrome
import com.vizvag.shieldvideo.ui.theme.ScreenTheme
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.notice.AppNoticeBus
import com.vizvag.shieldvideo.ui.notice.AppNoticeKind
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalLiteVisuals
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.staggeredEntrance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
private const val RADIO_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
private const val PREFS = com.vizvag.shieldvideo.playback.radio.RadioPlaybackController.PREFS_RADIO
private const val KEY_LAST_STATION =
    com.vizvag.shieldvideo.playback.radio.RadioPlaybackController.KEY_LAST_STATION
private const val METADATA_POLL_MS = 30_000L
private val LeftPanePad = 14.dp
private val StationPaneWidth = 268.dp
private val HeroArtSize = 220.dp
private val TrackChipCorner = PallasShapes.control
@Composable
fun RadioScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit = onBack,
    onSelectShare: (String) -> Unit = {},
    onOpenLiveTv: () -> Unit = {},
    onOpenYouTube: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    ScreenTheme(RadioChrome) {
        RadioScreenBody(
            settingsRepository = settingsRepository,
            onBack = onBack,
            onOpenBrowser = onOpenBrowser,
            onSelectShare = onSelectShare,
            onOpenLiveTv = onOpenLiveTv,
            onOpenYouTube = onOpenYouTube,
            onOpenMusic = onOpenMusic,
            onOpenPodcasts = onOpenPodcasts,
            onOpenSettings = onOpenSettings,
        )
    }
}
@Composable
private fun RadioScreenBody(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectShare: (String) -> Unit,
    onOpenLiveTv: () -> Unit,
    onOpenYouTube: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var customStations by remember { mutableStateOf(emptyList<CustomRadioStationConfig>()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settingsRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                customStations = settingsRepository.load().customRadioStations
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settingsRepository) {
        customStations = settingsRepository.load().customRadioStations
    }
    val stations = remember(customStations) { RadioStations.all(customStations) }
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val radioPlaybackState by app.radioPlayback.state.collectAsState()
    var selectedStationId by remember(stations) {
        val liveId = app.radioPlayback.state.value.stationId
        mutableStateOf(
            when {
                liveId.isNotBlank() && stations.any { it.id == liveId } -> liveId
                else -> prefs.getString(KEY_LAST_STATION, null)
            },
        )
    }
    val controllingRemote by com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.target.collectAsState()
    val remoteStatus by com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller.status.collectAsState()
    // Wait until we've mirrored the room station before pushing play — otherwise the phone
    // would overwrite the TV with its local last-station preference.
    var remoteReady by remember { mutableStateOf(controllingRemote == null) }
    // Deep link / LAN remote playStation() — keep dial + hero in sync with the shared player.
    LaunchedEffect(radioPlaybackState.stationId, controllingRemote) {
        if (controllingRemote != null) return@LaunchedEffect
        val id = radioPlaybackState.stationId
        if (id.isNotBlank() && id != selectedStationId && stations.any { it.id == id }) {
            selectedStationId = id
            prefs.edit().putString(KEY_LAST_STATION, id).apply()
        }
    }
    LaunchedEffect(controllingRemote?.host, controllingRemote?.port, stations) {
        val target = controllingRemote
        if (target == null) {
            remoteReady = true
            return@LaunchedEffect
        }
        remoteReady = false
        val polled = remoteStatus
        val status = if (
            polled?.mode == com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode.Radio &&
            polled.contentId.isNotBlank()
        ) {
            polled
        } else {
            com.vizvag.shieldvideo.ShieldVideoApp.instance.remoteClient
                .status(target)
                .getOrNull()
                ?.also {
                    com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller.publish(it)
                    com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller.kick()
                }
        }
        val roomId = status?.contentId?.takeIf { it.isNotBlank() }
        if (roomId != null && stations.any { it.id == roomId }) {
            selectedStationId = roomId
        }
        remoteReady = true
    }
    LaunchedEffect(remoteStatus?.contentId, remoteStatus?.mode, remoteStatus?.isPlaying) {
        if (controllingRemote == null) return@LaunchedEffect
        val remote = remoteStatus ?: return@LaunchedEffect
        if (remote.mode != com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode.Radio) {
            return@LaunchedEffect
        }
        val roomId = remote.contentId
        if (roomId.isNotBlank() && roomId != selectedStationId && stations.any { it.id == roomId }) {
            selectedStationId = roomId
        }
    }
    LaunchedEffect(stations) {
        if (stations.none { it.id == selectedStationId }) {
            selectedStationId = stations.firstOrNull()?.id
        }
    }
    val station = stations.firstOrNull { it.id == selectedStationId } ?: stations.firstOrNull()
    val appSettings = remember(customStations) { settingsRepository.load() }
    val railShares = rememberOrderedShares(appSettings)
    val recordingFolder = remember(appSettings) { recordingFolderForRail(appSettings) }
    val appForRail = LocalContext.current.applicationContext as ShieldVideoApp
    val sleepForEmpty by appForRail.sleepTimer.state.collectAsState()
    if (station == null) {
        AppWithNavRail(
            destination = RailDestination.Radio,
            shares = railShares,
            selectedShare = appSettings.defaultShare,
            onSelectShare = onSelectShare,
            recordingFolder = recordingFolder,
            onLiveTv = onOpenLiveTv,
            onYouTube = onOpenYouTube,
            onRadio = {},
            onMusic = onOpenMusic,
            onPodcasts = onOpenPodcasts,
            sleepTimerActive = sleepForEmpty.active,
            sleepTimerLabel = sleepForEmpty.label,
            onCycleSleepTimer = appForRail.sleepTimer::cycle,
            onSettings = onOpenSettings,
            players = RailPlayerVisibility.from(appSettings),
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
        ) {
            AmbientBackdrop(intensity = 0.55f)
            SoftVignette()
            Column(modifier = Modifier.fillMaxSize()) {
                RadioTopBar(onBack = onBack)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 64.dp)
                    ) {
                        Text(
                            "No stations yet",
                            color = AudioText,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = (-0.5).sp,
                            fontFamily = PallasFontFamily,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Open Settings → Radio to add a stream,\nor tap Add BBC defaults.",
                            color = AudioTextMuted,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp,
                            fontFamily = PallasFontFamily,
                        )
                    }
                }
            }
        }
        }
        BackHandler(onBack = onBack)
        return
    }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0.35f) }
    var streamAttempt by remember(station.id) { mutableIntStateOf(0) }
    var nowPlaying by remember(station.id) { mutableStateOf<RadioNowPlaying>(RadioNowPlaying.Unavailable) }
    var metadataLoading by remember(station.id) { mutableStateOf(true) }
    var screenBlack by remember { mutableStateOf(false) }
    var showStopAfterDialog by remember { mutableStateOf(false) }
    var nasFind by remember { mutableStateOf<RadioNasFind?>(null) }
    val player = rememberRadioPlayer()
    val metadataRepo = remember { BbcRadioMetadataRepository() }
    val hostView = LocalView.current
    val playFocus = remember { FocusRequester() }
    val blackFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val sleepState by app.sleepTimer.state.collectAsState()
    val recordState by RadioRecordingService.state.collectAsState()
    val settingsRevision by app.settingsRepository.revision.collectAsState()
    var hueSyncReady by remember { mutableStateOf(app.settingsRepository.isHueSyncReady()) }
    var hueSyncEnabled by remember { mutableStateOf(app.settingsRepository.isHueSyncEnabled()) }
    LaunchedEffect(settingsRevision) {
        hueSyncReady = app.settingsRepository.isHueSyncReady()
        hueSyncEnabled = app.settingsRepository.isHueSyncEnabled()
    }

    fun openLibraryFind(query: String, asTrack: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        // Stay on Radio — search overlay keeps the stream playing until the user
        // actually starts Music (album/track) or leaves for another player.
        nasFind = if (asTrack) RadioNasFind.Track(q) else RadioNasFind.Artist(q)
    }

    fun playOnMusic(tracks: List<com.vizvag.shieldvideo.music.data.local.TrackEntity>) {
        if (tracks.isEmpty()) return
        scope.launch {
            // Hand off to Music: silence radio first so streams don't overlap.
            screenBlack = false
            player.pause()
            player.stop()
            playing = false
            nasFind = null
            app.musicModule.playerController.playTracks(tracks)
            onOpenMusic()
        }
    }
    DisposableEffect(player) {
        app.sleepTimer.bindPlayback(
            onVolume = { player.volume = it },
            onStop = {
                screenBlack = false
                player.pause()
                if (RadioRecordingService.state.value.recording) {
                    RadioRecordingService.finish(context)
                }
            }
        )
        onDispose {
            app.sleepTimer.unbindPlayback()
            // Keep recording in the background (timed stop / explicit stop); only
            // auto-finish when no timed stop is scheduled.
            val rec = RadioRecordingService.state.value
            if (rec.recording && rec.finishAtMs <= 0L) {
                RadioRecordingService.finish(context)
            }
        }
    }
    val streamUrls = station.streamFallbackUrls
    val activeStreamUrl = streamUrls.getOrElse(streamAttempt) { streamUrls.last() }
    // Keep the display out of ambient/screensaver while radio plays (Chromecast / Google TV).
    // Do NOT stop on ON_PAUSE alone — some devices fire pause for transient UI; KEEP_SCREEN_ON
    // prevents screensaver. When the user leaves the app (Home / other app), ON_STOP must kill audio.
    DisposableEffect(playing, hostView) {
        val window = (hostView.context as? Activity)?.window
        if (playing) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hostView.keepScreenOn = true
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hostView.keepScreenOn = false
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hostView.keepScreenOn = false
        }
    }
    val playerLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, playerLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                screenBlack = false
                player.pause()
                player.stop()
                player.clearMediaItems()
                playing = false
            }
        }
        playerLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            playerLifecycleOwner.lifecycle.removeObserver(observer)
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }
    DisposableEffect(player, station.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) {
                    error = null
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlayerError(e: PlaybackException) {
                buffering = false
                if (streamAttempt < streamUrls.lastIndex) {
                    streamAttempt++
                    error = geoFriendlyMessage(streamAttempt, streamUrls.size)
                } else {
                    error = "Stream unavailable — BBC may block this station outside the UK"
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(station.id, streamAttempt, activeStreamUrl, remoteReady) {
        prefs.edit().putString(KEY_LAST_STATION, station.id).apply()
        if (com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.isControllingRemote()) {
            if (!remoteReady) return@LaunchedEffect
            val roomId = remoteStatus?.contentId
            // Only push when the phone dial moved — don't re-fire for the mirrored room station.
            if (roomId != station.id || remoteStatus?.mode != com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode.Radio) {
                runCatching {
                    com.vizvag.shieldvideo.playback.remote.RemotePlayBridge.playRadio(station.id) {}
                }
            }
            playing = remoteStatus?.isPlaying == true
            buffering = false
            error = null
            return@LaunchedEffect
        }
        val live = com.vizvag.shieldvideo.ShieldVideoApp.instance.radioPlayback.state.value
        // Already on this station from playStation() / deep link — attach UI without restarting.
        // When the dial moves first (user pick), live still has the old id; fall through and retune.
        if (
            streamAttempt == 0 &&
            live.stationId == station.id &&
            player.mediaItemCount > 0
        ) {
            buffering = false
            playing = player.isPlaying || player.playWhenReady
            error = null
            com.vizvag.shieldvideo.ShieldVideoApp.instance.radioPlayback.notePlaying(
                station.id,
                station.name,
                live.streamUrl.ifBlank { activeStreamUrl },
            )
            return@LaunchedEffect
        }
        if (streamAttempt == 0) error = null
        buffering = true
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(RADIO_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
        val item = MediaItem.fromUri(activeStreamUrl)
        val source = if (activeStreamUrl.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.volume = 1f
        com.vizvag.shieldvideo.ShieldVideoApp.instance.radioPlayback.notePlaying(
            station.id,
            station.name,
            activeStreamUrl,
        )
    }
    LaunchedEffect(remoteStatus?.isPlaying, remoteStatus?.mode, controllingRemote) {
        if (controllingRemote == null) return@LaunchedEffect
        val remote = remoteStatus ?: return@LaunchedEffect
        if (remote.mode == com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode.Radio) {
            playing = remote.isPlaying
            buffering = false
        }
    }
    DisposableEffect(station.id) {
        onDispose {
            if (RadioRecordingService.state.value.recording) {
                RadioRecordingService.finish(context)
            }
        }
    }
    LaunchedEffect(station.bbcServiceId) {
        if (station.bbcServiceId.isBlank()) {
            nowPlaying = RadioNowPlaying.Unavailable
            metadataLoading = false
            return@LaunchedEffect
        }
        metadataLoading = true
        nowPlaying = metadataRepo.fetchNowPlaying(station.bbcServiceId)
        metadataLoading = false
        while (true) {
            delay(METADATA_POLL_MS)
            nowPlaying = metadataRepo.fetchNowPlaying(station.bbcServiceId)
            metadataLoading = false
        }
    }
    LaunchedEffect(playing, buffering) {
        while (true) {
            level = when {
                !playing -> 0.12f
                buffering -> 0.2f + Random.nextFloat() * 0.15f
                else -> 0.35f + Random.nextFloat() * 0.65f
            }
            delay(90)
        }
    }
    BackHandler {
        when {
            screenBlack -> screenBlack = false
            nasFind != null -> nasFind = null
            else -> onBack()
        }
    }
    val bgTop by animateColorAsState(station.accentDeep, label = "bgTop")
    val bgAccent by animateColorAsState(station.accent.copy(alpha = 0.38f), label = "bgAccent")
    val artworkUrl = when (val np = nowPlaying) {
        is RadioNowPlaying.Music -> np.imageUrl
        is RadioNowPlaying.Show -> np.imageUrl
        RadioNowPlaying.Unavailable -> null
    }
    val recentTracks = (nowPlaying as? RadioNowPlaying.Music)?.recent.orEmpty()
    AppWithNavRail(
        destination = RailDestination.Radio,
        shares = railShares,
        selectedShare = appSettings.defaultShare,
        onSelectShare = onSelectShare,
        recordingFolder = recordingFolder,
        onLiveTv = onOpenLiveTv,
        onYouTube = onOpenYouTube,
        onRadio = {},
        onMusic = onOpenMusic,
        onPodcasts = onOpenPodcasts,
        sleepTimerActive = sleepState.active,
        sleepTimerLabel = sleepState.label,
        onCycleSleepTimer = app.sleepTimer::cycle,
        onSettings = onOpenSettings,
        showRail = !screenBlack,
        railFocusEnabled = nasFind == null,
        players = RailPlayerVisibility.from(appSettings),
    ) {
    Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT — glass station list
            Column(
                modifier = Modifier
                    .width(StationPaneWidth)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AudioSurface.copy(alpha = 0.98f),
                                AppBackground.copy(alpha = 0.96f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.08f),
                                AudioAccent.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                        ),
                        shape = RoundedCornerShape(0.dp),
                    ),
            ) {
                RadioTopBar(
                    onBack = {
                        if (nasFind != null) nasFind = null else onBack()
                    },
                    compact = true,
                )
                StationListPane(
                    stations = stations,
                    selected = station,
                    onSelect = {
                        selectedStationId = it.id
                        streamAttempt = 0
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = LeftPanePad, vertical = 8.dp),
                )
            }
            // RIGHT — cinematic stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(AppBackground),
            ) {
                // BBC artwork as full-bleed atmosphere
                if (!artworkUrl.isNullOrBlank()) {
                    val liteVisuals = LocalLiteVisuals.current
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (liteVisuals) 0.28f else 0.42f }
                            .then(
                                if (!liteVisuals && android.os.Build.VERSION.SDK_INT >= 31) {
                                    Modifier.blur(32.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(bgTop, AudioBackground, Color.Black),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(bgAccent, Color.Transparent),
                                    center = Offset(0.55f, 0.35f),
                                    radius = 1200f,
                                ),
                            ),
                    )
                }
                GraphicEqualizerBackdrop(
                    accent = station.accent,
                    level = level,
                    playing = playing && !buffering,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .align(Alignment.BottomCenter),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to AppBackground.copy(alpha = 0.55f),
                                    0.35f to AppBackground.copy(alpha = 0.28f),
                                    0.75f to AppBackground.copy(alpha = 0.55f),
                                    1.0f to AppBackground.copy(alpha = 0.88f),
                                ),
                            ),
                        ),
                )
                SoftVignette()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 20.dp)
                        .zIndex(2f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hueSyncReady) {
                        GlassControlButton(
                            selected = hueSyncEnabled,
                            size = 52.dp,
                            onClick = {
                                val next = app.settingsRepository.toggleHueSync()
                                if (next == null) {
                                    AppNoticeBus.show(
                                        "Set up Hue in Settings → Integrations",
                                        AppNoticeBus.inferKind("Set up"),
                                        title = "Radio",
                                    )
                                } else {
                                    hueSyncEnabled = next
                                    AppNoticeBus.show(
                                        if (next) "Hue sync on" else "Hue sync off",
                                        AppNoticeKind.Success,
                                        title = "Radio",
                                    )
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lightbulb,
                                contentDescription = if (hueSyncEnabled) "Hue sync on" else "Hue sync off",
                                tint = if (hueSyncEnabled) AudioAccent else Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    GlassControlButton(
                        selected = false,
                        size = 52.dp,
                        onClick = { screenBlack = true },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Brightness2,
                            contentDescription = "Black screen",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 36.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Sharp hero art from BBC metadata
                        Box(
                            modifier = Modifier
                                .size(HeroArtSize)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(
                                    1.dp,
                                    station.accent.copy(alpha = 0.55f),
                                    RoundedCornerShape(18.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!artworkUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = artworkUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = station.shortName.take(3),
                                    color = station.accent,
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            NowPlayingPanel(
                                station = station,
                                nowPlaying = nowPlaying,
                                loading = metadataLoading,
                                onArtistClick = { artist ->
                                    openLibraryFind(artist, asTrack = false)
                                },
                                onTrackClick = { track ->
                                    openLibraryFind(track, asTrack = true)
                                },
                            )
                            StatusPill(
                                station = station,
                                playing = playing,
                                buffering = buffering,
                                error = error,
                                streamHint = streamQualityLabel(activeStreamUrl),
                                recording = recordState.recording,
                                finishAtMs = recordState.finishAtMs,
                                recordMessage = recordState.message,
                                trailing = {
                                    PlayPauseButton(
                                        playing = playing && !buffering,
                                        accent = station.accent,
                                        focusRequester = playFocus,
                                        onClick = {
                                            if (com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.isControllingRemote()) {
                                                val device = com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.current()
                                                    ?: return@PlayPauseButton
                                                scope.launch {
                                                    val action = if (playing) {
                                                        com.vizvag.shieldvideo.playback.remote.TransportAction.Pause
                                                    } else {
                                                        com.vizvag.shieldvideo.playback.remote.TransportAction.Play
                                                    }
                                                    app.remoteClient.transport(device, action)
                                                        .onSuccess {
                                                            playing = it.isPlaying
                                                            com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller.publish(it)
                                                            com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller.kick()
                                                        }
                                                }
                                            } else if (player.isPlaying) {
                                                player.pause()
                                            } else {
                                                player.volume = 1f
                                                player.play()
                                            }
                                        },
                                    )
                                    RecordButton(
                                        recording = recordState.recording,
                                        saving = recordState.saving,
                                        accent = station.accent,
                                        onClick = {
                                            if (recordState.recording || recordState.saving) {
                                                RadioRecordingService.finish(context)
                                            } else {
                                                RadioRecordingService.start(
                                                    context = context,
                                                    streamUrl = activeStreamUrl,
                                                    stationName = station.name,
                                                    programme = programmeTitle(nowPlaying),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            if (recordState.recording && !recordState.saving) {
                                                showStopAfterDialog = true
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (recentTracks.isNotEmpty()) {
                        RecentTracksRow(
                            tracks = recentTracks,
                            accent = station.accent,
                            onArtistClick = { artist ->
                                openLibraryFind(artist, asTrack = false)
                            },
                            onTrackClick = { track ->
                                openLibraryFind(track, asTrack = true)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 22.dp),
                        )
                    }
                }
                nasFind?.let { find ->
                    RadioNasFindPanel(
                        find = find,
                        search = { q -> app.musicModule.libraryRepository.search(q) },
                        accent = station.accent,
                        onClose = { nasFind = null },
                        onPlayArtist = { artist ->
                            scope.launch {
                                val list = app.musicModule.libraryRepository
                                    .getTracksForArtistBrowse(artist)
                                playOnMusic(list)
                            }
                        },
                        onPlayAlbum = { album ->
                            scope.launch {
                                val list = app.musicModule.libraryRepository
                                    .getTracksByAlbum(album.albumId)
                                playOnMusic(list)
                            }
                        },
                        onPlayTrack = { track -> playOnMusic(listOf(track)) },
                        onQueryChange = { nasFind = it },
                    )
                }
            }
        }
        if (showStopAfterDialog && recordState.recording) {
            StopRecordingAfterDialog(
                finishAtMs = recordState.finishAtMs,
                onStopAfter = { minutes ->
                    RadioRecordingService.scheduleFinish(context, minutes)
                    showStopAfterDialog = false
                },
                onStopNow = {
                    RadioRecordingService.finish(context)
                    showStopAfterDialog = false
                },
                onCancelTimer = {
                    RadioRecordingService.cancelScheduledFinish(context)
                    showStopAfterDialog = false
                },
                onDismiss = { showStopAfterDialog = false },
            )
        }
        if (screenBlack) {
            BlackScreenOverlay(
                focusRequester = blackFocus,
                onWake = { screenBlack = false },
            )
        }
    }
    }
    LaunchedEffect(screenBlack) {
        if (screenBlack) {
            delay(80)
            runCatching { blackFocus.requestFocus() }
        } else {
            delay(80)
            runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { playFocus.requestFocus() }
    }
}
@Composable
private fun SoftVignette() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.58f to Color.Transparent,
                        0.85f to Color.Black.copy(alpha = 0.28f),
                        1.0f to Color.Black.copy(alpha = 0.55f)
                    ),
                    center = Offset(0.5f, 0.45f),
                    radius = 1400f
                )
            )
    )
}
private fun streamQualityLabel(url: String): String? = when {
    "320000" in url -> "320 kbps"
    "96000" in url -> "96 kbps (international)"
    "48000" in url -> "48 kbps (international)"
    else -> null
}
private fun geoFriendlyMessage(attempt: Int, total: Int): String =
    "UK stream blocked — trying alternative $attempt/${total - 1}…"
@Composable
private fun BlackScreenOverlay(
    focusRequester: FocusRequester,
    onWake: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black)
            .focusRequester(focusRequester)
            .clickable(role = Role.Button, onClick = onWake),
    )
}
@Composable
private fun NowPlayingPanel(
    station: RadioStation,
    nowPlaying: RadioNowPlaying,
    loading: Boolean,
    onArtistClick: (String) -> Unit = {},
    onTrackClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (nowPlaying) {
            is RadioNowPlaying.Music -> {
                val eyebrow = when {
                    !nowPlaying.showTitle.isNullOrBlank() -> nowPlaying.showTitle
                    else -> "NOW PLAYING"
                }
                Text(
                    text = eyebrow.uppercase(),
                    color = station.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    softWrap = true,
                    fontFamily = PallasFontFamily,
                )
                if (!nowPlaying.showEpisode.isNullOrBlank()) {
                    Text(
                        text = nowPlaying.showEpisode,
                        color = AudioTextMuted,
                        fontSize = 15.sp,
                        softWrap = true,
                        lineHeight = 20.sp,
                        fontFamily = PallasFontFamily,
                    )
                }
                if (nowPlaying.artist.isNotBlank()) {
                    RadioMetaLink(
                        text = nowPlaying.artist,
                        color = AudioText,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                        lineHeight = 40.sp,
                        enabled = true,
                        maxLines = Int.MAX_VALUE,
                        onClick = { onArtistClick(nowPlaying.artist) },
                    )
                }
                if (nowPlaying.track.isNotBlank()) {
                    RadioMetaLink(
                        text = nowPlaying.track,
                        color = AudioText.copy(alpha = 0.9f),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.sp,
                        lineHeight = 28.sp,
                        enabled = true,
                        maxLines = Int.MAX_VALUE,
                        onClick = { onTrackClick(nowPlaying.track) },
                    )
                }
                if (nowPlaying.status.isNotBlank()) {
                    Text(
                        text = nowPlaying.status,
                        color = AudioTextMuted,
                        fontSize = 13.sp,
                        softWrap = true,
                        fontFamily = PallasFontFamily,
                    )
                }
            }
            is RadioNowPlaying.Show -> {
                Text(
                    text = "ON AIR",
                    color = station.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    text = nowPlaying.title,
                    color = AudioText,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    softWrap = true,
                    lineHeight = 42.sp,
                )
                if (!nowPlaying.episode.isNullOrBlank()) {
                    Text(
                        text = nowPlaying.episode,
                        color = AudioText.copy(alpha = 0.9f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        softWrap = true,
                        lineHeight = 26.sp,
                    )
                }
                if (!nowPlaying.synopsis.isNullOrBlank()) {
                    Text(
                        text = nowPlaying.synopsis,
                        color = AudioTextMuted,
                        fontSize = 15.sp,
                        softWrap = true,
                        lineHeight = 22.sp,
                    )
                }
                val startMs = nowPlaying.startMs
                val endMs = nowPlaying.endMs
                if (startMs != null && endMs != null && endMs > startMs) {
                    val now = System.currentTimeMillis()
                    val progress = ((now - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = station.accent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
            }
            RadioNowPlaying.Unavailable -> {
                Text(
                    text = station.name.uppercase(),
                    color = station.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    text = if (loading) "Loading programme…" else station.tagline.ifBlank { station.name },
                    color = AudioText,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    softWrap = true,
                    lineHeight = 40.sp,
                )
                if (!loading && station.tagline.isNotBlank()) {
                    Text(
                        text = "Live radio",
                        color = AudioTextMuted,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
@Composable
private fun RadioMetaLink(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    letterSpacing: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        softWrap = true,
        lineHeight = lineHeight,
        fontFamily = PallasFontFamily,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = maxLines,
        modifier = modifier
            .glassInteract(
                focused = focused && enabled,
                selected = false,
                idleSurface = Color.Transparent,
                showIdleBorder = false,
                scaleOnFocus = false,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    feedback.click()
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusProperties { canFocus = enabled }
            .clickable(enabled = enabled, role = Role.Button, onClick = {
                feedback.click()
                onClick()
            })
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun RecentTracksRow(
    tracks: List<RadioTrackHistory>,
    accent: Color,
    onArtistClick: (String) -> Unit = {},
    onTrackClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "RECENTLY PLAYED",
            color = accent.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = PallasFontFamily,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = tracks,
                key = { "${it.artist}|${it.track}|${it.status}" },
            ) { entry ->
                RecentTrackChiplet(
                    entry = entry,
                    accent = accent,
                    onArtistClick = onArtistClick,
                    onTrackClick = onTrackClick,
                )
            }
        }
    }
}

@Composable
private fun RecentTrackChiplet(
    entry: RadioTrackHistory,
    accent: Color,
    onArtistClick: (String) -> Unit,
    onTrackClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 360.dp)
            .clip(RoundedCornerShape(TrackChipCorner))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(TrackChipCorner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (entry.artist.isNotBlank()) {
            RadioMetaLink(
                text = entry.artist,
                color = AudioText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                lineHeight = 18.sp,
                enabled = true,
                maxLines = Int.MAX_VALUE,
                onClick = { onArtistClick(entry.artist) },
            )
        }
        if (entry.track.isNotBlank()) {
            RadioMetaLink(
                text = entry.track,
                color = AudioTextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
                lineHeight = 17.sp,
                enabled = true,
                maxLines = Int.MAX_VALUE,
                onClick = { onTrackClick(entry.track) },
            )
        }
        if (entry.status.isNotBlank()) {
            Text(
                text = entry.status,
                color = accent.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                softWrap = true,
                fontFamily = PallasFontFamily,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun rememberRadioPlayer(): ExoPlayer {
    val app = com.vizvag.shieldvideo.ShieldVideoApp.instance
    return app.radioPlayback.player
}

@Composable
private fun RadioTopBar(
    onBack: () -> Unit,
    onBlackScreen: (() -> Unit)? = null,
    hueSyncReady: Boolean = false,
    hueSyncEnabled: Boolean = false,
    onToggleHueSync: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    if (compact) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LeftPanePad, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassControlButton(selected = false, size = 48.dp, onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = "Radio",
                    color = AudioText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    fontFamily = PallasFontFamily,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 32.dp),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 56.dp)
                    .width(56.dp)
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                AudioAccent.copy(alpha = 0.15f),
                                AudioAccent,
                                AudioAccent.copy(alpha = 0.15f),
                            ),
                        ),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconActionButton(selected = false, onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Radio",
                color = AudioText,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (hueSyncReady && onToggleHueSync != null) {
            IconActionButton(
                selected = hueSyncEnabled,
                onClick = onToggleHueSync,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = if (hueSyncEnabled) "Hue sync on" else "Hue sync off",
                    tint = if (hueSyncEnabled) AudioAccent else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (onBlackScreen != null) {
            IconActionButton(selected = false, onClick = onBlackScreen) {
                Icon(
                    imageVector = Icons.Filled.Brightness2,
                    contentDescription = "Black screen",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    station: RadioStation,
    playing: Boolean,
    buffering: Boolean,
    error: String?,
    streamHint: String?,
    recording: Boolean = false,
    finishAtMs: Long = 0L,
    recordMessage: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording, finishAtMs) {
        if (!recording || finishAtMs <= 0L) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingLabel = if (recording && finishAtMs > nowMs) {
        val mins = ((finishAtMs - nowMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        "REC · ${formatRemainingMinutes(mins)}"
    } else null
    val label = when {
        error != null -> "OFF AIR"
        remainingLabel != null -> remainingLabel
        recording -> "REC"
        buffering -> "TUNING"
        playing -> "ON AIR"
        else -> "PAUSED"
    }
    val pillColor = when {
        error != null -> Color(0xFFFF6E6E)
        recording -> Color(0xFFFF5252)
        buffering -> AudioTextMuted
        playing -> station.accent
        else -> AudioTextMuted
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(pillColor.copy(alpha = 0.16f))
                    .border(
                        width = 1.dp,
                        color = pillColor.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(100.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )
                    Text(
                        text = label,
                        color = pillColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                }
            }
            if (!streamHint.isNullOrBlank() && error == null) {
                Text(
                    text = streamHint,
                    color = AudioTextMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                color = AudioTextMuted,
                fontSize = 12.sp,
                softWrap = true,
            )
        } else if (!recordMessage.isNullOrBlank() && recordMessage.startsWith("Saved")) {
            Text(
                text = recordMessage,
                color = AudioTextMuted,
                fontSize = 12.sp,
                softWrap = true,
            )
        }
    }
}
@Composable
private fun PlayPauseButton(
    playing: Boolean,
    accent: Color,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .glassInteract(focused = focused, selected = playing)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = Color.White.copy(alpha = if (focused) 1f else 0.85f),
            modifier = Modifier.size(20.dp)
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordButton(
    recording: Boolean,
    saving: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val active = recording || saving
    val infinite = rememberInfiniteTransition(label = "recPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recPulseAlpha",
    )
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    val longPressTimeout = 450L
    val longClickEnabled = recording && !saving
    Box(
        modifier = Modifier
            .size(40.dp)
            .drawBehind {
                if (active) {
                    drawCircle(
                        color = Color(0xFFFF5252).copy(alpha = pulse * 0.35f),
                        radius = size.minDimension * 0.68f,
                    )
                }
            }
            .glassInteract(
                focused = focused,
                selected = false,
                idleSurface = if (active) Color(0xFFFF5252).copy(alpha = 0.88f)
                else Color.White.copy(alpha = 0.08f),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (saving) return@onPreviewKeyEvent false
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    longClickEnabled && event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        onLongClick()
                        true
                    }
                    longClickEnabled && isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressFired = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressFired = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        val wasLongPress = longClickEnabled && longPressFired
                        longPressJob?.cancel()
                        longPressJob = null
                        longPressFired = false
                        if (wasLongPress) onLongClick() else onClick()
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> true
                    else -> false
                }
            }
            .focusable()
            .combinedClickable(
                enabled = !saving,
                role = Role.Button,
                onClick = onClick,
                onLongClick = if (longClickEnabled) onLongClick else null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = when {
                saving -> "Saving recording"
                recording -> "Stop recording"
                else -> "Record"
            },
            tint = when {
                active -> Color.White
                focused -> Accent
                else -> Color(0xFFFF8A80)
            },
            modifier = Modifier.size(18.dp),
        )
    }
}
@Composable
private fun StopRecordingAfterDialog(
    finishAtMs: Long,
    onStopAfter: (Int) -> Unit,
    onStopNow: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Hold-OK release must not immediately activate the first option.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        armed = false
        delay(280)
        armed = true
    }
    fun run(action: () -> Unit) {
        if (armed) action()
    }
    val hasTimer = finishAtMs > System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Stop recording", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Stop after",
                    color = AudioTextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                RadioRecordingService.STOP_AFTER_MINUTES.forEach { minutes ->
                    StopRecordingDialogOption(
                        label = RadioRecordingService.formatMinutesLabel(minutes),
                        onClick = { run { onStopAfter(minutes) } },
                    )
                }
                StopRecordingDialogOption(
                    label = "Stop now",
                    color = Color(0xFFFF6E6E),
                    onClick = { run(onStopNow) },
                )
                if (hasTimer) {
                    StopRecordingDialogOption(
                        label = "Cancel timed stop",
                        color = AudioTextMuted,
                        onClick = { run(onCancelTimer) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { run(onDismiss) }) {
                Text("Close", color = AudioAccent)
            }
        },
        containerColor = CardSurface,
    )
}

@Composable
private fun StopRecordingDialogOption(
    label: String,
    onClick: () -> Unit,
    color: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = color,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = false)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

private fun formatRemainingMinutes(mins: Int): String = when {
    mins >= 60 -> {
        val h = mins / 60
        val m = mins % 60
        if (m == 0) "${h}h" else "${h}h ${m}m"
    }
    else -> "${mins}m"
}
private fun programmeTitle(nowPlaying: RadioNowPlaying): String? = when (nowPlaying) {
    is RadioNowPlaying.Music -> {
        nowPlaying.showTitle?.takeIf { it.isNotBlank() }
            ?: listOf(nowPlaying.artist, nowPlaying.track)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
                .ifBlank { null }
    }
    is RadioNowPlaying.Show -> {
        listOfNotNull(
            nowPlaying.title.takeIf { it.isNotBlank() },
            nowPlaying.episode?.takeIf { it.isNotBlank() },
        ).joinToString(" — ").ifBlank { null }
    }
    RadioNowPlaying.Unavailable -> null
}
@Composable
private fun StationListPane(
    stations: List<RadioStation>,
    selected: RadioStation,
    onSelect: (RadioStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = stations.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    LaunchedEffect(selected.id) {
        listState.animateScrollToItem(selectedIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(stations, key = { _, s -> s.id }) { index, s ->
            StationListRow(
                station = s,
                selected = s.id == selected.id,
                onClick = { onSelect(s) },
                index = index,
            )
        }
    }
}
@Composable
private fun StationListRow(
    station: RadioStation,
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    val logoRes = station.logoRes
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .staggeredEntrance(visible = true, index = index)
            .glassInteract(
                focused = focused,
                selected = selected,
                idleSurface = if (logoRes != null) Color.Transparent else station.accentDeep.copy(alpha = 0.55f),
                selectedAlpha = if (logoRes != null) 0.14f else GlassSelectedAlpha,
            )
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .clickable {
                feedback.click()
                onClick()
            },
    ) {
        if (logoRes != null) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (focused || selected) 0.28f else 0.18f },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.82f),
                                0.55f to Color.Black.copy(alpha = 0.72f),
                                1.0f to Color.Black.copy(alpha = 0.62f),
                            ),
                        ),
                    ),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(station.accent.copy(alpha = 0.16f)),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                station.accentDeep.copy(alpha = 0.9f),
                                station.accent.copy(alpha = 0.35f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = station.name,
                    color = AudioText,
                    fontSize = 15.sp,
                    fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.SemiBold,
                    softWrap = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    fontFamily = PallasFontFamily,
                )
                Text(
                    text = station.tagline.ifBlank { station.shortName },
                    color = AudioTextMuted,
                    fontSize = 12.sp,
                    softWrap = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                    fontFamily = PallasFontFamily,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(station.accent.copy(alpha = 0.28f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "ON",
                        color = station.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = PallasFontFamily,
                    )
                }
            }
        }
    }
}
@Composable
private fun GraphicEqualizerBackdrop(
    accent: Color,
    level: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val liteVisuals = LocalLiteVisuals.current
    if (liteVisuals) {
        Box(
            modifier = modifier.background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.08f), Color.Transparent),
                ),
            ),
        )
        return
    }
    val infinite = rememberInfiniteTransition(label = "eq")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (playing) 2400 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eqPhase",
    )
    val pulse by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(90),
        label = "eqPulse",
    )
    val barCount = 56
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = w / (barCount * 2.4f)
        val barW = ((w - gap * (barCount + 1)) / barCount).coerceAtLeast(2f)
        val baseline = h * 0.92f
        for (i in 0 until barCount) {
            val t = i / (barCount - 1f)
            val wave = (
                sin(phase + t * 9.5f) * 0.38f +
                    sin(phase * 1.7f + t * 4.2f) * 0.28f +
                    sin(phase * 0.55f + t * 17f) * 0.18f +
                    0.5f
                ).coerceIn(0.08f, 1f)
            val energy = if (playing) (0.22f + pulse * 0.78f) else 0.12f
            val barH = h * 0.78f * wave * energy
            val x = gap + i * (barW + gap)
            val top = baseline - barH
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.08f + energy * 0.22f),
                        accent.copy(alpha = 0.35f + energy * 0.45f),
                        accent.copy(alpha = 0.75f),
                    ),
                    startY = top,
                    endY = baseline,
                ),
                topLeft = Offset(x, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
