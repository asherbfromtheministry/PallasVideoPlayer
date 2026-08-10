package com.vizvag.shieldvideo.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import com.vizvag.shieldvideo.ui.components.LocalForcedLandscapeRotated
import com.vizvag.shieldvideo.ui.components.touchFriendlyVerticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberDvr
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.FeatureFlags
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PosterShape = RoundedCornerShape(PallasShapes.art)
private val PosterArtBg = Color(0xFF101014)
private val RailWidth = 76.dp

/** Which top-level app section the left rail highlights. */
enum class RailDestination {
    Browser,
    LiveTv,
    YouTube,
    Radio,
    Music,
    Podcasts,
}

/** Which player entries appear on the left rail (Settings → Display). */
data class RailPlayerVisibility(
    val liveTv: Boolean = true,
    val youtube: Boolean = false,
    val radio: Boolean = true,
    val music: Boolean = true,
    val podcasts: Boolean = true,
) {
    companion object {
        fun from(settings: AppSettings) = RailPlayerVisibility(
            liveTv = settings.homeShowLiveTv,
            youtube = settings.homeShowYouTube && FeatureFlags.youtube,
            radio = settings.homeShowRadio,
            music = settings.homeShowMusic,
            podcasts = settings.homeShowPodcasts,
        )
    }
}

