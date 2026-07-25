package com.vizvag.shieldvideo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.FiberDvr
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.ui.browser.MediaCardItem
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.staggeredEntrance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DarkGlass = Color(0xE6121216)
private val ArtPanelBg = Color(0xFF0E0E12)
private val IconShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(20.dp)
private val ArtInsetShape = RoundedCornerShape(16.dp)

@Composable
fun IconActionButton(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = Motion.focusSpring(),
        label = "iconScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            focused -> 2.dp
            selected -> 1.5.dp
            else -> 1.dp
        },
        animationSpec = Motion.focusSpring(),
        label = "iconBorder",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> chrome.accent
            selected -> chrome.accent.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        animationSpec = Motion.focusSpring(),
        label = "iconBorderColor",
    )
    val bg by animateColorAsState(
        targetValue = when {
            selected -> chrome.accent.copy(alpha = 0.35f)
            focused -> Color.White.copy(alpha = 0.14f)
            else -> chrome.surface.copy(alpha = 0.55f)
        },
        animationSpec = Motion.focusSpring(),
        label = "iconBg",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.focusSpring(),
        label = "iconGlow",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(72.dp)
    ) {
        // Soft ambient glow behind the pill (blurred-ish via oversized soft disc)
        Box(
            modifier = Modifier
                .size(68.dp)
                .graphicsLayer { alpha = glowAlpha * 0.9f }
                .background(chrome.accent.copy(alpha = 0.25f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(IconShape)
                .background(bg)
                .border(borderWidth, borderColor, IconShape)
                .onFocusChanged {
                    val gained = it.isFocused && !focused
                    focused = it.isFocused
                    if (gained) feedback.focus()
                }
                .focusProperties { canFocus = enabled }
                .focusable(enabled = enabled)
                .clickable(enabled = enabled, role = Role.Button, onClick = {
                    feedback.click()
                    onClick()
                }),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun ShareSwitcher(
    shares: List<String>,
    selectedShare: String,
    onSelect: (String) -> Unit,
    onSearch: (() -> Unit)? = null,
    onSettings: () -> Unit,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onLiveTv: (() -> Unit)? = null,
    liveTvSelected: Boolean = false,
    onRadio: (() -> Unit)? = null,
    radioSelected: Boolean = false,
    onMusic: (() -> Unit)? = null,
    musicSelected: Boolean = false,
    /** NAS IPTV recording folder ("/share/folder"); shown as its own icon when set. */
    recordingFolder: String? = null,
    sleepTimerActive: Boolean = false,
    sleepTimerLabel: String? = null,
    onCycleSleepTimer: (() -> Unit)? = null
) {
    val chrome = LocalScreenChrome.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shares.forEach { share ->
                val label = NasPaths.labelFor(share)
                val selected = !liveTvSelected && !radioSelected && !musicSelected &&
                    share.equals(selectedShare, ignoreCase = true)
                IconActionButton(selected = selected, onClick = { onSelect(share) }) {
                    Icon(
                        imageVector = shareIcon(share),
                        contentDescription = label,
                        tint = if (selected) chrome.accent else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (!recordingFolder.isNullOrBlank() &&
                shares.none { it.equals(recordingFolder, ignoreCase = true) }
            ) {
                val selected = !liveTvSelected && !radioSelected && !musicSelected &&
                    recordingFolder.equals(selectedShare, ignoreCase = true)
                IconActionButton(selected = selected, onClick = { onSelect(recordingFolder) }) {
                    Icon(
                        imageVector = Icons.Filled.FiberDvr,
                        contentDescription = "IPTV recordings",
                        tint = if (selected) chrome.accent else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (onLiveTv != null) {
                IconActionButton(selected = liveTvSelected, onClick = onLiveTv) {
                    Icon(
                        imageVector = Icons.Filled.LiveTv,
                        contentDescription = "Live TV",
                        tint = if (liveTvSelected) chrome.accent else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (onRadio != null) {
                IconActionButton(selected = radioSelected, onClick = onRadio) {
                    Icon(
                        imageVector = Icons.Filled.Radio,
                        contentDescription = "Radio",
                        tint = if (radioSelected) chrome.accent else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (onMusic != null) {
                IconActionButton(selected = musicSelected, onClick = onMusic) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = "Music",
                        tint = if (musicSelected) chrome.accent else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (onCycleSleepTimer != null) {
                SleepTimerButton(
                    active = sleepTimerActive,
                    label = sleepTimerLabel,
                    onCycle = onCycleSleepTimer
                )
            }

            if (canGoUp) {
                IconActionButton(selected = false, onClick = onGoUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Up",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            onSearch?.let { openSearch ->
                IconActionButton(selected = false, onClick = openSearch) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            IconActionButton(selected = false, onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .height(1.dp)
                .background(chrome.muted.copy(alpha = 0.18f))
        )
    }
}

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HaStyleMediaCard(
    item: MediaCardItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    entranceIndex: Int = 0,
) {
    val chrome = LocalScreenChrome.current
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val feedback = rememberTvFeedback()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = Motion.cardSpring(),
        label = "cardScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            longPressHandled -> 3.dp
            focused -> 2.5.dp
            else -> 1.dp
        },
        animationSpec = Motion.focusSpring(),
        label = "cardBorderW",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            longPressHandled -> chrome.accent
            focused -> Color.White
            else -> Color.White.copy(alpha = 0.08f)
        },
        animationSpec = Motion.focusSpring(),
        label = "cardBorderC",
    )
    val bg by animateColorAsState(
        targetValue = if (focused) Color(0xF018181E) else DarkGlass,
        animationSpec = Motion.focusSpring(),
        label = "cardBg",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.focusSpring(),
        label = "cardGlow",
    )
    val contentAlpha = if (item.watched) 0.42f else 1f
    val hasResume = item.resumePositionMs != null && !item.watched
    val artScale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = Motion.cardSpring(),
        label = "artScale",
    )

    fun fireLongPress() {
        if (onLongClick == null || longPressHandled) return
        longPressHandled = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(visible = true, index = entranceIndex),
        contentAlignment = Alignment.Center
    ) {
        // Soft ambient accent glow behind the card
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .graphicsLayer { alpha = glowAlpha * 0.85f }
                .background(
                    chrome.accent.copy(alpha = 0.22f),
                    RoundedCornerShape(24.dp),
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CardShape)
                .background(bg)
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = CardShape
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
                    onLongClick = onLongClick
                )
                .focusable(interactionSource = interaction),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 22.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.displayTitle,
                        color = chrome.text.copy(alpha = contentAlpha),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 26.sp
                    )
                    val secondary = listOf(item.line1, item.line2)
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  ")
                    if (secondary.isNotBlank()) {
                        Text(
                            text = secondary,
                            color = chrome.muted.copy(alpha = 0.92f * contentAlpha),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 17.sp
                        )
                    }
                    if (!hasResume && item.line3.isNotBlank()) {
                        Text(
                            text = item.line3,
                            color = chrome.muted.copy(alpha = 0.65f * contentAlpha),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasResume) {
                        Text(
                            text = "Resume  ${formatResume(item.resumePositionMs!!)}",
                            color = chrome.accent.copy(alpha = contentAlpha),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    item.resolutionLabel?.let { label ->
                        QualityBadge(label = label, contentAlpha = contentAlpha)
                    }
                    item.fpsLabel?.let { label ->
                        QualityBadge(label = label, contentAlpha = contentAlpha)
                    }
                    if (item.isHdr) {
                        QualityBadge(label = "HDR", contentAlpha = contentAlpha)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(end = 10.dp, top = 10.dp, bottom = 10.dp)
                    .fillMaxHeight()
                    .width(280.dp)
                    .clip(ArtInsetShape)
                    .background(ArtPanelBg),
                contentAlignment = Alignment.Center
            ) {
                val image = item.fanartUrl ?: item.posterUrl
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = item.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = artScale
                                scaleY = artScale
                            }
                            .alpha(contentAlpha)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = if (focused) 0.40f else 0.22f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                } else {
                    Icon(
                        imageVector = when {
                            item.entry.isDirectory -> Icons.Filled.Folder
                            NasPaths.isArchiveFile(item.entry.name) -> Icons.Filled.FolderZip
                            else -> Icons.Filled.Movie
                        },
                        contentDescription = null,
                        tint = chrome.accent.copy(alpha = 0.55f * contentAlpha),
                        modifier = Modifier.size(40.dp)
                    )
                }
                if (hasResume) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "Resume",
                            color = Color.White.copy(alpha = 0.95f * contentAlpha),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier
                                .padding(start = 10.dp, bottom = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(item.resumeProgress ?: 0.35f)
                                    .fillMaxHeight()
                                    .background(chrome.accent)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityBadge(label: String, contentAlpha: Float) {
    val chrome = LocalScreenChrome.current
    Text(
        text = label,
        color = chrome.text.copy(alpha = 0.78f * contentAlpha),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.07f * contentAlpha))
            .border(1.dp, Color.White.copy(alpha = 0.12f * contentAlpha), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun formatResume(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun StatusPane(loading: Boolean, error: String?, empty: Boolean) {
    val chrome = LocalScreenChrome.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    CircularProgressIndicator(
                        color = chrome.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Loading",
                        color = chrome.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    )
                    Text(
                        text = "Fetching titles from your library",
                        color = chrome.muted.copy(alpha = 0.85f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Something went wrong",
                        color = chrome.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = error,
                        color = chrome.accentWarm.copy(alpha = 0.95f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth(0.72f)
                    )
                }
            }
            empty -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Nothing here yet",
                        color = chrome.text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "No videos in this folder",
                        color = chrome.muted.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
