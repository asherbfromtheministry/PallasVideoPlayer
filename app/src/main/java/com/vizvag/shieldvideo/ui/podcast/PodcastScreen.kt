package com.vizvag.shieldvideo.ui.podcast

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.podcast.PodcastEpisode
import com.vizvag.shieldvideo.data.podcast.PodcastEpisodeSort
import com.vizvag.shieldvideo.data.podcast.PodcastShow
import com.vizvag.shieldvideo.data.podcast.PodcastShowSort
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.RailPlayerVisibility
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.components.AmbientBackdrop
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalLiteVisuals
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.TvFeedback
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun PodcastScreen(
    appSettings: AppSettings,
    onBack: () -> Unit,
    onSelectShare: (String) -> Unit,
    onOpenLiveTv: () -> Unit,
    onOpenYouTube: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val viewModel: PodcastViewModel = viewModel(
        factory = PodcastViewModelFactory(app.podcastRepository, app.podcastPlayback),
    )
    val ui by viewModel.ui.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val sleep by app.sleepTimer.state.collectAsState()
    val lite = LocalLiteVisuals.current
    val feedback = rememberTvFeedback()

    var showPicker by remember { mutableStateOf(false) }
    val blackout by app.blackout.active.collectAsState()
    val playFocus = remember { FocusRequester() }
    val episodeListFocus = remember { FocusRequester() }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val railShares = rememberOrderedShares(appSettings)
    val recordingFolder = remember(appSettings) { recordingFolderForRail(appSettings) }

    LaunchedEffect(ui.shows.isEmpty()) {
        if (ui.shows.isEmpty()) showPicker = false
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenOpened()
    }

    DisposableEffect(viewModel.playback) {
        app.sleepTimer.bindPlayback(
            onStop = {
                // Keep blackout up so HA standby can power off without flashing the UI.
                if (!viewModel.isRemoteSession()) {
                    viewModel.stopPlayback()
                }
            },
        )
        onDispose { app.sleepTimer.unbindPlayback() }
    }

    val controllingRemote by viewModel.controllingRemote.collectAsState()
    val keepScreenOn = controllingRemote == null && playback.isPlaying
    val refreshOverlay = ui.refreshOverlay
    DisposableEffect(lifecycleOwner, view, keepScreenOn) {
        val window = (view.context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = true
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                view.keepScreenOn = false
                if (!viewModel.isRemoteSession()) {
                    viewModel.stopPlayback()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
        }
    }

    BackHandler(enabled = refreshOverlay == null && !blackout) {
        when {
            showPicker -> showPicker = false
            else -> {
                if (!viewModel.isRemoteSession()) {
                    viewModel.stopPlayback()
                }
                onBack()
            }
        }
    }

    LaunchedEffect(blackout) {
        if (!blackout) {
            delay(80)
            runCatching { playFocus.requestFocus() }
        }
    }

    AppWithNavRail(
        destination = RailDestination.Podcasts,
        shares = railShares,
        selectedShare = appSettings.defaultShare,
        onSelectShare = onSelectShare,
        recordingFolder = recordingFolder,
        onLiveTv = {
            if (!viewModel.isRemoteSession()) viewModel.stopPlayback()
            onOpenLiveTv()
        },
        onYouTube = {
            if (!viewModel.isRemoteSession()) viewModel.stopPlayback()
            onOpenYouTube()
        },
        onRadio = {
            if (!viewModel.isRemoteSession()) viewModel.stopPlayback()
            onOpenRadio()
        },
        onMusic = {
            if (!viewModel.isRemoteSession()) viewModel.stopPlayback()
            onOpenMusic()
        },
        onPodcasts = {},
        sleepTimerActive = sleep.active,
        sleepTimerLabel = sleep.label,
        onCycleSleepTimer = app.sleepTimer::cycle,
        onSettings = onOpenSettings,
        showRail = !blackout,
        railFocusEnabled = !showPicker && !blackout && refreshOverlay == null,
        players = RailPlayerVisibility.from(appSettings),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground),
        ) {
            val artUrl = playback.imageUrl
                .ifBlank {
                    ui.selectedShow?.imageUrl
                        ?: ui.displayedEpisodes.firstOrNull()?.imageUrl.orEmpty()
                }
            if (artUrl.isNotBlank() && !lite) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(48.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f)),
                ) {}
            } else {
                AmbientBackdrop(intensity = 0.5f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            ) {}

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val compact = lite || maxHeight < 480.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    PodcastTopBar(
                        showTitle = when {
                            ui.browsingAll -> "All shows · recent"
                            else -> ui.selectedShow?.title.orEmpty()
                        },
                        refreshing = refreshOverlay != null,
                        feedback = feedback,
                        onShows = { showPicker = true },
                        onRefresh = { viewModel.refreshAllFeeds() },
                        onBlack = { app.blackout.enter() },
                        compact = compact,
                    )

                    if (ui.shows.isEmpty() && refreshOverlay == null) {
                        EmptyPodcastsState(modifier = Modifier.weight(1f))
                    } else if (ui.shows.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(
                                    start = if (compact) 10.dp else 28.dp,
                                    end = if (compact) 8.dp else 20.dp,
                                    bottom = if (compact) 8.dp else 20.dp,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 24.dp),
                        ) {
                            NowPlayingStage(
                                show = ui.selectedShow
                                    ?: ui.shows.firstOrNull { it.id == playback.showId }
                                    ?: ui.displayedEpisodes.firstOrNull()?.let { ep ->
                                        ui.shows.firstOrNull { it.id == ep.showId }
                                    },
                                episodeTitle = playback.episodeTitle.ifBlank {
                                    ui.displayedEpisodes.firstOrNull()?.title.orEmpty()
                                },
                                imageUrl = artUrl,
                                isPlaying = playback.isPlaying,
                                positionMs = playback.positionMs,
                                durationMs = playback.durationMs,
                                playFocus = playFocus,
                                episodeListFocus = episodeListFocus.takeIf {
                                    ui.displayedEpisodes.isNotEmpty()
                                },
                                onToggle = {
                                    if (playback.episodeGuid.isBlank()) {
                                        ui.displayedEpisodes.firstOrNull()?.let(viewModel::playEpisode)
                                    } else {
                                        viewModel.togglePlayPause()
                                    }
                                },
                                onBack15 = viewModel::seekBack,
                                onFwd30 = viewModel::seekForward,
                                onNewer = viewModel::playNewer,
                                onOlder = viewModel::playOlder,
                                feedback = feedback,
                                compact = compact,
                                modifier = Modifier
                                    .weight(if (compact) 1f else 1.15f)
                                    .fillMaxHeight(),
                            )
                            EpisodeListPane(
                                episodes = ui.displayedEpisodes,
                                hasMore = ui.hasMoreEpisodes,
                                loading = ui.loadingEpisodes,
                                playingGuid = playback.episodeGuid,
                                playingTitle = playback.episodeTitle,
                                progress = ui.progress,
                                showTitles = if (ui.browsingAll) ui.showTitleById else emptyMap(),
                                showImages = ui.shows.associate { it.id to it.imageUrl },
                                episodeSort = ui.episodeSort,
                                onCycleSort = viewModel::cycleEpisodeSort,
                                onPlay = viewModel::playEpisode,
                                onLoadMore = viewModel::loadMoreEpisodes,
                                playFocus = playFocus,
                                listFocusRequester = episodeListFocus,
                                feedback = feedback,
                                compact = compact,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (refreshOverlay != null) {
        PodcastRefreshOverlayDialog(overlay = refreshOverlay)
    }

    if (showPicker) {
        ShowsPickerDialog(
            shows = ui.shows,
            showGroups = ui.showGroups,
            showSort = ui.showSort,
            selectedId = ui.selectedShow?.id,
            browsingAll = ui.browsingAll,
            onCycleSort = viewModel::cycleShowSort,
            onSelectAll = {
                viewModel.selectAllShows()
                showPicker = false
            },
            onSelect = {
                viewModel.selectShow(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
            feedback = feedback,
        )
    }
}

@Composable
private fun PodcastTopBar(
    showTitle: String,
    refreshing: Boolean,
    feedback: TvFeedback,
    onShows: () -> Unit,
    onRefresh: () -> Unit,
    onBlack: () -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 8.dp else 24.dp,
                vertical = if (compact) 6.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        Icon(
            Icons.Filled.Podcasts,
            contentDescription = null,
            tint = LocalScreenChrome.current.accent,
            modifier = Modifier.size(if (compact) 20.dp else 28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Podcasts",
                color = Color.White,
                fontSize = if (compact) 16.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PallasFontFamily,
            )
            if (showTitle.isNotBlank()) {
                Text(
                    showTitle,
                    color = TextMuted,
                    fontSize = if (compact) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = PallasFontFamily,
                )
            }
        }
        ChromeChip(label = "Shows", icon = Icons.Filled.GridView, onClick = onShows, feedback = feedback, compact = compact)
        ChromeChip(
            label = if (refreshing) "…" else "Refresh",
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            feedback = feedback,
            compact = compact,
            enabled = !refreshing,
        )
        if (!compact) {
            ChromeChip(label = "Black", icon = Icons.Filled.Brightness2, onClick = onBlack, feedback = feedback)
        }
    }
}

@Composable
private fun ChromeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    feedback: TvFeedback,
    selected: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    leftFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .glassInteract(
                focused = focused,
                selected = selected,
                idleSurface = if (!enabled) CardSurface.copy(alpha = 0.5f) else CardSurface,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) feedback.focus()
            }
            .focusProperties {
                canFocus = enabled
                if (leftFocus != null) left = leftFocus
            }
            .clickable(enabled = enabled) {
                feedback.click()
                onClick()
            }
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) LocalScreenChrome.current.accent else Color.White,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
        )
        Text(
            label,
            color = Color.White,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PodcastRefreshOverlayDialog(overlay: PodcastRefreshOverlay) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = LocalScreenChrome.current.accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    overlay.headline,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PallasFontFamily,
                    textAlign = TextAlign.Center,
                )
                if (overlay.total > 0 && overlay.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        overlay.detail,
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontFamily = PallasFontFamily,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = {
                            (overlay.current.toFloat() / overlay.total.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = LocalScreenChrome.current.accent,
                        trackColor = Color.White.copy(alpha = 0.14f),
                    )
                } else if (overlay.detail.isNotBlank() && overlay.total <= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        overlay.detail,
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontFamily = PallasFontFamily,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPodcastsState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 64.dp),
        ) {
            Text(
                "No subscriptions yet",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Pull-to-refresh (↻) reloads from the saved NAS OPML,\nor Settings → Podcasts → Import OPML…",
                color = TextMuted,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                fontFamily = PallasFontFamily,
            )
        }
    }
}

