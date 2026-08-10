package com.vizvag.shieldvideo.ui.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.data.youtube.YoutubeQualityOption
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeFeedSort
import com.vizvag.shieldvideo.data.youtube.YoutubeRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeResolutionCache
import com.vizvag.shieldvideo.data.youtube.YoutubeStreamInfo
import com.vizvag.shieldvideo.data.youtube.YoutubeVideoItem
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.RailPlayerVisibility
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.notice.AppNoticeBus
import com.vizvag.shieldvideo.ui.notice.AppNoticeKind
import com.vizvag.shieldvideo.ui.notice.ForwardFlashNotice
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun YoutubeScreen(
    viewModel: YoutubeViewModel,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit = onBack,
    onSelectShare: (String) -> Unit = {},
    onOpenLiveTv: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val appSettings = remember { settingsRepository.load() }
    val railShares = rememberOrderedShares(appSettings)
    val recordingFolder = remember(appSettings) { recordingFolderForRail(appSettings) }
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val sleepState by app.sleepTimer.state.collectAsState()
    val player = rememberYoutubeExoPlayer()
    val searchFocus = remember { FocusRequester() }
    val feedFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }

    val feedFocusShelf = remember(
        state.loggedIn,
        state.sortedRecommended,
        state.sortedFeed,
        state.history,
        state.browsingChannel,
        state.searchQuery,
    ) {
        when {
            state.searchQuery.isNotBlank() -> "search"
            state.browsingChannel -> "channel"
            state.loggedIn && state.sortedRecommended.isNotEmpty() -> "recommended"
            state.loggedIn && state.sortedFeed.isNotEmpty() -> "subscriptions"
            state.history.isNotEmpty() -> "history"
            else -> null
        }
    }

    LaunchedEffect(feedFocusShelf, state.loadingBrowse, state.fullscreen, searchOpen) {
        if (state.fullscreen || searchOpen || feedFocusShelf == null || state.loadingBrowse) {
            return@LaunchedEffect
        }
        delay(200)
        runCatching { feedFocus.requestFocus() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshHome()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(player) {
        app.sleepTimer.bindPlayback(
            onStop = {
                player.pause()
                viewModel.closePlayer()
            },
        )
        onDispose { app.sleepTimer.unbindPlayback() }
    }

    LaunchedEffect(state.fullscreen) {
        onFullscreenChanged(state.fullscreen)
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(80)
            runCatching { searchFocus.requestFocus() }
        }
    }

    BackHandler {
        when {
            state.fullscreen || state.playing != null -> viewModel.closePlayer()
            searchOpen -> {
                searchOpen = false
                viewModel.setSearchQuery("")
            }
            state.browsingChannel -> viewModel.closeChannel()
            else -> onBack()
        }
    }

    BindYoutubeStream(
        player = player,
        info = state.playing,
        onError = viewModel::reportPlaybackError,
    )

    ForwardFlashNotice(
        message = state.error,
        title = "YouTube",
        onConsumed = viewModel::clearError,
    )

    val progressMsg = when {
        state.loadingPlay -> "Resolving stream…"
        state.loadingBrowse -> "Loading feed…"
        else -> null
    }
    if (progressMsg != null) {
        LaunchedEffect(progressMsg) {
            AppNoticeBus.progress(progressMsg, title = "YouTube")
        }
    } else {
        LaunchedEffect(Unit) {
            val cur = AppNoticeBus.current.value
            if (cur?.kind == AppNoticeKind.Progress && cur.title == "YouTube") {
                AppNoticeBus.dismiss(cur.id)
            }
        }
    }

    // Pause playback when leaving fullscreen / closing
    LaunchedEffect(state.playing?.id) {
        if (state.playing == null) {
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        AppWithNavRail(
            destination = RailDestination.YouTube,
            shares = railShares,
            selectedShare = appSettings.defaultShare,
            onSelectShare = onSelectShare,
            recordingFolder = recordingFolder,
            onLiveTv = onOpenLiveTv,
            onYouTube = {},
            onRadio = onOpenRadio,
            onMusic = onOpenMusic,
            onPodcasts = onOpenPodcasts,
            sleepTimerActive = sleepState.active,
            sleepTimerLabel = sleepState.label,
            onCycleSleepTimer = app.sleepTimer::cycle,
            onSettings = onOpenSettings,
            showRail = !state.fullscreen,
            railFocusEnabled = !state.fullscreen,
            players = RailPlayerVisibility.from(appSettings),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                YoutubeTopBar(
                    onRefresh = viewModel::refreshHome,
                    onSearch = { searchOpen = true },
                    loggedIn = state.loggedIn,
                    username = state.username,
                    feedSort = state.feedSort,
                    onFeedSort = viewModel::setFeedSort,
                    showSort = state.searchQuery.isBlank() && state.loggedIn && !state.browsingChannel,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                if (searchOpen) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocus),
                        singleLine = true,
                        placeholder = {
                            Text("Search YouTube or paste a URL", color = TextMuted)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = LocalScreenChrome.current.accent)
                        },
                        trailingIcon = {
                            IconActionButton(
                                selected = false,
                                onClick = {
                                    searchOpen = false
                                    viewModel.setSearchQuery("")
                                },
                            ) {
                                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LocalScreenChrome.current.accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = TextCream,
                            unfocusedTextColor = TextCream,
                            cursorColor = LocalScreenChrome.current.accent,
                            focusedContainerColor = CardSurface,
                            unfocusedContainerColor = CardSurface,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (state.searchQuery.isNotBlank()) {
                        item {
                            YoutubeShelf(
                                title = "Search results",
                                items = state.sortedSearchResults,
                                watchedIds = state.watchedIds,
                                onPlay = viewModel::play,
                                onOpenChannel = viewModel::openChannel,
                                firstItemFocusRequester = if (feedFocusShelf == "search") feedFocus else null,
                            )
                        }
                    } else if (state.browsingChannel) {
                        item {
                            YoutubeShelf(
                                title = state.channelTitle.ifBlank { "Channel" },
                                items = state.sortedChannelVideos,
                                watchedIds = state.watchedIds,
                                emptyHint = if (state.channelVideos.isEmpty() && !state.loadingBrowse) {
                                    "No videos on this channel"
                                } else {
                                    "Nothing here yet."
                                },
                                onPlay = viewModel::play,
                                onOpenChannel = viewModel::openChannel,
                                firstItemFocusRequester = if (feedFocusShelf == "channel") feedFocus else null,
                            )
                        }
                        item {
                            Text(
                                "Back returns to Recommended / Subscriptions. Hold OK on a video to jump channels.",
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                    } else {
                        if (state.loggedIn) {
                            item {
                                YoutubeShelf(
                                    title = "Recommended",
                                    items = state.sortedRecommended,
                                    watchedIds = state.watchedIds,
                                    emptyHint = if (state.recommended.isEmpty() && !state.loadingBrowse) {
                                        "No recommendations yet — watch a few videos, then Refresh"
                                    } else {
                                        "Nothing here yet."
                                    },
                                    onPlay = viewModel::play,
                                    onOpenChannel = viewModel::openChannel,
                                    firstItemFocusRequester = if (feedFocusShelf == "recommended") feedFocus else null,
                                )
                            }
                            item {
                                YoutubeShelf(
                                    title = if (state.username.isNotBlank()) {
                                        "Subscriptions · ${state.username}"
                                    } else {
                                        "Subscriptions"
                                    },
                                    items = state.sortedFeed,
                                    watchedIds = state.watchedIds,
                                    emptyHint = if (state.feed.isEmpty() && !state.loadingBrowse) {
                                        "Feed empty — press Refresh, or re-link under Settings → YouTube"
                                    } else {
                                        "Nothing here yet."
                                    },
                                    onPlay = viewModel::play,
                                    onOpenChannel = viewModel::openChannel,
                                    firstItemFocusRequester = if (feedFocusShelf == "subscriptions") feedFocus else null,
                                )
                            }
                        } else {
                            item {
                                Text(
                                    "Link YouTube under Settings → YouTube (TV code) for Recommended and Subscriptions.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        if (state.history.isNotEmpty()) {
                            item {
                                YoutubeShelf(
                                    title = "Continue watching",
                                    items = state.history,
                                    watchedIds = emptySet(),
                                    onPlay = viewModel::play,
                                    onOpenChannel = viewModel::openChannel,
                                    firstItemFocusRequester = if (feedFocusShelf == "history") feedFocus else null,
                                )
                            }
                        }
                    }
                    if (state.related.isNotEmpty() && state.playing != null && !state.fullscreen) {
                        item {
                            YoutubeShelf(
                                title = "Related",
                                items = state.related,
                                watchedIds = state.watchedIds,
                                onPlay = viewModel::play,
                                onOpenChannel = viewModel::openChannel,
                            )
                        }
                    }
                }
            }
        }

        if (state.fullscreen && state.playing != null) {
            YoutubeFullscreenPlayer(
                player = player,
                info = state.playing!!,
            )
        }
    }
}

@Composable
private fun YoutubeTopBar(
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    loggedIn: Boolean,
    username: String,
    feedSort: YoutubeFeedSort,
    onFeedSort: (YoutubeFeedSort) -> Unit,
    showSort: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = LocalScreenChrome.current.accent, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "YouTube",
                    color = TextCream,
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    buildString {
                        if (loggedIn) {
                            append(username.ifBlank { "YouTube account linked" })
                        } else {
                            append("Link account in Settings for Recommended + Subscriptions")
                        }
                    },
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconActionButton(selected = false, onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconActionButton(selected = false, onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (showSort) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SORT",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                YoutubeFeedSort.entries.forEach { sort ->
                    YoutubeSortPill(
                        label = sort.label,
                        selected = feedSort == sort,
                        onClick = { onFeedSort(sort) },
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeSortPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = when {
            selected || focused -> TextCream
            else -> TextMuted
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .glassInteract(focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable {
                feedback.click()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun YoutubeShelf(
    title: String,
    items: List<YoutubeVideoItem>,
    onPlay: (YoutubeVideoItem) -> Unit,
    onOpenChannel: (YoutubeVideoItem) -> Unit,
    watchedIds: Set<String> = emptySet(),
    emptyHint: String = "Nothing here yet.",
    firstItemFocusRequester: FocusRequester? = null,
) {
    Column {
        Text(
            title.uppercase(),
            color = LocalScreenChrome.current.accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (items.isEmpty()) {
            Text(emptyHint, color = TextMuted, fontSize = 13.sp)
        } else {
            // Row + scroll (not LazyRow): nested LazyRow inside LazyColumn breaks D-pad
            // scrolling on Shield — focus sticks on a mid/last card with nothing past it.
            val scroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEachIndexed { index, item ->
                    key(item.id) {
                        YoutubeVideoCard(
                            item = item,
                            watched = item.id in watchedIds,
                            onClick = { onPlay(item) },
                            onLongClick = { onOpenChannel(item) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YoutubeVideoCard(
    item: YoutubeVideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    watched: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis

    fun markLongPressArmed() {
        if (longPressHandled) return
        longPressHandled = true
    }

    val interaction = remember { MutableInteractionSource() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    Column(
        modifier = Modifier
            .width(220.dp)
            .glassInteract(focused = focused, selected = longPressHandled)
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (it.isFocused) {
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                    if (gained) feedback.focus()
                }
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressHandled = false
                }
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                val isMenu = event.key == Key.Menu
                when {
                    isMenu && event.type == KeyEventType.KeyUp -> {
                        feedback.click()
                        onLongClick()
                        true
                    }
                    // Only arm once — Shield OK-hold repeats KeyDown and used to reset the timer.
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                markLongPressArmed()
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressHandled) {
                            longPressHandled = false
                            feedback.click()
                            onLongClick()
                        } else {
                            feedback.click()
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                indication = null,
                interactionSource = interaction,
                onClick = {
                    feedback.click()
                    onClick()
                },
                onLongClick = {
                    feedback.click()
                    onLongClick()
                },
            )
            .focusable(interactionSource = interaction)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF101014)),
        ) {
            if (item.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (watched) {
                Text(
                    text = "WATCHED",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            item.resolutionLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = label,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.50f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            val durationLabel = formatDuration(item.durationSec)
            if (durationLabel.isNotBlank()) {
                Text(
                    text = durationLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.50f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (focused) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = LocalScreenChrome.current.accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text(
            item.title,
            color = TextCream,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        Text(
            item.uploader.takeIf { it.isNotBlank() } ?: "Unknown channel",
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            item.uploadedDate
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let { add(it) }
            YoutubeRepository.formatViewCount(item.views).takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                color = TextMuted.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YoutubeFullscreenPlayer(
    player: androidx.media3.exoplayer.ExoPlayer,
    info: YoutubeStreamInfo,
) {
    var showChrome by remember { mutableStateOf(true) }
    var chromeEpoch by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var videoCodec by remember { mutableStateOf<String?>(null) }
    var audioCodec by remember { mutableStateOf<String?>(null) }
    var audioChannels by remember { mutableIntStateOf(0) }
    var qualityPickerVisible by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val playerFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }

    fun refreshFormats() {
        val vf = player.videoFormat
        val af = player.audioFormat
        videoW = vf?.width?.takeIf { it > 0 } ?: 0
        videoH = vf?.height?.takeIf { it > 0 } ?: 0
        videoCodec = formatCodecLabel(vf?.codecs ?: vf?.sampleMimeType)
        audioCodec = formatCodecLabel(af?.codecs ?: af?.sampleMimeType)
        audioChannels = af?.channelCount?.takeIf { it > 0 } ?: 0
    }

    LaunchedEffect(info.id) {
        delay(60)
        runCatching { playerFocus.requestFocus() }
        refreshFormats()
    }

    DisposableEffect(player) {
        refreshFormats()
        isPlaying = player.isPlaying
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onEvents(
                p: androidx.media3.common.Player,
                events: androidx.media3.common.Player.Events,
            ) {
                positionMs = p.currentPosition.coerceAtLeast(0L)
                durationMs = p.duration.takeIf { it > 0L } ?: 0L
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) refreshFormats()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                refreshFormats()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0) videoW = videoSize.width
                if (videoSize.height > 0) videoH = videoSize.height
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            isPlaying = player.isPlaying
            delay(400)
        }
    }

    LaunchedEffect(info.id, chromeEpoch, isPlaying) {
        showChrome = true
        if (isPlaying) {
            qualityPickerVisible = false
            delay(3_000)
            showChrome = false
        }
        // Stay visible while paused.
    }

    LaunchedEffect(isPlaying, qualityPickerVisible) {
        if (!isPlaying && !qualityPickerVisible) {
            delay(120)
            runCatching { qualityFocus.requestFocus() }
        }
    }

    fun bumpChrome() {
        chromeEpoch += 1
    }

    fun togglePlay() {
        bumpChrome()
        feedback.click()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        bumpChrome()
        feedback.click()
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val dur = player.duration
        player.seekTo(if (dur > 0L) target.coerceAtMost(dur) else target)
    }

    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val currentResLabel = when {
        videoH >= 2000 || videoW >= 3800 -> "4K"
        videoH >= 1400 || videoW >= 2500 -> "1440p"
        videoH >= 1040 || videoW >= 1900 -> "1080p"
        videoH >= 700 || videoW >= 1200 -> "720p"
        videoH > 0 -> "${videoH}p"
        else -> null
    }
    val maxResLabel = YoutubeResolutionCache.labelForHeight(info.maxHeight)
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val canForceBetter = info.qualities.any { it.height > videoH + 16 } ||
        (info.maxHeight > 0 && videoH > 0 && info.maxHeight > videoH + 16)
    val forceLabel = maxResLabel ?: info.qualities.maxByOrNull { it.height }?.label

    fun forceMaxQuality() {
        bumpChrome()
        feedback.click()
        val switched = app.youtubePlayback.forceMaxQuality()
        if (!switched) {
            app.youtubePlayback.forceHighestVideoQuality()
        }
        // Refresh after ExoPlayer swaps tracks.
        refreshFormats()
    }

    fun selectQuality(option: com.vizvag.shieldvideo.data.youtube.YoutubeQualityOption) {
        bumpChrome()
        feedback.click()
        app.youtubePlayback.selectQuality(option)
        refreshFormats()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        togglePlay()
                        true
                    }
                    Key.DirectionUp -> {
                        if (!isPlaying && (info.qualities.isNotEmpty() || canForceBetter)) {
                            qualityPickerVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        seekBy(-10_000L)
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(10_000L)
                        true
                    }
                    Key.Menu -> {
                        if (!isPlaying && (info.qualities.isNotEmpty() || canForceBetter)) {
                            qualityPickerVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        YoutubePlayerSurface(
            player = player,
            useController = false,
            modifier = Modifier.fillMaxSize(),
        )

        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f),
                        ),
                    ),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showChrome || !isPlaying,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .zIndex(2f),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 6 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 8 },
        ) {
            if (!isPlaying) {
                YoutubePauseInfoOverlay(
                    title = info.title,
                    uploader = info.uploader,
                    videoWidth = videoW,
                    videoHeight = videoH,
                    currentRes = currentResLabel,
                    maxRes = maxResLabel,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    audioLayout = audioLayoutLabel(audioChannels),
                    livestream = info.livestream,
                    qualityLabel = currentResLabel ?: maxResLabel ?: "Quality",
                    hasQualityOptions = info.qualities.isNotEmpty() || canForceBetter,
                    positionLabel = formatClock(positionMs),
                    durationLabel = if (info.livestream && durationMs <= 0L) {
                        "LIVE"
                    } else {
                        formatClock(durationMs)
                    },
                    progress = progress,
                    qualityFocusRequester = qualityFocus,
                    onOpenQualityPicker = { qualityPickerVisible = true },
                    modifier = Modifier
                        .padding(start = 22.dp, bottom = 18.dp, end = 22.dp)
                        .widthIn(max = 520.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            ),
                        )
                        .padding(horizontal = 36.dp, vertical = 22.dp),
                ) {
                    Text(
                        info.title,
                        color = TextCream,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        fontFamily = PallasFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    YoutubeProgressBar(progress = progress)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatClock(positionMs), color = TextMuted, fontSize = 12.sp)
                        Text(
                            if (info.livestream && durationMs <= 0L) "LIVE" else formatClock(durationMs),
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (qualityPickerVisible) {
            YoutubeQualityPickerDialog(
                qualities = info.qualities,
                currentHeight = videoH,
                canForceMax = canForceBetter,
                maxLabel = forceLabel,
                onSelect = ::selectQuality,
                onForceMax = {
                    forceMaxQuality()
                    qualityPickerVisible = false
                },
                onDismiss = {
                    qualityPickerVisible = false
                    if (!isPlaying) {
                        runCatching { qualityFocus.requestFocus() }
                    }
                },
            )
        }
    }
}

@Composable
private fun YoutubeProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(PallasShapes.track))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(PallasShapes.track))
                .background(
                    Brush.horizontalGradient(
                        listOf(LocalScreenChrome.current.accent, LocalScreenChrome.current.accentSecondary),
                    ),
                ),
        )
    }
}

@Composable
private fun YoutubePauseInfoOverlay(
    title: String,
    uploader: String,
    videoWidth: Int,
    videoHeight: Int,
    currentRes: String?,
    maxRes: String?,
    videoCodec: String?,
    audioCodec: String?,
    audioLayout: String?,
    livestream: Boolean,
    qualityLabel: String,
    hasQualityOptions: Boolean,
    positionLabel: String,
    durationLabel: String,
    progress: Float,
    qualityFocusRequester: FocusRequester,
    onOpenQualityPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(12.dp)
    val videoDetail = buildList {
        when {
            videoWidth > 0 && videoHeight > 0 -> add("${videoWidth}×${videoHeight}")
            currentRes != null -> add(currentRes)
            videoHeight > 0 -> add("${videoHeight}p")
        }
        videoCodec?.let { add(it) }
        maxRes?.takeIf { it != currentRes && videoHeight > 0 && infoMaxBelowCurrent(maxRes, videoHeight) }
            ?.let { add("max $it") }
    }.joinToString(" · ")

    val audioDetail = buildList {
        audioCodec?.let { add(it) }
        audioLayout?.let { add(it) }
    }.joinToString(" · ")

    Column(
        modifier = modifier
            .shadow(12.dp, panelShape, clip = false)
            .clip(panelShape)
            .background(Color(0xE6121218))
            .border(1.dp, Color.White.copy(alpha = 0.10f), panelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "PAUSED",
                color = LocalScreenChrome.current.accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                fontFamily = PallasFontFamily,
            )
            if (livestream) {
                Text(
                    "LIVE",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LocalScreenChrome.current.accentWarm.copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(
                title,
                color = TextCream,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (uploader.isNotBlank()) {
            Text(
                uploader,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (videoDetail.isNotBlank()) {
                    StreamInfoLine(label = "Video", value = videoDetail)
                }
                if (audioDetail.isNotBlank()) {
                    StreamInfoLine(label = "Audio", value = audioDetail)
                }
            }
            if (hasQualityOptions) {
                QualityPickerChip(
                    label = qualityLabel,
                    focusRequester = qualityFocusRequester,
                    onClick = onOpenQualityPicker,
                )
            }
        }

        YoutubeProgressBar(progress = progress, modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(positionLabel, color = TextCream.copy(alpha = 0.85f), fontSize = 10.sp)
            Text(durationLabel, color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StreamInfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            color = TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.width(42.dp),
        )
        Text(
            value,
            color = TextCream.copy(alpha = 0.92f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QualityPickerChip(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .focusRequester(focusRequester)
            .glassInteract(focused = focused, selected = focused, scaleOnFocus = true, corner = 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (focused) Color.Black else TextCream,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "▾",
            color = if (focused) Color.Black.copy(alpha = 0.7f) else TextMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun YoutubeQualityPickerDialog(
    qualities: List<YoutubeQualityOption>,
    currentHeight: Int,
    canForceMax: Boolean,
    maxLabel: String?,
    onSelect: (YoutubeQualityOption) -> Unit,
    onForceMax: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val options = qualities.distinctBy { it.height }.sortedByDescending { it.height }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardSurface)
                    .border(1.dp, LocalScreenChrome.current.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Quality",
                        color = TextCream,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = LocalScreenChrome.current.accent)
                    }
                }
                Text(
                    "Select a resolution — playback continues from the same position.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canForceMax && maxLabel != null) {
                        item {
                            QualityPickerRow(
                                label = "Max ($maxLabel)",
                                selected = false,
                                onClick = onForceMax,
                            )
                        }
                    }
                    if (options.isEmpty()) {
                        item {
                            Text(
                                "No manual quality rungs — stream is adaptive only.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    } else {
                        items(options, key = { it.height }) { option ->
                            val selected =
                                currentHeight > 0 &&
                                    kotlin.math.abs(option.height - currentHeight) <= 16
                            QualityPickerRow(
                                label = option.label,
                                selected = selected,
                                onClick = { onSelect(option) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityPickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = selected || focused, scaleOnFocus = true, corner = 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected || focused) Color.Black else TextCream,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                "Playing",
                color = if (focused) Color.Black.copy(alpha = 0.65f) else LocalScreenChrome.current.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun infoMaxBelowCurrent(maxRes: String, currentHeight: Int): Boolean {
    val maxH = when {
        maxRes.equals("4K", ignoreCase = true) -> 2160
        maxRes.endsWith("p", ignoreCase = true) -> maxRes.dropLast(1).toIntOrNull() ?: return false
        else -> return false
    }
    return maxH > currentHeight + 16
}

private fun audioLayoutLabel(channels: Int): String? = when (channels) {
    0 -> null
    1 -> "Mono"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

private fun formatCodecLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val short = value.substringBefore(';').substringAfter('/').ifBlank { value }
    return when {
        short.contains("avc1", ignoreCase = true) || short.equals("avc", ignoreCase = true) -> "AVC"
        short.contains("av01", ignoreCase = true) || short.contains("av1", ignoreCase = true) -> "AV1"
        short.contains("vp9", ignoreCase = true) -> "VP9"
        short.contains("hev1", ignoreCase = true) || short.contains("hvc1", ignoreCase = true) ||
            short.contains("hevc", ignoreCase = true) -> "HEVC"
        short.contains("mp4a", ignoreCase = true) -> "AAC"
        short.contains("opus", ignoreCase = true) -> "Opus"
        short.contains("ec-3", ignoreCase = true) || short.contains("eac3", ignoreCase = true) -> "E-AC-3"
        short.contains("ac-3", ignoreCase = true) || short.contains("ac3", ignoreCase = true) -> "AC-3"
        else -> short.take(18)
    }
}

private fun formatDuration(sec: Long): String {
    if (sec < 0L) return "LIVE"
    if (sec == 0L) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
