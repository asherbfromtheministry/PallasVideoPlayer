package com.vizvag.shieldvideo.ui.browser
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.vizvag.shieldvideo.data.nas.NasPaths
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.staggeredEntrance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
private val ListPaneWidth = 340.dp
private val ListPaneBg = Color(0xE00E0E12)
private val RowHighlight = Color.White.copy(alpha = 0.10f)
/**
 * Option C — Split Command: left list of folder contents, right cinematic preview.
 */
@Composable
fun SplitFolderBrowser(
    title: String,
    itemCount: Int,
    items: List<MediaCardItem>,
    focused: MediaCardItem?,
    onFocusedChange: (MediaCardItem) -> Unit,
    onClick: (MediaCardItem) -> Unit,
    onLongClick: (MediaCardItem) -> Unit,
    onPlay: (MediaCardItem) -> Unit,
    onSearch: () -> Unit = {},
    listFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        SplitListPane(
            title = title,
            itemCount = itemCount,
            items = items,
            focusedPath = focused?.entry?.path,
            onFocusedChange = onFocusedChange,
            onClick = onClick,
            onLongClick = onLongClick,
            onSearch = onSearch,
            listFocusRequester = listFocusRequester,
            modifier = Modifier
                .width(ListPaneWidth)
                .fillMaxHeight(),
        )
        SplitPreviewPane(
            focused = focused,
            onPlay = onPlay,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}
@Composable
private fun SplitListPane(
    title: String,
    itemCount: Int,
    items: List<MediaCardItem>,
    focusedPath: String?,
    onFocusedChange: (MediaCardItem) -> Unit,
    onClick: (MediaCardItem) -> Unit,
    onLongClick: (MediaCardItem) -> Unit,
    onSearch: () -> Unit,
    listFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalScreenChrome.current
    Column(
        modifier = modifier
            .background(ListPaneBg)
            .padding(top = 20.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = cleanSplitTitle(title),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    text = "$itemCount ITEMS",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            IconActionButton(selected = false, onClick = onSearch) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search videos",
                    tint = chrome.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.entry.path }) { index, item ->
                val isFocusedRow = when {
                    focusedPath != null -> item.entry.path == focusedPath
                    else -> index == 0
                }
                SplitListRow(
                    item = item,
                    selected = item.entry.path == focusedPath,
                    onFocused = { onFocusedChange(item) },
                    onClick = { onClick(item) },
                    onLongClick = {
                        onLongClick(item)
                    },
                    focusRequester = if (isFocusedRow) listFocusRequester else null,
                    index = index,
                )
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitListRow(
    item: MediaCardItem,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    focusRequester: FocusRequester?,
    index: Int,
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }
    val isActive = focused || selected
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        animationSpec = Motion.focusSpring(),
        label = "splitRowScale",
    )
    val episodeLabel = BrowserViewModel.formatEpisodeLabel(
        item.season,
        item.episode,
        item.episodeTitle,
    )
    val rowTitle = episodeLabel ?: cleanSplitTitle(item.displayTitle)
    val rowIcon = when {
        focused || selected -> Icons.Filled.PlayArrow
        item.entry.isDirectory -> Icons.Filled.Folder
        NasPaths.isArchiveFile(item.entry.name) -> Icons.Filled.FolderZip
        else -> Icons.Filled.Movie
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(visible = true, index = index)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (it.isFocused) onFocused()
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
                                if (!longPressHandled) longPressHandled = true
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
            .focusable(interactionSource = interaction)
            .background(if (isActive) RowHighlight else Color.Transparent)
            .then(
                if (focused) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                } else {
                    Modifier
                },
            )
            .padding(end = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(48.dp)
                .background(if (isActive) chrome.accent else Color.Transparent),
        )
        Icon(
            imageVector = rowIcon,
            contentDescription = null,
            tint = if (isActive) chrome.accent else Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .size(22.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = rowTitle,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun SplitPreviewPane(
    focused: MediaCardItem?,
    onPlay: (MediaCardItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    Box(modifier = modifier.background(Color(0xFF070708))) {
        if (focused == null) {
            Text(
                text = "Focus an item",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val art = focused.fanartUrl ?: focused.posterUrl
            val isFolder = focused.entry.isDirectory
            val folderNameLabel = if (isFolder) {
                cleanSplitTitle(focused.entry.name)
            } else {
                null
            }
            val titleText = cleanSplitTitle(focused.displayTitle)
            val episodeLabel = BrowserViewModel.formatEpisodeLabel(
                focused.season,
                focused.episode,
                focused.episodeTitle,
            ) ?: focused.line1.takeIf {
                it.isNotBlank() &&
                    !it.equals("Folder", ignoreCase = true) &&
                    !it.equals("Category", ignoreCase = true) &&
                    !it.equals("Archive", ignoreCase = true)
            }
            val detailMeta = buildList {
                if (folderNameLabel != null &&
                    !folderNameLabel.equals(titleText, ignoreCase = true)
                ) {
                    add(folderNameLabel)
                }
                focused.line2.split("  ·  ", " · ")
                    .map { it.trim() }
                    .filter { bit ->
                        bit.isNotBlank() &&
                            !bit.equals("Watched", ignoreCase = true) &&
                            !bit.equals("Folder", ignoreCase = true) &&
                            !bit.matches(Regex("""(?i)S\d{1,2}E\d{1,3}"""))
                    }
                    .forEach { add(it) }
            }.distinct().joinToString("  ·  ")
            val resumeMs = focused.resumePositionMs
                ?.takeIf { !isFolder && !focused.watched }
            val artAlpha = if (!isFolder && focused.watched) 0.72f else 1f
            // Key by path so Coil cannot keep the previous folder's fanart painted.
            key(focused.entry.path) {
                if (art != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(art)
                            .crossfade(false)
                            .build(),
                        contentDescription = focused.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = artAlpha },
                    )
                } else {
                    val tone = colorFromTitleSplit(focused.displayTitle)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = artAlpha }
                            .background(
                                Brush.linearGradient(
                                    listOf(tone.copy(alpha = 0.55f), Color(0xFF0A0A0C)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                isFolder -> Icons.Filled.Folder
                                NasPaths.isArchiveFile(focused.entry.name) -> Icons.Filled.FolderZip
                                else -> Icons.Filled.Movie
                            },
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.82f),
                            0.55f to Color.Black.copy(alpha = 0.55f),
                            0.82f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Black.copy(alpha = 0.12f),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.2f),
                            0.4f to Color.Transparent,
                            0.7f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 36.dp, bottom = 40.dp, top = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = titleText,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 44.sp,
                    letterSpacing = (-0.6).sp,
                )
                if (!episodeLabel.isNullOrBlank()) {
                    Text(
                        text = episodeLabel,
                        color = chrome.accent.copy(alpha = 0.95f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 26.sp,
                    )
                }
                if (detailMeta.isNotBlank()) {
                    Text(
                        text = detailMeta,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = focused.entry.name,
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                val badges = buildList {
                    if (!isFolder && focused.watched) add("WATCHED")
                    if (focused.metadataCleared) add("NO META")
                    // Runtime sits next to the resume clock when in progress; otherwise badge.
                    if (resumeMs == null) {
                        focused.runtimeLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    focused.resolutionLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (focused.isHdr) add("HDR")
                    focused.fpsLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    // SMB folders often report ~4096 bytes — never show size on directories.
                    if (!isFolder) {
                        formatFileSizeSplit(focused.entry.size)?.let { add(it) }
                    }
                }
                if (badges.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        badges.forEach { label ->
                            val isWatchedBadge = label == "WATCHED"
                            Text(
                                text = label,
                                color = if (isWatchedBadge) {
                                    chrome.accent.copy(alpha = 0.95f)
                                } else {
                                    Color.White.copy(alpha = 0.78f)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (isWatchedBadge) chrome.accent.copy(alpha = 0.16f)
                                        else Color.White.copy(alpha = 0.08f),
                                    )
                                    .border(
                                        1.dp,
                                        if (isWatchedBadge) chrome.accent.copy(alpha = 0.45f)
                                        else Color.White.copy(alpha = 0.14f),
                                        RoundedCornerShape(5.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (resumeMs != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "RESUME",
                                color = chrome.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                            )
                            Text(
                                text = formatResumeMsSplit(resumeMs),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            focused.runtimeLabel?.takeIf { it.isNotBlank() }?.let { total ->
                                Text(
                                    text = "/ $total",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.18f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(focused.resumeProgress ?: 0.35f)
                                    .fillMaxHeight()
                                    .background(chrome.accent),
                            )
                        }
                    }
                }
                focused.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                    )
                }
                var playFocused by remember(focused.entry.path) { mutableStateOf(false) }
                val playScale by animateFloatAsState(
                    targetValue = if (playFocused) 1.06f else 1f,
                    animationSpec = Motion.focusSpring(),
                    label = "splitPlayScale",
                )
                val playLabel = when {
                    isFolder -> "OPEN"
                    resumeMs != null -> "RESUME"
                    focused.watched -> "PLAY AGAIN"
                    else -> "PLAY"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(chrome.accent)
                        .border(
                            width = if (playFocused) 3.dp else 0.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .onFocusChanged { playFocused = it.isFocused }
                        .focusable()
                        .clickable(role = Role.Button, onClick = {
                            feedback.click()
                            onPlay(focused)
                        })
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp),
                    )
                    Text(
                        text = playLabel,
                        color = Color.Black,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }
    }
}
private fun cleanSplitTitle(raw: String): String {
    var t = raw
        .replace('.', ' ')
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
    t = t.replace(
        Regex(
            // Keep bare S01 / S01-S03 — only strip episode codes and release junk
            """(?i)\b(S\d{1,2}\s?E\d{1,3}|COMPLETE|PACK|1080p|720p|2160p|4K|UHD|HDR10?\+?|BluRay|WEB[- ]?DL|WEBRip|WEB|AMZN|NF|x264|x265|HEVC|PROPER|REPACK)\b""",
        ),
        "",
    )
    t = t.replace(Regex("""(?i)-[a-z0-9]{2,10}$"""), "")
    return t.replace(Regex("""\s+"""), " ").trim().ifBlank { raw }
}
private fun formatResumeMsSplit(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
private fun formatFileSizeSplit(bytes: Long): String? {
    if (bytes <= 0L) return null
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
private fun colorFromTitleSplit(title: String): Color {
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