@Composable
private fun NowPlayingStage(
    show: PodcastShow?,
    episodeTitle: String,
    imageUrl: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playFocus: FocusRequester,
    episodeListFocus: FocusRequester?,
    onToggle: () -> Unit,
    onBack15: () -> Unit,
    onFwd30: () -> Unit,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
    feedback: TvFeedback,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val artSize = if (compact) 96.dp else 220.dp
    val artCorner = if (compact) 12.dp else 18.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(artSize)
                .clip(RoundedCornerShape(artCorner))
                .background(CardSurface)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(artCorner)),
        ) {
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Podcasts,
                    contentDescription = null,
                    tint = LocalScreenChrome.current.accent.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (compact) 36.dp else 72.dp),
                )
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 22.dp))
        Text(
            episodeTitle.ifBlank { "Pick an episode" },
            color = Color.White,
            fontSize = if (compact) 15.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            fontFamily = PallasFontFamily,
            modifier = Modifier.fillMaxWidth(0.92f),
        )
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        Text(
            show?.title.orEmpty(),
            color = LocalScreenChrome.current.accent,
            fontSize = if (compact) 11.sp else 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = PallasFontFamily,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
        val frac = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(if (compact) 3.dp else 6.dp)
                .clip(RoundedCornerShape(if (compact) 2.dp else 3.dp)),
            color = LocalScreenChrome.current.accent,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        Text(
            "${formatMs(positionMs)} / ${formatMs(durationMs)}",
            color = TextMuted,
            fontSize = if (compact) 10.sp else 12.sp,
            fontFamily = PallasFontFamily,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Icons.Filled.SkipPrevious, "Newer", onNewer, feedback = feedback, compact = compact)
            TransportButton(Icons.Filled.FastRewind, "−15s", onBack15, feedback = feedback, compact = compact)
            TransportButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                onToggle,
                large = true,
                focusRequester = playFocus,
                feedback = feedback,
                compact = compact,
            )
            TransportButton(Icons.Filled.FastForward, "+30s", onFwd30, feedback = feedback, compact = compact)
            TransportButton(
                Icons.Filled.SkipNext, "Older", onOlder,
                feedback = feedback, compact = compact,
                // Rightmost transport → episode list (FocusRequester lives on the LazyColumn host).
                rightFocus = episodeListFocus,
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    large: Boolean = false,
    focusRequester: FocusRequester? = null,
    feedback: TvFeedback,
    compact: Boolean = false,
    rightFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val size = when {
        compact && large -> 40.dp
        compact -> 32.dp
        large -> 64.dp
        else -> 48.dp
    }
    val iconSize = when {
        compact && large -> 22.dp
        compact -> 16.dp
        large -> 34.dp
        else -> 24.dp
    }
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(size)
            .glassInteract(focused = focused, selected = false)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) feedback.focus()
            }
            .focusProperties {
                if (rightFocus != null) right = rightFocus
            }
            .clickable {
                feedback.click()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (large) LocalScreenChrome.current.accent else Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun EpisodeListPane(
    episodes: List<PodcastEpisode>,
    hasMore: Boolean,
    loading: Boolean,
    playingGuid: String,
    playingTitle: String,
    progress: Map<String, com.vizvag.shieldvideo.data.podcast.PodcastEpisodeProgress>,
    showTitles: Map<String, String>,
    showImages: Map<String, String>,
    episodeSort: PodcastEpisodeSort,
    onCycleSort: () -> Unit,
    onPlay: (PodcastEpisode) -> Unit,
    onLoadMore: () -> Unit,
    playFocus: FocusRequester,
    listFocusRequester: FocusRequester,
    feedback: TvFeedback,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val paneCorner = if (compact) 12.dp else 16.dp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(paneCorner))
            .background(CardSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(paneCorner))
            .padding(if (compact) 8.dp else 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Episodes",
                color = Color.White,
                fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PallasFontFamily,
            )
            ChromeChip(
                label = episodeSort.label,
                icon = Icons.AutoMirrored.Filled.Sort,
                onClick = onCycleSort,
                feedback = feedback,
                selected = false,
                compact = compact,
                leftFocus = playFocus,
            )
        }
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        when {
            loading && episodes.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = LocalScreenChrome.current.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(if (compact) 24.dp else 32.dp),
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    Text(
                        "Loading episodes…",
                        color = TextMuted,
                        fontSize = if (compact) 12.sp else 14.sp,
                    )
                }
            }
            episodes.isEmpty() -> {
                Text(
                    "No episodes",
                    color = TextMuted,
                    fontSize = if (compact) 12.sp else 14.sp,
                )
            }
            else -> {
                val firstEpisodeFocus = remember { FocusRequester() }
                val listScope = rememberCoroutineScope()
                // Host stays outside LazyColumn so scroll never detaches listFocusRequester.
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(1.dp)
                            .focusRequester(listFocusRequester)
                            .focusable()
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    listScope.launch {
                                        runCatching { listState.scrollToItem(0) }
                                        runCatching { firstEpisodeFocus.requestFocus() }
                                    }
                                }
                            },
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
                        contentPadding = PaddingValues(bottom = if (compact) 4.dp else 8.dp),
                    ) {
                        itemsIndexed(episodes, key = { _, e -> e.guid }) { index, ep ->
                            val playing = when {
                                playingGuid.isNotBlank() && playingGuid != "remote" ->
                                    ep.guid == playingGuid
                                playingTitle.isNotBlank() -> ep.title == playingTitle
                                else -> false
                            }
                            EpisodeRow(
                                episode = ep,
                                showTitle = showTitles[ep.showId],
                                showImageUrl = showImages[ep.showId],
                                playing = playing,
                                progressFrac = progress[ep.guid]?.progressFraction ?: 0f,
                                completed = progress[ep.guid]?.completed == true,
                                onPlay = { onPlay(ep) },
                                onNearEnd = {
                                    if (hasMore && index >= episodes.lastIndex - 1) {
                                        onLoadMore()
                                    }
                                },
                                feedback = feedback,
                                compact = compact,
                                focusRequester = if (index == 0) firstEpisodeFocus else null,
                                leftFocus = playFocus,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    playing: Boolean,
    progressFrac: Float,
    completed: Boolean,
    onPlay: () -> Unit,
    feedback: TvFeedback,
    showTitle: String? = null,
    showImageUrl: String? = null,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
    leftFocus: FocusRequester? = null,
    onNearEnd: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val artUrl = episode.imageUrl.ifBlank { showImageUrl.orEmpty() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassInteract(
                focused = focused,
                selected = false,
                idleSurface = Color.Transparent,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    feedback.focus()
                    onNearEnd?.invoke()
                }
            }
            .focusProperties {
                if (leftFocus != null) left = leftFocus
            }
            .clickable {
                feedback.click()
                onPlay()
            },
    ) {
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.82f),
                                0.55f to Color.Black.copy(alpha = 0.62f),
                                1f to Color.Black.copy(alpha = 0.38f),
                            ),
                        ),
                    ),
            )
            when {
                playing -> Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(LocalScreenChrome.current.accent.copy(alpha = 0.22f)),
                )
                focused -> Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 6.dp else 10.dp,
                ),
        ) {
            if (!showTitle.isNullOrBlank()) {
                Text(
                    showTitle,
                    color = LocalScreenChrome.current.accent.copy(alpha = 0.95f),
                    fontSize = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = PallasFontFamily,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                episode.title,
                color = when {
                    completed -> TextMuted.copy(alpha = 0.85f)
                    playing -> LocalScreenChrome.current.accent
                    else -> Color.White
                },
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                if (episode.publishEpochMs > 0L) {
                    Text(
                        formatDate(episode.publishEpochMs),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = if (compact) 9.sp else 11.sp,
                    )
                }
                if (episode.durationSec > 0L) {
                    Text(
                        formatDuration(episode.durationSec),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = if (compact) 9.sp else 11.sp,
                    )
                }
                if (completed) {
                    Text(
                        "Played",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = if (compact) 9.sp else 11.sp,
                    )
                }
            }
            if (!completed && progressFrac > 0.02f) {
                Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                LinearProgressIndicator(
                    progress = { progressFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 2.dp else 3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = LocalScreenChrome.current.accent,
                    trackColor = Color.White.copy(alpha = 0.22f),
                )
            }
        }
    }
}

