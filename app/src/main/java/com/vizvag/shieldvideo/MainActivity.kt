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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.vizvag.shieldvideo.ui.background.BackgroundImageController
import com.vizvag.shieldvideo.ui.browser.BrowseNavRequests
import com.vizvag.shieldvideo.ui.browser.BrowserScreen
import com.vizvag.shieldvideo.ui.browser.BrowserViewModel
import com.vizvag.shieldvideo.ui.browser.BrowserViewModelFactory
import com.vizvag.shieldvideo.ui.components.AmbientBackdrop
import com.vizvag.shieldvideo.ui.components.AppClockOverlay
import com.vizvag.shieldvideo.ui.home.HomeLandingScreen
import com.vizvag.shieldvideo.ui.iptv.IptvScreen
import com.vizvag.shieldvideo.ui.music.MusicScreen
import com.vizvag.shieldvideo.ui.radio.RadioScreen
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.iptv.IptvViewModel
import com.vizvag.shieldvideo.ui.iptv.IptvViewModelFactory
import com.vizvag.shieldvideo.ui.iptv.MultiviewScreen
import com.vizvag.shieldvideo.ui.settings.SettingsScreen
import com.vizvag.shieldvideo.ui.settings.SettingsViewModel
import com.vizvag.shieldvideo.ui.settings.SettingsViewModelFactory
import com.vizvag.shieldvideo.ui.theme.ShieldVideoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vlcLauncher: VlcLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent, application as ShieldVideoApp)
    }

    /** @return true if this launch was consumed as a deep-link handoff (UI not needed). */
    private fun handleDeepLink(intent: Intent?, app: ShieldVideoApp): Boolean {
        if (DeepLinkPlayer.isStopIntent(intent)) {
            app.resumeMonitor.stopPlayer()
            LocalMediaProxyService.stop(this)
            window.decorView.postDelayed({ finish() }, 300L)
            return true
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
    val backgroundModel by backgroundImages.imageModel.collectAsState()
    val fallbackGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF070708),
            Color(0xFF0B0B0D),
            Color(0xFF121216)
        )
    )

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

        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { routeEnter },
            exitTransition = { routeExit },
            popEnterTransition = { routePopEnter },
            popExitTransition = { routePopExit },
        ) {
            composable("home") {
                val homeSettings = settingsRepository.load()
                HomeLandingScreen(
                    settings = homeSettings,
                    onOpenLiveTv = {
                        navController.navigate("iptv") {
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
                    onOpenShare = { share ->
                        BrowseNavRequests.requestShare(share)
                        navController.navigate("browser") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
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
                    onOpenRadio = {
                        appContext.musicModule.playerController.stop()
                        navController.navigate("radio") {
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
                    onOpenMusic = {
                        navController.navigate("music") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
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
                        ShieldVideoApp.instance.settingsBackupManager
                    )
                )
                SettingsScreen(
                    viewModel = viewModel,
                    nasRepository = nasRepository,
                    onBack = { navController.popBackStack() },
                    notificationAccessEnabled = resumeMonitor.isNotificationAccessEnabled()
                )
            }
        }

        val settingsRevision by settingsRepository.revision.collectAsState()
        val clockCorner = remember(settingsRevision) {
            settingsRepository.load().clockCorner
        }
        AppClockOverlay(corner = clockCorner)
    }
}
