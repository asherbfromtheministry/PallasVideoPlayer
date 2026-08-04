package com.vizvag.shieldvideo.ui.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeFeedSort
import com.vizvag.shieldvideo.data.youtube.YoutubeRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeVideoItem
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.RailPlayerVisibility
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.FocusRing
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

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
    var searchOpen by remember { mutableStateOf(false) }

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
            else -> onBack()
        }
    }

    BindYoutubeStream(
        player = player,
        info = state.playing,
        onError = viewModel::reportPlaybackError,
    )

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
                    instanceHint = viewModel.pipedApiHint(),
                    loggedIn = state.loggedIn,
                    username = state.username,
                    feedSort = state.feedSort,
                    onFeedSort = viewModel::setFeedSort,
                    showSort = state.searchQuery.isBlank() && state.loggedIn,
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
                            Icon(Icons.Filled.Search, contentDescription = null, tint = Accent)
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
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = TextCream,
                            unfocusedTextColor = TextCream,
                            cursorColor = Accent,
                            focusedContainerColor = CardSurface,
                            unfocusedContainerColor = CardSurface,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                }
                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (state.loadingBrowse || state.loadingPlay) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            if (state.loadingPlay) "Resolving stream…" else "Loading…",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                    }
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
                                onPlay = viewModel::play,
                            )
                        }
                    } else {
                        if (state.loggedIn) {
                            item {
                                YoutubeShelf(
                                    title = if (state.username.isNotBlank()) {
                                        "Subscriptions · ${state.username}"
                                    } else {
                                        "Subscriptions"
                                    },
                                    items = state.sortedFeed,
                                    emptyHint = if (state.feed.isEmpty() && !state.loadingBrowse) {
                                        when {
                                            state.subscriptionCount <= 0 ->
                                                "No channels yet — Settings → YouTube → Import from Downloads"
                                            else ->
                                                "Feed empty (${state.subscriptionCount} channels) — press Refresh, or try another Piped API"
                                        }
                                    } else {
                                        "Nothing here yet."
                                    },
                                    onPlay = viewModel::play,
                                )
                            }
                        } else {
                            item {
                                Text(
                                    "Log in under Settings → YouTube (Piped account) to see your subscription feed.",
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
                                    onPlay = viewModel::play,
                                )
                            }
                        }
                    }
                    if (state.related.isNotEmpty() && state.playing != null && !state.fullscreen) {
                        item {
                            YoutubeShelf(
                                title = "Recommendations",
                                items = state.related,
                                onPlay = viewModel::play,
                            )
                        }
                    }
                }
            }
        }

        if (state.fullscreen && state.playing != null) {
            YoutubeFullscreenPlayer(
                player = player,
                title = state.playing!!.title,
                uploader = state.playing!!.uploader,
                related = state.related,
                onClose = viewModel::closePlayer,
                onPlayRelated = viewModel::play,
            )
        }
    }
}

@Composable
private fun YoutubeTopBar(
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    instanceHint: String,
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
            Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
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
                        append("via ")
                        append(instanceHint.removePrefix("https://").removePrefix("http://"))
                        if (loggedIn) {
                            append(" · ")
                            append(username.ifBlank { "signed in" })
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
            selected -> Color.Black
            focused -> TextCream
            else -> TextMuted
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    selected -> Accent
                    focused -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(
                width = if (focused && !selected) 2.dp else 0.dp,
                color = FocusRing,
                shape = RoundedCornerShape(999.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
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
    emptyHint: String = "Nothing here yet.",
) {
    Column {
        Text(
            title.uppercase(),
            color = Accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (items.isEmpty()) {
            Text(emptyHint, color = TextMuted, fontSize = 13.sp)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    YoutubeVideoCard(item = item, onClick = { onPlay(item) })
                }
            }
        }
    }
}

@Composable
private fun YoutubeVideoCard(
    item: YoutubeVideoItem,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FocusRing else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable {
                feedback.click()
                onClick()
            }
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(formatDuration(item.durationSec), color = TextCream, fontSize = 11.sp)
            }
            if (focused) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
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
    title: String,
    uploader: String,
    related: List<YoutubeVideoItem>,
    onClose: () -> Unit,
    onPlayRelated: (YoutubeVideoItem) -> Unit,
) {
    var showChrome by remember { mutableStateOf(true) }
    LaunchedEffect(title) {
        showChrome = true
        delay(4_000)
        showChrome = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        YoutubePlayerSurface(
            player = player,
            useController = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (showChrome) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    title,
                    color = TextCream,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(uploader, color = TextMuted, fontSize = 13.sp)
            }
        }
        if (related.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(280.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(12.dp),
            ) {
                Text(
                    "UP NEXT",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(related.take(12), key = { it.id }) { item ->
                        YoutubeRelatedRow(item = item, onClick = { onPlayRelated(item) })
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            IconActionButton(selected = false, onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun YoutubeRelatedRow(
    item: YoutubeVideoItem,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable {
                feedback.click()
                onClick()
            }
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF101014)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = TextCream,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.uploader, color = TextMuted, fontSize = 10.sp, maxLines = 1)
            if (item.uploadedDate.isNotBlank()) {
                Text(item.uploadedDate, color = TextMuted.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

private fun formatDuration(sec: Long): String {
    if (sec <= 0L) return "LIVE"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
