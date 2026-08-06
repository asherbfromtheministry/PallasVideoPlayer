package com.vizvag.shieldvideo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.data.index.VideoIndexController
import com.vizvag.shieldvideo.data.iptv.IptvFavoritesStore
import com.vizvag.shieldvideo.data.iptv.IptvParentalStore
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore
import com.vizvag.shieldvideo.data.iptv.IptvRepository
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.tmdb.TmdbRepository
import com.vizvag.shieldvideo.data.trakt.MetadataOverrideStore
import com.vizvag.shieldvideo.data.trakt.TraktAuthRepository
import com.vizvag.shieldvideo.data.trakt.TraktRepository
import com.vizvag.shieldvideo.playback.DeepLinkPlayer
import com.vizvag.shieldvideo.playback.LocalMediaProxyService
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.PlayerLaunchResult
import com.vizvag.shieldvideo.playback.ResumeMonitor
import com.vizvag.shieldvideo.playback.VlcLauncher
import com.vizvag.shieldvideo.playback.remote.RemoteControlService
import com.vizvag.shieldvideo.playback.remote.RemoteControllerSessions
import com.vizvag.shieldvideo.playback.remote.RemoteNavBridge
import com.vizvag.shieldvideo.playback.remote.RemoteNavRequests
import com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode
import com.vizvag.shieldvideo.playback.remote.RemoteStatus
import com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller
import com.vizvag.shieldvideo.playback.remote.RemoteTargetStore
import com.vizvag.shieldvideo.playback.remote.RemoteUiRouteStore
import com.vizvag.shieldvideo.playback.remote.TransportAction
import com.vizvag.shieldvideo.ui.background.BackgroundImageController
import com.vizvag.shieldvideo.ui.browser.BrowseNavRequests
import com.vizvag.shieldvideo.ui.browser.BrowserScreen
import com.vizvag.shieldvideo.ui.browser.BrowserViewModel
import com.vizvag.shieldvideo.ui.browser.BrowserViewModelFactory
import com.vizvag.shieldvideo.ui.components.AmbientBackdrop
import com.vizvag.shieldvideo.ui.components.AppClockOverlay
import com.vizvag.shieldvideo.ui.components.ForcedLandscape
import com.vizvag.shieldvideo.ui.home.HomeLandingScreen
import com.vizvag.shieldvideo.ui.iptv.IptvScreen
import com.vizvag.shieldvideo.ui.music.MusicScreen
import com.vizvag.shieldvideo.ui.radio.RadioScreen
import com.vizvag.shieldvideo.ui.remote.RemoteScreen
import com.vizvag.shieldvideo.ui.theme.LocalLiteVisuals
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.iptv.IptvViewModel
import com.vizvag.shieldvideo.ui.iptv.IptvViewModelFactory
import com.vizvag.shieldvideo.ui.iptv.MultiviewScreen
import com.vizvag.shieldvideo.ui.settings.SettingsScreen
import com.vizvag.shieldvideo.ui.settings.SettingsViewModel
import com.vizvag.shieldvideo.ui.settings.SettingsViewModelFactory
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.ShieldVideoTheme
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.youtube.YoutubeScreen
import com.vizvag.shieldvideo.ui.youtube.YoutubeViewModel
import com.vizvag.shieldvideo.ui.youtube.YoutubeViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REMOTE_ROUTE = "com.vizvag.shieldvideo.EXTRA_REMOTE_ROUTE"
    }

    private lateinit var vlcLauncher: VlcLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lockLandscapeFullscreen()
        consumeRemoteRouteExtra(intent)

        val app = application as ShieldVideoApp
        val settingsRepository = app.settingsRepository
        val nasRepository = app.nasRepository
        val traktRepository = TraktRepository()
        val traktAuthRepository = TraktAuthRepository()
        val tmdbRepository = TmdbRepository()
        vlcLauncher = VlcLauncher(this)
        val metadataOverrides = MetadataOverrideStore(applicationContext)

        if (handleDeepLink(intent, app)) {
            return
        }

        setContent {
            ShieldVideoTheme {
                ShieldVideoAppNav(
                    settingsRepository = settingsRepository,
                    nasRepository = nasRepository,
                    traktRepository = traktRepository,
                    traktAuthRepository = traktAuthRepository,
                    tmdbRepository = tmdbRepository,
                    vlcLauncher = vlcLauncher,
                    resumeStore = app.resumeStore,
                    resumeMonitor = app.resumeMonitor,
                    progressSync = app.progressSync,
                    nasWatchHistory = app.nasWatchHistory,
                    metadataOverrides = metadataOverrides,
                    backgroundImages = app.backgroundImages,
                    videoIndex = app.videoIndex,
                    iptvRepository = app.iptvRepository,
                    iptvFavorites = app.iptvFavorites,
                    iptvParental = app.iptvParental,
                    iptvRecordings = app.iptvRecordings
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lockLandscapeFullscreen()
        RemoteStatusPoller.onForeground()
        // Phones/tablets are remotes only — don't advertise a control server on them.
        if (!isTelevisionDevice()) {
            RemoteControlService.stop(this)
            return
        }
        RemoteControlService.start(this)
    }

    override fun onResume() {
        super.onResume()
        lockLandscapeFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) lockLandscapeFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Samsung/large-screen OEMs ignore screenOrientation — keep requesting + immersive.
        lockLandscapeFullscreen()
    }

    /** Fixed landscape + immersive bars. Tablets may still rotate the window; Compose forces landscape. */
    private fun lockLandscapeFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (isTelevisionDevice()) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun isTelevisionDevice(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    override fun onStop() {
        RemoteStatusPoller.onBackground()
        // Hard rule: leaving the app must kill in-app audio/video.
        val app = application as ShieldVideoApp
        runCatching { app.musicModule.playerController.stop() }
        runCatching { app.radioPlayback.stop() }
        runCatching { app.podcastPlayback.stop() }
        runCatching { app.iptvPlayback.stop() }
        runCatching { app.youtubePlayback.stop() }
        // Keep remote control alive while VLC handoff FGS is streaming (TVs only).
        if (isTelevisionDevice() &&
            !app.resumeMonitor.isPlayerActive() &&
            app.resumeMonitor.activeVideoPath() == null
        ) {
            RemoteControlService.stop(this)
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRemoteRouteExtra(intent)
        handleDeepLink(intent, application as ShieldVideoApp)
    }

    private fun consumeRemoteRouteExtra(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_REMOTE_ROUTE)?.trim().orEmpty()
        if (route.isNotEmpty()) {
            RemoteNavRequests.requestRoute(route)
            intent?.removeExtra(EXTRA_REMOTE_ROUTE)
        }
    }

    /** @return true if this launch was consumed as a deep-link handoff (UI not needed). */
    private fun handleDeepLink(intent: Intent?, app: ShieldVideoApp): Boolean {
        if (DeepLinkPlayer.isStopIntent(intent)) {
            app.resumeMonitor.stopPlayer()
            LocalMediaProxyService.stop(this)
            window.decorView.postDelayed({ finish() }, 300L)
            return true
        }
        if (DeepLinkPlayer.isRadioIntent(intent)) {
            val stationId = DeepLinkPlayer.radioStationIdFrom(intent!!)
            val stationName = DeepLinkPlayer.radioStationNameFrom(intent)
            lifecycleScope.launch {
                val result = app.playbackRouter.playRadioStation(
                    stationId = stationId.orEmpty(),
                    stationName = stationName.orEmpty(),
                )
                result.onFailure { err ->
                    Toast.makeText(
                        this@MainActivity,
                        err.message ?: "Unable to play radio station",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            // Keep UI so Radio screen can show; playRadioStation navigates there.
            return false
        }
        if (DeepLinkPlayer.isPodcastIntent(intent)) {
            val refresh = DeepLinkPlayer.podcastRefreshFrom(intent!!)
            val skipSec = DeepLinkPlayer.podcastSkipSecondsFrom(intent)
            val guid = DeepLinkPlayer.podcastGuidFrom(intent)
            val showId = DeepLinkPlayer.podcastShowIdFrom(intent)
            val label = DeepLinkPlayer.podcastLabelFrom(intent)
            val showName = DeepLinkPlayer.podcastShowNameFrom(intent)
            lifecycleScope.launch {
                when {
                    refresh -> {
                        val result = app.playbackRouter.refreshPodcastFeeds()
                        result.onFailure { err ->
                            Toast.makeText(
                                this@MainActivity,
                                err.message ?: "Unable to refresh podcasts",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    skipSec != null -> {
                        val result = app.playbackRouter.seekPodcastBy(skipSec * 1_000L)
                        result.onFailure { err ->
                            Toast.makeText(
                                this@MainActivity,
                                err.message ?: "Unable to seek podcast",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    else -> {
                        val result = app.playbackRouter.playPodcastEpisode(
                            episodeGuid = guid.orEmpty(),
                            showId = showId.orEmpty(),
                            label = label.orEmpty(),
                            showName = showName.orEmpty(),
                        )
                        result.onFailure { err ->
                            Toast.makeText(
                                this@MainActivity,
                                err.message ?: "Unable to play podcast episode",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            return false
        }
        if (!DeepLinkPlayer.isPlayIntent(intent)) return false

        val title = DeepLinkPlayer.titleFrom(intent!!)
        val positionMs = DeepLinkPlayer.positionMsFrom(intent)
        val share = DeepLinkPlayer.shareFrom(intent)
        val path = DeepLinkPlayer.pathFrom(intent)
        val hostHint = DeepLinkPlayer.hostFrom(intent)

        // Handoff: foreground localhost proxy (Pallas NAS auth + HTTP Range) → VLC.
        if (!share.isNullOrBlank() && !path.isNullOrBlank()) {
            val settings = app.settingsRepository.load()
            val playSettings = if (!hostHint.isNullOrBlank()) {
                settings.copy(host = hostHint)
            } else {
                settings
            }
            lifecycleScope.launch {
                try {
                    val mediaUri = LocalMediaProxyService.startAndAwait(
                        context = this@MainActivity,
                        share = share,
                        path = path,
                        host = playSettings.host,
                        title = title.ifBlank { path.substringAfterLast('/') }
                    )
                    val handoff = app.nasRepository.handoffUri(playSettings, share, path)
                    val result = vlcLauncher.play(
                        playbackUri = mediaUri,
                        relativePath = path,
                        title = title,
                        startPositionMs = positionMs
                    )
                    when (result) {
                        is PlayerLaunchResult.Success -> {
                            app.nasWatchHistory.record(
                                share,
                                path,
                                title.ifBlank { path.substringAfterLast('/') }
                            )
                            app.resumeMonitor.start(
                                path = path,
                                playbackUri = handoff.toString(),
                                title = title,
                                share = share,
                                host = playSettings.host
                            )
                            // FGS keeps the proxy alive; finishing avoids stealing focus from VLC.
                            window.decorView.postDelayed({ finish() }, 1_200L)
                        }
                        is PlayerLaunchResult.NotInstalled -> {
                            LocalMediaProxyService.stop(this@MainActivity)
                            Toast.makeText(this@MainActivity, "VLC is not installed", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        is PlayerLaunchResult.Failed -> {
                            LocalMediaProxyService.stop(this@MainActivity)
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                } catch (error: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        error.message ?: "Unable to start media proxy",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
            return true
        }

        val mediaUri = DeepLinkPlayer.mediaUriFrom(intent)
        if (mediaUri == null) {
            Toast.makeText(this, "Missing or unsupported media URI", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        val pathHint = DeepLinkPlayer.relativePathHint(mediaUri)
        val result = vlcLauncher.play(
            playbackUri = mediaUri,
            relativePath = pathHint,
            title = title,
            startPositionMs = positionMs
        )
        when (result) {
            is PlayerLaunchResult.Success -> {
                app.resumeMonitor.start(
                    path = pathHint,
                    playbackUri = mediaUri.toString(),
                    title = title
                )
                window.decorView.postDelayed({ finish() }, 400L)
            }
            is PlayerLaunchResult.NotInstalled -> {
                Toast.makeText(this, "VLC is not installed", Toast.LENGTH_LONG).show()
                finish()
            }
            is PlayerLaunchResult.Failed -> {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        return true
    }
}

@Composable
private fun ShieldVideoAppNav(
    settingsRepository: SettingsRepository,
    nasRepository: NasRepository,
    traktRepository: TraktRepository,
    traktAuthRepository: TraktAuthRepository,
    tmdbRepository: TmdbRepository,
    vlcLauncher: VlcLauncher,
    resumeStore: LocalResumeStore,
    resumeMonitor: ResumeMonitor,
    progressSync: NasProgressSync,
    nasWatchHistory: NasWatchHistoryStore,
    metadataOverrides: MetadataOverrideStore,
    backgroundImages: BackgroundImageController,
    videoIndex: VideoIndexController,
    iptvRepository: IptvRepository,
    iptvFavorites: IptvFavoritesStore,
    iptvParental: IptvParentalStore,
    iptvRecordings: IptvRecordingStore
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    var iptvFullscreen by remember { mutableStateOf(false) }
    var youtubeFullscreen by remember { mutableStateOf(false) }
    val backgroundModel by backgroundImages.imageModel.collectAsState()
    val fallbackGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF070708),
            Color(0xFF0B0B0D),
            Color(0xFF121216)
        )
    )
    val settingsRevision by settingsRepository.revision.collectAsState()
    val appSettings = remember(settingsRevision) { settingsRepository.load() }
    val clockCorner = appSettings.clockCorner
    val context = LocalContext.current
    val isPhoneOrTablet = remember {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val tv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature("android.software.leanback")
        !tv
    }
    // Tablets used as remotes: static backdrop — animated mesh burns GPU/battery.
    val liteVisuals = appSettings.liteVisuals || isPhoneOrTablet
    val startRoute = "home"

    CompositionLocalProvider(LocalLiteVisuals provides liteVisuals) {
    ForcedLandscape(enabled = isPhoneOrTablet) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fallbackGradient)
        )
        AmbientBackdrop(intensity = 0.72f)
        if (backgroundModel != null) {
            AsyncImage(
                model = backgroundModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.22f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF2070708))
            )
            AmbientBackdrop(intensity = 0.35f)
        }

        val routeEnter = fadeIn(Motion.enter()) + slideInHorizontally(
            animationSpec = tween(420, easing = Motion.EmphasizedDecelerate),
            initialOffsetX = { it / 28 },
        )
        val routeExit = fadeOut(Motion.exit()) + slideOutHorizontally(
            animationSpec = tween(280, easing = Motion.EmphasizedAccelerate),
            targetOffsetX = { -it / 36 },
        )
        val routePopEnter = fadeIn(Motion.enter()) + slideInHorizontally(
            animationSpec = tween(420, easing = Motion.EmphasizedDecelerate),
            initialOffsetX = { -it / 28 },
        )
        val routePopExit = fadeOut(Motion.exit()) + slideOutHorizontally(
            animationSpec = tween(280, easing = Motion.EmphasizedAccelerate),
            targetOffsetX = { it / 36 },
        )

        val remoteTarget by RemoteTargetStore.target.collectAsState()
        val showRemoteBanner = remoteTarget != null && !iptvFullscreen && !youtubeFullscreen
        val remoteControllers by RemoteControllerSessions.sessions.collectAsState()
        val showControlledBanner =
            !isPhoneOrTablet &&
                remoteTarget == null &&
                remoteControllers.isNotEmpty() &&
                !iptvFullscreen &&
                !youtubeFullscreen
        val remoteStatus by RemoteStatusPoller.status.collectAsState()
        val remoteBannerScope = rememberCoroutineScope()
        val remoteClient = remember { (context.applicationContext as ShieldVideoApp).remoteClient }
        val lifecycleOwner = LocalLifecycleOwner.current

        // Full-bleed stage — no system-bar padding that shrinks the TV layout.
        Box(modifier = Modifier.fillMaxSize()) {
            val navHost: @Composable (Modifier) -> Unit = { navModifier ->
            NavHost(
                navController = navController,
                startDestination = startRoute,
                modifier = navModifier,
                enterTransition = { routeEnter },
                exitTransition = { routeExit },
                popEnterTransition = { routePopEnter },
                popExitTransition = { routePopExit },
            ) {
            composable("home") {
                val homeSettings = remember(settingsRevision) { settingsRepository.load() }
                HomeLandingScreen(
                    settings = homeSettings,
                    onOpenLiveTv = {
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenYouTube = {
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenShare = { share ->
                        BrowseNavRequests.requestShare(share)
                        navController.navigate("browser") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenRemote = { navController.navigate("remote") },
                )
            }
            composable("browser") {
                val viewModel: BrowserViewModel = viewModel(
                    factory = BrowserViewModelFactory(
                        settingsRepository,
                        nasRepository,
                        traktRepository,
                        tmdbRepository,
                        vlcLauncher,
                        resumeStore,
                        resumeMonitor,
                        nasWatchHistory,
                        metadataOverrides,
                        videoIndex,
                        progressSync,
                    )
                )
                LaunchedEffect(backStackEntry?.destination?.route) {
                    if (backStackEntry?.destination?.route == "browser") {
                        viewModel.reloadFromSettings()
                    }
                }
                BrowserScreen(
                    viewModel = viewModel,
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenLiveTv = {
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenYouTube = {
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("music") {
                val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as ShieldVideoApp
                val appSettings = remember { settingsRepository.load() }
                fun openBrowser(share: String? = null, openSearch: Boolean = false) {
                    share?.let { BrowseNavRequests.requestShare(it) }
                    if (openSearch) BrowseNavRequests.requestOpenSearch()
                    navController.navigate("browser") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
                MusicScreen(
                    appSettings = appSettings,
                    onBack = {
                        appContext.musicModule.playerController.stop()
                        navController.popBackStack()
                    },
                    onOpenBrowser = { openBrowser() },
                    onSelectShare = { openBrowser(share = it) },
                    onOpenLiveTv = {
                        appContext.musicModule.playerController.stop()
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenYouTube = {
                        appContext.musicModule.playerController.stop()
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        appContext.musicModule.playerController.stop()
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        appContext.musicModule.playerController.stop()
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("radio") {
                fun openBrowser(share: String? = null, openSearch: Boolean = false) {
                    share?.let { BrowseNavRequests.requestShare(it) }
                    if (openSearch) BrowseNavRequests.requestOpenSearch()
                    navController.navigate("browser") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
                RadioScreen(
                    settingsRepository = settingsRepository,
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { openBrowser() },
                    onSelectShare = { openBrowser(share = it) },
                    onOpenLiveTv = {
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenYouTube = {
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("youtube") {
                val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as ShieldVideoApp
                val viewModel: YoutubeViewModel = viewModel(
                    factory = YoutubeViewModelFactory(
                        settingsRepository = settingsRepository,
                        repository = appContext.youtubeRepository,
                        historyStore = appContext.youtubeWatchHistory,
                    )
                )
                fun openBrowser(share: String? = null, openSearch: Boolean = false) {
                    share?.let { BrowseNavRequests.requestShare(it) }
                    if (openSearch) BrowseNavRequests.requestOpenSearch()
                    navController.navigate("browser") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
                YoutubeScreen(
                    viewModel = viewModel,
                    settingsRepository = settingsRepository,
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { openBrowser() },
                    onSelectShare = { openBrowser(share = it) },
                    onOpenLiveTv = {
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                    onFullscreenChanged = { youtubeFullscreen = it },
                )
            }
            composable("iptv") {
                val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as ShieldVideoApp
                val viewModel: IptvViewModel = viewModel(
                    factory = IptvViewModelFactory(
                        application = appContext,
                        settingsRepository = settingsRepository,
                        iptvRepository = iptvRepository,
                        favorites = iptvFavorites,
                        channelCustom = appContext.iptvChannelCustom,
                        parental = iptvParental,
                        recordings = iptvRecordings,
                        watchHistory = appContext.iptvWatchHistory,
                        searchHistory = appContext.iptvSearchHistory,
                        nasWatchHistory = appContext.nasWatchHistory,
                        resumeStore = appContext.resumeStore,
                        resumeMonitor = appContext.resumeMonitor,
                        progressSync = appContext.progressSync,
                        nasRepository = nasRepository,
                        playerLauncher = vlcLauncher
                    )
                )
                fun openBrowser(share: String? = null, openSearch: Boolean = false) {
                    share?.let { BrowseNavRequests.requestShare(it) }
                    if (openSearch) BrowseNavRequests.requestOpenSearch()
                    navController.navigate("browser") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
                IptvScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenMultiview = { navController.navigate("multiview") },
                    onOpenBrowser = { openBrowser() },
                    onSelectShare = { openBrowser(share = it) },
                    onOpenYouTube = {
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenPodcasts = {
                        navController.navigate("podcasts") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onFullscreenChanged = { iptvFullscreen = it },
                )
            }
            composable("podcasts") {
                val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as ShieldVideoApp
                val appSettings = remember(settingsRevision) { settingsRepository.load() }
                fun openBrowser(share: String? = null, openSearch: Boolean = false) {
                    share?.let { BrowseNavRequests.requestShare(it) }
                    if (openSearch) BrowseNavRequests.requestOpenSearch()
                    navController.navigate("browser") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
                com.vizvag.shieldvideo.ui.podcast.PodcastScreen(
                    appSettings = appSettings,
                    onBack = {
                        appContext.podcastPlayback.stop()
                        navController.popBackStack()
                    },
                    onSelectShare = { openBrowser(share = it) },
                    onOpenLiveTv = {
                        appContext.podcastPlayback.stop()
                        navController.navigate("iptv") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenYouTube = {
                        appContext.podcastPlayback.stop()
                        navController.navigate("youtube") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenRadio = {
                        appContext.podcastPlayback.stop()
                        navController.navigate("radio") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenMusic = {
                        appContext.podcastPlayback.stop()
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("multiview") {
                MultiviewScreen(
                    settingsRepository = settingsRepository,
                    iptvRepository = iptvRepository,
                    favorites = iptvFavorites,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModelFactory(
                        settingsRepository,
                        nasRepository,
                        traktAuthRepository,
                        backgroundImages,
                        vlcLauncher,
                        videoIndex,
                        ShieldVideoApp.instance.musicModule.musicIndex,
                        iptvParental,
                        ShieldVideoApp.instance.settingsBackupManager,
                        ShieldVideoApp.instance.youtubeRepository,
                        ShieldVideoApp.instance.podcastRepository,
                    )
                )
                SettingsScreen(
                    viewModel = viewModel,
                    nasRepository = nasRepository,
                    onBack = { navController.popBackStack() },
                    notificationAccessEnabled = resumeMonitor.isNotificationAccessEnabled()
                )
            }
            composable("remote") {
                RemoteScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate("home") {
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenRoom = { status ->
                        val route = status?.uiRoute
                            ?.takeIf { RemoteUiRouteStore.isMirrorable(it) }
                            ?: "home"
                        navController.navigate(route) {
                            launchSingleTop = true
                            popUpTo("remote") { inclusive = true }
                        }
                    },
                )
            }
            }
            }

            navHost(Modifier.fillMaxSize())

            if (showRemoteBanner) {
                val room = remoteTarget!!.deviceId.ifBlank { remoteTarget!!.host }
                val videoNowPlaying = remoteStatus?.takeIf {
                    it.mode == RemotePlaybackMode.NasVideo
                }
                // Tiny floating chip — must not steal vertical space from the stage.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(8f)
                        .padding(top = 8.dp, end = 10.dp)
                        .heightIn(max = 28.dp)
                        .wrapContentWidth()
                        .widthIn(max = 280.dp)
                        .background(CardSurface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                        .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (videoNowPlaying != null) {
                            "▶ ${videoNowPlaying.title.ifBlank { room }}"
                        } else {
                            room
                        },
                        color = TextCream,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    if (videoNowPlaying != null) {
                        val playing = videoNowPlaying.isPlaying
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Accent.copy(alpha = 0.35f))
                                .clickable {
                                    val device = remoteTarget ?: return@clickable
                                    remoteBannerScope.launch {
                                        remoteClient.transport(device, TransportAction.Toggle)
                                            .onSuccess { RemoteStatusPoller.publish(it) }
                                    }
                                }
                                .padding(3.dp),
                        )
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            tint = Color.White,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable {
                                    val device = remoteTarget ?: return@clickable
                                    remoteBannerScope.launch {
                                        remoteClient.transport(device, TransportAction.Stop)
                                            .onSuccess { RemoteStatusPoller.publish(it) }
                                    }
                                }
                                .padding(3.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Disconnect",
                        tint = Accent,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                RemoteTargetStore.clear()
                                navController.navigate("remote") {
                                    launchSingleTop = true
                                }
                            }
                            .padding(2.dp),
                    )
                }
            }

            if (showControlledBanner) {
                val names = remoteControllers.joinToString(" · ") { it.name }
                val label = when (remoteControllers.size) {
                    1 -> "Controlled by $names"
                    else -> "Controlled by ${remoteControllers.size}: $names"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(8f)
                        .padding(top = 8.dp, start = 10.dp)
                        .heightIn(max = 28.dp)
                        .wrapContentWidth()
                        .widthIn(max = 420.dp)
                        .background(CardSurface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                        .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Phonelink,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = label,
                        color = TextCream,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // TVs publish the visible screen; phones push their screen to the connected TV.
        LaunchedEffect(backStackEntry?.destination?.route, isPhoneOrTablet) {
            val route = backStackEntry?.destination?.route
            if (!isPhoneOrTablet) {
                RemoteUiRouteStore.set(route)
            } else if (RemoteTargetStore.isControllingRemote()) {
                RemoteNavBridge.pushRouteIfControlling(route)
            }
        }

        val pendingRemoteRoute by RemoteNavRequests.pendingRoute.collectAsState()
        LaunchedEffect(pendingRemoteRoute, lifecycleOwner) {
            val route = pendingRemoteRoute ?: return@LaunchedEffect
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val current = navController.currentDestination?.route
                if (current != route) {
                    navController.navigate(route) {
                        launchSingleTop = true
                        popUpTo("home") { inclusive = false }
                    }
                }
                RemoteNavRequests.consume(route)
            }
        }

        if (!iptvFullscreen && !youtubeFullscreen) {
            AppClockOverlay(corner = clockCorner)
        }
    }
    }
    }
}