/**
 * Option A left rail — icon stacked over label (mockup), lime when selected.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowserNavRail(
    shares: List<String>,
    selectedShare: String,
    onSelectShare: (String) -> Unit,
    recordingFolder: String?,
    onLiveTv: () -> Unit,
    onYouTube: () -> Unit,
    onRadio: () -> Unit,
    onMusic: () -> Unit,
    onPodcasts: () -> Unit = {},
    sleepTimerActive: Boolean,
    sleepTimerLabel: String?,
    onCycleSleepTimer: () -> Unit,
    onCustomSleepTimer: () -> Unit = {},
    onSettings: () -> Unit,
    destination: RailDestination = RailDestination.Browser,
    /** When false, rail icons stay visible but are removed from the D-pad focus tree. */
    focusEnabled: Boolean = true,
    players: RailPlayerVisibility = RailPlayerVisibility(),
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val onBrowser = destination == RailDestination.Browser
    // When focus enters the rail from content, land on the active section (not a random icon).
    val selectedFocus = remember { FocusRequester() }
    val dvrSelected = onBrowser &&
        !recordingFolder.isNullOrBlank() &&
        recordingFolder.equals(selectedShare, ignoreCase = true) &&
        shares.none { it.equals(recordingFolder, ignoreCase = true) }
    val shareSelected = onBrowser &&
        shares.any { it.equals(selectedShare, ignoreCase = true) }
    val showDvr = !recordingFolder.isNullOrBlank() &&
        shares.none { it.equals(recordingFolder, ignoreCase = true) }
    val showLiveTv = players.liveTv
    val showYouTube = players.youtube
    val showRadio = players.radio
    val showMusic = players.music
    val showPodcasts = players.podcasts
    val playerCount =
        (if (showLiveTv) 1 else 0) +
            (if (showYouTube) 1 else 0) +
            (if (showRadio) 1 else 0) +
            (if (showMusic) 1 else 0) +
            (if (showPodcasts) 1 else 0)
    // Focusable rows: shares + optional DVR + players + Sleep + Settings
    val focusableCount = shares.size + (if (showDvr) 1 else 0) + playerCount + 2
    var focusIndex = 0
    fun nextEdge(): Pair<Boolean, Boolean> {
        val i = focusIndex
        focusIndex++
        return (i == 0) to (i == focusableCount - 1)
    }

    val rotated = LocalForcedLandscapeRotated.current
    BoxWithConstraints(modifier = modifier.width(RailWidth).fillMaxHeight()) {
        // Pack icons vertically — Shields need every pixel when many shares + players are enabled.
        val compact = maxHeight < 560.dp
        val itemGap = 0.dp
        val sectionGap = if (compact) 1.dp else 2.dp
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .focusProperties {
                        // Entering from the content pane → selected destination.
                        // (Avoid the old railHasFocus hop — it stole focus while scrolling up.)
                        enter = { selectedFocus }
                    }
                    .clipToBounds()
                    .touchFriendlyVerticalScroll(scroll, rotated)
                    .padding(
                        top = if (compact) 4.dp else 8.dp,
                        bottom = if (compact) 4.dp else 6.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(itemGap),
            ) {
                shares.forEachIndexed { index, share ->
                    val label = NasPaths.labelFor(share)
                    val selected = onBrowser && share.equals(selectedShare, ignoreCase = true)
                    val preferred = selected ||
                        (onBrowser && !shareSelected && !dvrSelected && index == 0)
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = label,
                        selected = selected,
                        onClick = { onSelectShare(share) },
                        icon = shareIcon(share),
                        focusRequester = if (preferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                if (showDvr) {
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "DVR",
                        selected = dvrSelected,
                        onClick = { onSelectShare(recordingFolder!!) },
                        icon = Icons.Filled.FiberDvr,
                        focusRequester = if (dvrSelected) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }

                Spacer(Modifier.height(sectionGap))
                Box(
                    Modifier
                        .width(28.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )

                if (showLiveTv) {
                    val livePreferred = destination == RailDestination.LiveTv
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "Live TV",
                        selected = livePreferred,
                        onClick = onLiveTv,
                        icon = Icons.Filled.LiveTv,
                        focusRequester = if (livePreferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                if (showYouTube) {
                    val youtubePreferred = destination == RailDestination.YouTube
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "YouTube",
                        selected = youtubePreferred,
                        onClick = onYouTube,
                        icon = Icons.Filled.VideoLibrary,
                        focusRequester = if (youtubePreferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                if (showRadio) {
                    val radioPreferred = destination == RailDestination.Radio
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "Radio",
                        selected = radioPreferred,
                        onClick = onRadio,
                        icon = Icons.Filled.Radio,
                        focusRequester = if (radioPreferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                if (showMusic) {
                    val musicPreferred = destination == RailDestination.Music
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "Music",
                        selected = musicPreferred,
                        onClick = onMusic,
                        icon = Icons.Filled.LibraryMusic,
                        focusRequester = if (musicPreferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                if (showPodcasts) {
                    val podcastsPreferred = destination == RailDestination.Podcasts
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "Podcasts",
                        selected = podcastsPreferred,
                        onClick = onPodcasts,
                        icon = Icons.Filled.Podcasts,
                        focusRequester = if (podcastsPreferred) selectedFocus else null,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }

                Spacer(Modifier.height(sectionGap))
                Box(
                    Modifier
                        .width(28.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )

                run {
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = sleepTimerLabel?.takeIf { it.isNotBlank() } ?: "Sleep",
                        selected = sleepTimerActive,
                        onClick = onCycleSleepTimer,
                        onLongClick = onCustomSleepTimer,
                        icon = Icons.Filled.Timer,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
                run {
                    val (isFirst, isLast) = nextEdge()
                    RailItem(
                        label = "Settings",
                        selected = false,
                        onClick = onSettings,
                        icon = Icons.Filled.Settings,
                        focusEnabled = focusEnabled,
                        blockFocusUp = isFirst,
                        blockFocusDown = isLast,
                        compact = compact,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.06f)),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector,
    focusRequester: FocusRequester? = null,
    focusEnabled: Boolean = true,
    blockFocusUp: Boolean = false,
    blockFocusDown: Boolean = false,
    compact: Boolean = false,
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val tint = when {
        selected -> Color.White
        focused -> chrome.accent
        else -> Color.White.copy(alpha = 0.78f)
    }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val iconSize = if (compact) 16.dp else 19.dp
    val labelSize = if (compact) 7.sp else 8.sp
    val vPad = if (compact) 1.dp else 2.dp

    fun fireLongPress() {
        if (onLongClick == null || longPressHandled) return
        longPressHandled = true
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .glassInteract(
                focused = focused,
                selected = selected || longPressHandled,
                idleSurface = Color.Transparent,
            )
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (it.isFocused) {
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                    if (gained) feedback.focus()
                }
            }
            .focusProperties {
                canFocus = focusEnabled
                if (blockFocusUp) up = FocusRequester.Cancel
                if (blockFocusDown) down = FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                val isMenu = event.key == Key.Menu
                when {
                    isMenu && event.type == KeyEventType.KeyUp && onLongClick != null -> {
                        feedback.click()
                        onLongClick()
                        true
                    }
                    isSelect && onLongClick != null && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                fireLongPress()
                            }
                        }
                        true
                    }
                    isSelect && onLongClick != null && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressHandled) {
                            longPressHandled = false
                            feedback.click()
                            onLongClick()
                            true
                        } else {
                            feedback.click()
                            onClick()
                            true
                        }
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = focusEnabled,
                role = Role.Button,
                onClick = {
                    if (onLongClick == null) {
                        feedback.click()
                        onClick()
                    }
                },
                onLongClick = onLongClick?.let { handler ->
                    {
                        feedback.click()
                        handler()
                    }
                },
            )
            .focusable(enabled = focusEnabled, interactionSource = interaction)
            .padding(vertical = vPad),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = label,
            color = tint,
            fontSize = labelSize,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (compact) 0.dp else 1.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * Mockup A hero — near full-bleed cinematic billboard, huge title, solid lime Play.
 */
@Composable
fun HeroBillboard(
    item: MediaCardItem,
    onPlay: () -> Unit,
    onOpenFolder: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    val isFolder = item.entry.isDirectory
    val art = item.fanartUrl ?: item.posterUrl
    val meta = listOf(item.line1, item.line2, item.line3)
        .filter { it.isNotBlank() && !it.equals("Folder", ignoreCase = true) }
        .joinToString("  ·  ")
    val onActivate = {
        feedback.click()
        if (isFolder) onOpenFolder() else onPlay()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(380.dp)
            .glassInteract(
                focused = focused,
                selected = false,
                idleSurface = Color.Transparent,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .clickable(role = Role.Button, onClick = onActivate),
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val folderTone = colorFromTitle(item.displayTitle)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(folderTone.copy(alpha = 0.55f), Color(0xFF0A0A0C)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        isFolder -> Icons.Filled.Folder
                        NasPaths.isArchiveFile(item.entry.name) -> Icons.Filled.FolderZip
                        else -> Icons.Filled.Movie
                    },
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(72.dp),
                )
            }
        }

        // Left cinematic scrim — mockup style
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.38f to Color.Black.copy(alpha = 0.72f),
                        0.72f to Color.Black.copy(alpha = 0.2f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.65f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.58f)
                .padding(start = 36.dp, end = 20.dp, top = 36.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = cleanDisplayTitle(item.displayTitle),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 48.sp,
                letterSpacing = (-0.8).sp,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                HeroPlayButton(
                    label = if (isFolder) "Open" else "Play",
                    onClick = onActivate,
                )
                if (!isFolder && item.resumePositionMs != null && !item.watched) {
                    Text(
                        text = "Resume  ${formatResumeMs(item.resumePositionMs)}",
                        color = chrome.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPlayButton(label: String, onClick: () -> Unit) {
    val chrome = LocalScreenChrome.current
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .glassInteract(focused = focused, selected = true)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterShelfCard(
    item: MediaCardItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }
    val contentAlpha = if (item.watched) 0.42f else 1f
    val hasResume = item.resumePositionMs != null && !item.watched
    val art = item.posterUrl ?: item.fanartUrl
    val episodeLine = when {
        item.line1.equals("Category", ignoreCase = true) -> "Category"
        else -> BrowserViewModel.formatEpisodeLabel(item.season, item.episode, item.episodeTitle)
            ?: item.line1.takeIf { it.isNotBlank() && !it.equals("Folder", ignoreCase = true) }
            ?: item.line2.takeIf {
                item.entry.isDirectory &&
                    it.isNotBlank() &&
                    !it.equals("Folder", ignoreCase = true)
            }
    }

    fun fireLongPress() {
        if (onLongClick == null || longPressHandled) return
        longPressHandled = true
    }

    Column(
        modifier = modifier
            .width(152.dp)
            .glassInteract(
                focused = focused,
                selected = longPressHandled,
                idleSurface = Color.Transparent,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                val isMenu = event.key == Key.Menu
                when {
                    isMenu && event.type == KeyEventType.KeyUp && onLongClick != null -> {
                        onLongClick()
                        true
                    }
                    isSelect && onLongClick != null && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                fireLongPress()
                            }
                        }
                        true
                    }
                    isSelect && onLongClick != null && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressHandled) {
                            longPressHandled = false
                            feedback.click()
                            onLongClick()
                            true
                        } else {
                            feedback.click()
                            onClick()
                            true
                        }
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    feedback.click()
                    onClick()
                },
                onLongClick = onLongClick,
            )
            .focusable(interactionSource = interaction),
    ) {
        // Poster art frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(228.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(PosterShape)
                    .background(PosterArtBg),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = item.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(contentAlpha),
                    )
                } else if (item.entry.isDirectory && item.line1.equals("Category", ignoreCase = true)) {
                    CategoryFolderArt(
                        title = item.displayTitle,
                        contentAlpha = contentAlpha,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val tone = colorFromTitle(item.displayTitle)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(tone.copy(alpha = 0.65f), Color(0xFF0C0C10)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                item.entry.isDirectory -> Icons.Filled.Folder
                                NasPaths.isArchiveFile(item.entry.name) -> Icons.Filled.FolderZip
                                else -> Icons.Filled.Movie
                            },
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f * contentAlpha),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }

                if (hasResume) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.resumeProgress ?: 0.35f)
                                .fillMaxHeight()
                                .background(chrome.accent),
                        )
                    }
                }
            }
        }

        Text(
            text = cleanDisplayTitle(item.displayTitle),
            color = Color.White.copy(alpha = contentAlpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        if (!episodeLine.isNullOrBlank()) {
            Text(
                text = episodeLine,
                color = chrome.muted.copy(alpha = contentAlpha),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
fun ShelfRow(
    title: String,
    items: List<MediaCardItem>,
    onClick: (MediaCardItem) -> Unit,
    onLongClick: (MediaCardItem) -> Unit,
    firstFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.entry.path }) { index, item ->
                PosterShelfCard(
                    item = item,
                    focusRequester = if (index == 0) firstFocus else null,
                    onClick = { onClick(item) },
                    onLongClick = {
                        if (!item.entry.isDirectory) onLongClick(item)
                    },
                )
            }
        }
    }
}

/** Icon for a NAS share name — used by the rail and home landing. */
fun nasShareIcon(share: String): ImageVector = shareIcon(share)

private fun shareIcon(share: String): ImageVector {
    val key = share.trim('/').substringBefore('/').lowercase()
    return when (key) {
        "download", "downloads" -> Icons.Filled.Download
        "video", "videos", "movies" -> Icons.Filled.Movie
        "docs", "documents" -> Icons.AutoMirrored.Filled.InsertDriveFile
        "photo", "photos", "pictures" -> Icons.Filled.Folder
        else -> Icons.Filled.Folder
    }
}

/** Strip release junk so hero titles don't look like torrents (keep S01 / season labels) */
private fun cleanDisplayTitle(raw: String): String {
    var t = raw
        .replace('.', ' ')
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
    t = t.replace(
        Regex(
            """(?i)\b(S\d{1,2}\s?E\d{1,3}|COMPLETE|PACK|1080p|720p|2160p|4K|UHD|HDR10?\+?|BluRay|WEB[- ]?DL|WEBRip|WEB|AMZN|NF|x264|x265|HEVC|PROPER|REPACK)\b""",
        ),
        "",
    )
    t = t.replace(Regex("""(?i)-[a-z0-9]{2,10}$"""), "")
    return t.replace(Regex("""\s+"""), " ").trim().ifBlank { raw }
}

private fun formatResumeMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Stable tonal fill for folders / files without poster art */
private fun colorFromTitle(title: String): Color {
    val hues = listOf(
        Color(0xFF2A3A4A),
        Color(0xFF3A2A3A),
        Color(0xFF2A3A2A),
        Color(0xFF3A3A2A),
        Color(0xFF2A2A4A),
        Color(0xFF3A2A2A),
    )
    val idx = (title.hashCode().and(0x7FFFFFFF)) % hues.size
    return hues[idx]
}

/**
 * Generic category tiles for top-level /video folders (Movies, TV, Kids, …).
 * No Trakt — stylised gradient + icon keyed by folder name.
 */
@Composable
private fun CategoryFolderArt(
    title: String,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val key = title.lowercase()
    val (icon, top, bottom) = when {
        key.contains("movie") || key.contains("film") || key.contains("cinema") ->
            Triple(Icons.Filled.Movie, Color(0xFF1B3A4A), Color(0xFF0A1014))
        key.contains("tv") || key.contains("series") || key.contains("show") ||
            key.contains("tele") ->
            Triple(Icons.Filled.LiveTv, Color(0xFF2A1F4A), Color(0xFF0C0A14))
        key.contains("kid") || key.contains("child") || key.contains("family") ||
            key.contains("anim") ->
            Triple(Icons.Filled.Movie, Color(0xFF1F3A2A), Color(0xFF0A140C))
        key.contains("doc") || key.contains("factual") ->
            Triple(Icons.AutoMirrored.Filled.InsertDriveFile, Color(0xFF3A3A1F), Color(0xFF14140A))
        key.contains("sport") ->
            Triple(Icons.Filled.LiveTv, Color(0xFF1F3A1F), Color(0xFF0A140A))
        key.contains("music") || key.contains("concert") ->
            Triple(Icons.Filled.LibraryMusic, Color(0xFF3A1F3A), Color(0xFF140A14))
        key.contains("record") || key.contains("dvr") || key.contains("iptv") ->
            Triple(Icons.Filled.FiberDvr, Color(0xFF3A2A1F), Color(0xFF14100A))
        else ->
            Triple(Icons.Filled.Folder, colorFromTitle(title), Color(0xFF0C0C10))
    }

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(top.copy(alpha = 0.95f), bottom)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f * contentAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.88f * contentAlpha),
            modifier = Modifier.size(56.dp),
        )
    }
}