@Composable
private fun ShowsPickerDialog(
    shows: List<PodcastShow>,
    showGroups: List<Pair<String, List<PodcastShow>>>,
    showSort: PodcastShowSort,
    selectedId: String?,
    browsingAll: Boolean,
    onCycleSort: () -> Unit,
    onSelectAll: () -> Unit,
    onSelect: (PodcastShow) -> Unit,
    onDismiss: () -> Unit,
    feedback: TvFeedback,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(36.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Your shows",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = PallasFontFamily,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${shows.size} subscriptions · sorted by ${showSort.label} · OK to open · Back to close",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                    ChromeChip(
                        label = showSort.label,
                        icon = Icons.AutoMirrored.Filled.Sort,
                        onClick = onCycleSort,
                        feedback = feedback,
                        selected = true,
                    )
                }
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "all-shows") {
                        AllShowsTile(
                            selected = browsingAll,
                            count = shows.size,
                            onClick = onSelectAll,
                            feedback = feedback,
                        )
                    }
                    showGroups.forEach { (genre, group) ->
                        if (genre.isNotBlank()) {
                            item(
                                key = "hdr-$genre",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Text(
                                    genre,
                                    color = LocalScreenChrome.current.accent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = PallasFontFamily,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(group, key = { it.id }) { show ->
                            ShowTile(
                                show = show,
                                selected = !browsingAll && show.id == selectedId,
                                dateEpochMs = show.latestEpisodeEpochMs,
                                subtitle = when (showSort) {
                                    PodcastShowSort.GENRE -> null
                                    PodcastShowSort.RECENT -> null
                                    else -> show.primaryGenre.takeIf {
                                        it != "Uncategorised" && show.genres.isNotEmpty()
                                    }
                                },
                                onClick = { onSelect(show) },
                                feedback = feedback,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllShowsTile(
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    feedback: TvFeedback,
) {
    var focused by remember { mutableStateOf(false) }
    val chrome = LocalScreenChrome.current
    val artShape = RoundedCornerShape(PallasShapes.art)
    Column(
        modifier = Modifier
            .width(140.dp)
            .glassInteract(
                focused = focused,
                selected = selected,
                idleSurface = Color.Transparent,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) feedback.focus()
            }
            .clickable {
                feedback.click()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(artShape)
                .background(chrome.surface.copy(alpha = 0.55f), artShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), artShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            chrome.accent.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    tint = LocalScreenChrome.current.accent,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "RECENT",
                    color = LocalScreenChrome.current.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = PallasFontFamily,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "All shows",
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontFamily = PallasFontFamily,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$count feeds",
            color = TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            fontFamily = PallasFontFamily,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ShowTile(
    show: PodcastShow,
    selected: Boolean,
    onClick: () -> Unit,
    feedback: TvFeedback,
    subtitle: String? = null,
    dateEpochMs: Long = 0L,
) {
    var focused by remember { mutableStateOf(false) }
    val chrome = LocalScreenChrome.current
    val artShape = RoundedCornerShape(PallasShapes.art)
    val relativeDate = remember(dateEpochMs) {
        if (dateEpochMs > 0L) formatRelativeAgo(dateEpochMs) else ""
    }
    Column(
        modifier = Modifier
            .width(140.dp)
            .glassInteract(
                focused = focused,
                selected = selected,
                idleSurface = Color.Transparent,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) feedback.focus()
            }
            .clickable {
                feedback.click()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(artShape)
                .background(chrome.surface.copy(alpha = 0.55f), artShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), artShape),
        ) {
            if (show.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = show.imageUrl,
                    contentDescription = show.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Frosted glass wash over cover art
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.06f))
                        .background(chrome.accent.copy(alpha = 0.10f)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    chrome.accent.copy(alpha = 0.22f),
                                    chrome.surface.copy(alpha = 0.7f),
                                ),
                            ),
                        ),
                )
                Icon(
                    Icons.Filled.Podcasts,
                    contentDescription = null,
                    tint = LocalScreenChrome.current.accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
            }
            if (relativeDate.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        relativeDate,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            show.title,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontFamily = PallasFontFamily,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontFamily = PallasFontFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatDuration(sec: Long): String {
    if (sec <= 0L) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        else -> "${m} min"
    }
}

private fun formatRelativeAgo(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val delta = (now - epochMs).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        hours < 24L -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        days < 30L -> if (days == 1L) "1 day ago" else "$days days ago"
        days < 365L -> {
            val months = (days / 30L).coerceAtLeast(1L)
            if (months == 1L) "1 month ago" else "$months months ago"
        }
        else -> {
            val years = (days / 365L).coerceAtLeast(1L)
            if (years == 1L) "1 year ago" else "$years years ago"
        }
    }
}

/** Episode rows / other call sites that previously used calendar dates. */
private fun formatDate(epochMs: Long): String = formatRelativeAgo(epochMs)
