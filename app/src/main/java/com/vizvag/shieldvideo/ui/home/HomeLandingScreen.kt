package com.vizvag.shieldvideo.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberDvr
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.R
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage
import com.vizvag.shieldvideo.ui.browser.nasShareIcon
import com.vizvag.shieldvideo.ui.browser.orderedSharesForRail
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.staggeredEntrance
import kotlinx.coroutines.delay

/**
 * Fraction of the media-room plate — tuned to the generated image layout:
 * radio (far left), hi-fi (left speakers), home theatre (center screen), living-room TV (right).
 */
private data class RoomHotspot(
    val key: String,
    val label: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val onOpen: () -> Unit,
)

/** Approximate aspect of [R.drawable.media_room] — hotspots are authored against this frame. */
private const val ROOM_ASPECT = 16f / 9f

@Composable
fun HomeLandingScreen(
    settings: AppSettings,
    onOpenLiveTv: () -> Unit,
    onOpenYouTube: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenShare: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRemote: () -> Unit = {},
) {
    val shares = remember(settings.shares, settings.defaultShare) { orderedSharesForRail(settings) }
    val defaultShare = remember(shares, settings.defaultShare) {
        val configured = settings.defaultShare.trim().trimEnd('/')
        shares.firstOrNull { it.equals(configured, ignoreCase = true) }
            ?: shares.firstOrNull { it.equals(settings.defaultShare, ignoreCase = true) }
            ?: shares.firstOrNull()
    }
    val otherShares = remember(shares, defaultShare) {
        if (defaultShare == null) shares
        else shares.filterNot { it.equals(defaultShare, ignoreCase = true) }
    }
    val dvrFolder = remember(settings) {
        settings.iptvRecordingNasFolder
            .takeIf { settings.iptvRecordingStorage == IptvRecordingStorage.NAS && it.isNotBlank() }
            ?.takeUnless { folder -> shares.any { it.equals(folder, ignoreCase = true) } }
    }

    val showYouTube = settings.homeShowYouTube
    val showLiveTv = settings.homeShowLiveTv
    val hotspots = remember(
        defaultShare,
        settings.homeShowRadio,
        settings.homeShowMusic,
        settings.homeShowLibrary,
        showYouTube,
        showLiveTv,
        onOpenLiveTv,
        onOpenYouTube,
        onOpenMusic,
        onOpenRadio,
        onOpenShare,
    ) {
        buildList {
            if (settings.homeShowRadio) {
                add(
                    RoomHotspot(
                        key = "radio",
                        label = "Radio",
                        kind = "ON AIR",
                        x = 0.02f, y = 0.28f, w = 0.14f, h = 0.42f,
                        onOpen = onOpenRadio,
                    ),
                )
            }
            if (settings.homeShowMusic) {
                add(
                    RoomHotspot(
                        key = "music",
                        label = "Music",
                        kind = "HI-FI",
                        x = 0.17f, y = 0.22f, w = 0.18f, h = 0.52f,
                        onOpen = onOpenMusic,
                    ),
                )
            }
            if (settings.homeShowLibrary) {
                add(
                    RoomHotspot(
                        key = "library",
                        label = defaultShare?.let { NasPaths.labelFor(it) } ?: "Library",
                        kind = "HOME THEATRE",
                        x = 0.36f, y = 0.14f, w = 0.36f, h = 0.58f,
                        onOpen = {
                            if (defaultShare != null) onOpenShare(defaultShare) else onOpenLiveTv()
                        },
                    ),
                )
            }
            when {
                showYouTube && showLiveTv -> {
                    add(
                        RoomHotspot(
                            key = "youtube",
                            label = "YouTube",
                            kind = "STREAM",
                            x = 0.73f, y = 0.22f, w = 0.12f, h = 0.40f,
                            onOpen = onOpenYouTube,
                        ),
                    )
                    add(
                        RoomHotspot(
                            key = "livetv",
                            label = "Live TV",
                            kind = "IPTV",
                            x = 0.86f, y = 0.24f, w = 0.12f, h = 0.48f,
                            onOpen = onOpenLiveTv,
                        ),
                    )
                }
                showYouTube -> {
                    add(
                        RoomHotspot(
                            key = "youtube",
                            label = "YouTube",
                            kind = "STREAM",
                            x = 0.73f, y = 0.22f, w = 0.25f, h = 0.50f,
                            onOpen = onOpenYouTube,
                        ),
                    )
                }
                showLiveTv -> {
                    add(
                        RoomHotspot(
                            key = "livetv",
                            label = "Live TV",
                            kind = "IPTV",
                            x = 0.73f, y = 0.22f, w = 0.25f, h = 0.50f,
                            onOpen = onOpenLiveTv,
                        ),
                    )
                }
            }
        }
    }

    var entered by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val initialFocusKey = remember(hotspots) {
        when {
            hotspots.any { it.key == "library" } -> "library"
            else -> hotspots.firstOrNull()?.key
        }
    }
    LaunchedEffect(hotspots, initialFocusKey) {
        entered = true
        delay(100)
        if (initialFocusKey != null) {
            runCatching { firstFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val viewAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else ROOM_ASPECT
        val stageW = if (viewAspect > ROOM_ASPECT) maxHeight * ROOM_ASPECT else maxWidth
        val stageH = if (viewAspect > ROOM_ASPECT) maxHeight else maxWidth / ROOM_ASPECT
        val titleSp = when {
            stageH < 280.dp -> 28.sp
            stageH < 360.dp -> 34.sp
            else -> 44.sp
        }
        val compactChrome = stageH < 320.dp || stageW < 520.dp

        // Scale the authored landscape room to fit — never crush hotspots into portrait bounds.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(stageW)
                .height(stageH),
        ) {
            Image(
                painter = painterResource(R.drawable.media_room),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.55f),
                                0.18f to Color.Transparent,
                                0.78f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (compactChrome) 10.dp else 20.dp)
                    .staggeredEntrance(entered, 0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "PALLAS",
                    color = TextCream,
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = titleSp,
                    letterSpacing = (-1.2).sp,
                )
                Text(
                    text = "CHOOSE A DEVICE",
                    color = Accent.copy(alpha = 0.92f),
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compactChrome) 10.sp else 12.sp,
                    letterSpacing = 3.sp,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (compactChrome) 8.dp else 20.dp,
                        end = if (compactChrome) 12.dp else 28.dp,
                    ),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconActionButton(selected = false, onClick = onOpenRemote) {
                        Icon(
                            Icons.Filled.Phonelink,
                            contentDescription = "Remote",
                            tint = Color.White,
                            modifier = Modifier.size(if (compactChrome) 22.dp else 26.dp),
                        )
                    }
                    IconActionButton(selected = false, onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(if (compactChrome) 22.dp else 26.dp),
                        )
                    }
                }
            }

            hotspots.forEachIndexed { index, spot ->
                RoomHotspotFrame(
                    label = spot.label,
                    kind = spot.kind,
                    compact = compactChrome,
                    onOpen = spot.onOpen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = stageW * spot.x,
                            top = stageH * spot.y,
                        )
                        .width(stageW * spot.w)
                        .height(stageH * spot.h)
                        .then(
                            if (spot.key == initialFocusKey) Modifier.focusRequester(firstFocus)
                            else Modifier
                        )
                        .staggeredEntrance(entered, index + 1),
                )
            }

            val corridor = buildList {
                otherShares.forEach { share ->
                    add(Triple(NasPaths.labelFor(share), nasShareIcon(share)) { onOpenShare(share) })
                }
                if (dvrFolder != null) {
                    add(Triple("DVR", Icons.Filled.FiberDvr) { onOpenShare(dvrFolder) })
                }
            }
            if (corridor.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = if (compactChrome) 12.dp else 28.dp,
                            bottom = if (compactChrome) 10.dp else 20.dp,
                            end = if (compactChrome) 12.dp else 28.dp,
                        )
                        .fillMaxWidth(0.72f)
                        .horizontalScroll(rememberScrollState())
                        .staggeredEntrance(entered, 6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "MORE NAS",
                        color = TextMuted,
                        fontFamily = PallasFontFamily,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    corridor.forEachIndexed { index, (title, icon, open) ->
                        CorridorPlaque(
                            title = title,
                            icon = icon,
                            onOpen = open,
                            modifier = Modifier.staggeredEntrance(entered, index + 7),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomHotspotFrame(
    label: String,
    kind: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.focusSpring(),
        label = "hotspotGlow",
    )
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = 1f + 0.02f * glow
                scaleY = 1f + 0.02f * glow
            }
            .clip(shape)
            .border(
                width = if (focused) 3.dp else 1.5.dp,
                color = if (focused) Accent else Color.White.copy(alpha = 0.12f),
                shape = shape,
            )
            .background(
                if (focused) Accent.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .focusable()
            .clickable(role = Role.Button, onClick = {
                feedback.click()
                onOpen()
            }),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 6.dp else 12.dp)
                .background(
                    Color.Black.copy(alpha = if (focused) 0.72f else 0.42f),
                    RoundedCornerShape(8.dp),
                )
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 5.dp else 8.dp,
                ),
        ) {
            Text(
                text = kind,
                color = if (focused) Accent else TextMuted,
                fontFamily = PallasFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 8.sp else 10.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = label,
                color = TextCream,
                fontFamily = PallasFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = when {
                    compact && focused -> 14.sp
                    compact -> 12.sp
                    focused -> 20.sp
                    else -> 16.sp
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CorridorPlaque(
    title: String,
    icon: ImageVector,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = if (focused) 0.78f else 0.5f))
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) Accent else Color.White.copy(alpha = 0.18f),
                shape = shape,
            )
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .focusable()
            .clickable(role = Role.Button, onClick = {
                feedback.click()
                onOpen()
            })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) Accent else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            color = if (focused) Accent else TextCream,
            fontFamily = PallasFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
