package com.vizvag.shieldvideo.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.FeatureFlags
import com.vizvag.shieldvideo.R
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage
import com.vizvag.shieldvideo.ui.browser.nasShareIcon
import com.vizvag.shieldvideo.ui.browser.orderedSharesForRail
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.LiveTvChrome
import com.vizvag.shieldvideo.ui.theme.MusicChrome
import com.vizvag.shieldvideo.ui.theme.PodcastChrome
import com.vizvag.shieldvideo.ui.theme.RadioChrome
import com.vizvag.shieldvideo.ui.theme.ScreenChrome
import com.vizvag.shieldvideo.ui.theme.ScreenTheme
import com.vizvag.shieldvideo.ui.theme.SettingsChrome
import com.vizvag.shieldvideo.ui.theme.VideoChrome
import com.vizvag.shieldvideo.ui.theme.YoutubeChrome
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.staggeredEntrance
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Fraction of the media-room plate — authored against [R.drawable.media_room]:
 * radio (left sideboard), music (speakers + amp rack), podcasts (headphones/mic table),
 * library / Download (center cinema screen), YouTube (wall TV), Live TV (far-right console TV).
 * Each rect covers that prop — not a forced horizontal strip.
 */
private data class RoomHotspot(
    val key: String,
    val label: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val chrome: ScreenChrome,
    val onOpen: () -> Unit,
)

private data class FocusNode(
    val key: String,
    val cx: Float,
    val cy: Float,
    val requester: FocusRequester,
)

private data class FocusLinks(
    val left: FocusRequester? = null,
    val right: FocusRequester? = null,
    val up: FocusRequester? = null,
    val down: FocusRequester? = null,
)

/** Aspect of [R.drawable.media_room] (1536×1024). Hotspots are authored against this frame. */
private const val ROOM_ASPECT = 1536f / 1024f

@Composable
fun HomeLandingScreen(
    settings: AppSettings,
    onOpenLiveTv: () -> Unit,
    onOpenYouTube: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenPodcasts: () -> Unit = {},
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

    val showYouTube = settings.homeShowYouTube && FeatureFlags.youtube
    val showLiveTv = settings.homeShowLiveTv
    val hotspots = remember(
        defaultShare,
        settings.homeShowRadio,
        settings.homeShowMusic,
        settings.homeShowPodcasts,
        settings.homeShowLibrary,
        showYouTube,
        showLiveTv,
        onOpenLiveTv,
        onOpenYouTube,
        onOpenMusic,
        onOpenRadio,
        onOpenPodcasts,
        onOpenShare,
    ) {
        buildList {
            // Zones stay locked to image props even when siblings are hidden.
            if (settings.homeShowRadio) {
                add(
                    RoomHotspot(
                        key = "radio",
                        label = "Radio",
                        kind = "ON AIR",
                        x = 0f, y = 0.34f, w = 0.18f, h = 0.28f,
                        chrome = RadioChrome,
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
                        x = 0.18f, y = 0.14f, w = 0.17f, h = 0.48f,
                        chrome = MusicChrome,
                        onOpen = onOpenMusic,
                    ),
                )
            }
            if (settings.homeShowPodcasts) {
                add(
                    RoomHotspot(
                        key = "podcasts",
                        label = "Podcasts",
                        kind = "SHOWS",
                        x = 0.17f, y = 0.64f, w = 0.22f, h = 0.28f,
                        chrome = PodcastChrome,
                        onOpen = onOpenPodcasts,
                    ),
                )
            }
            if (settings.homeShowLibrary) {
                add(
                    RoomHotspot(
                        key = "library",
                        label = defaultShare?.let { NasPaths.labelFor(it) } ?: "Library",
                        kind = "HOME THEATRE",
                        x = 0.34f, y = 0.20f, w = 0.32f, h = 0.42f,
                        chrome = VideoChrome,
                        onOpen = {
                            if (defaultShare != null) onOpenShare(defaultShare) else onOpenLiveTv()
                        },
                    ),
                )
            }
            if (showYouTube) {
                add(
                    RoomHotspot(
                        key = "youtube",
                        label = "YouTube",
                        kind = "STREAM",
                        x = 0.67f, y = 0.18f, w = 0.16f, h = 0.34f,
                        chrome = YoutubeChrome,
                        onOpen = onOpenYouTube,
                    ),
                )
            }
            if (showLiveTv) {
                add(
                    RoomHotspot(
                        key = "livetv",
                        label = "Live TV",
                        kind = "IPTV",
                        x = 0.83f, y = 0.34f, w = 0.17f, h = 0.36f,
                        chrome = LiveTvChrome,
                        onOpen = onOpenLiveTv,
                    ),
                )
            }
        }
    }

    val corridor = remember(otherShares, dvrFolder) {
        buildList {
            otherShares.forEach { share ->
                add(Triple(NasPaths.labelFor(share), nasShareIcon(share)) { onOpenShare(share) })
            }
            if (dvrFolder != null) {
                add(Triple("DVR", Icons.Filled.FiberDvr) { onOpenShare(dvrFolder) })
            }
        }
    }

    val hotspotRequesters = remember(hotspots) {
        hotspots.associate { it.key to FocusRequester() }
    }
    val remoteFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val corridorRequesters = remember(corridor.size) {
        List(corridor.size) { FocusRequester() }
    }

    val focusLinks = remember(hotspots, corridor.size, hotspotRequesters, corridorRequesters) {
        buildLandingFocusLinks(
            hotspots = hotspots,
            hotspotRequesters = hotspotRequesters,
            remoteFocus = remoteFocus,
            settingsFocus = settingsFocus,
            corridorRequesters = corridorRequesters,
        )
    }

    var entered by remember { mutableStateOf(false) }
    val initialFocusKey = remember(hotspots) {
        when {
            hotspots.any { it.key == "youtube" } -> "youtube"
            hotspots.any { it.key == "library" } -> "library"
            else -> hotspots.firstOrNull()?.key
        }
    }
    val initialFocus = initialFocusKey?.let { hotspotRequesters[it] }
    LaunchedEffect(hotspots, initialFocusKey) {
        entered = true
        delay(100)
        if (initialFocus != null) {
            runCatching { initialFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val viewAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else ROOM_ASPECT
        // Cover transform: image fills the viewport; overflow is cropped (matches ContentScale.Crop).
        val coverW: Dp
        val coverH: Dp
        val coverOffsetX: Dp
        val coverOffsetY: Dp
        if (viewAspect > ROOM_ASPECT) {
            coverW = maxWidth
            coverH = maxWidth / ROOM_ASPECT
            coverOffsetX = 0.dp
            coverOffsetY = (maxHeight - coverH) / 2
        } else {
            coverH = maxHeight
            coverW = maxHeight * ROOM_ASPECT
            coverOffsetX = (maxWidth - coverW) / 2
            coverOffsetY = 0.dp
        }
        val titleSp = when {
            maxHeight < 280.dp -> 28.sp
            maxHeight < 360.dp -> 34.sp
            else -> 44.sp
        }
        val compactChrome = maxHeight < 320.dp || maxWidth < 520.dp

        Box(modifier = Modifier.fillMaxSize()) {
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
                    ScreenTheme(SettingsChrome) {
                        IconActionButton(
                            selected = false,
                            onClick = onOpenRemote,
                            focusModifier = Modifier
                                .focusRequester(remoteFocus)
                                .landingFocusLinks(focusLinks["remote"]),
                        ) {
                            Icon(
                                Icons.Filled.Phonelink,
                                contentDescription = "Remote",
                                tint = Color.White,
                                modifier = Modifier.size(if (compactChrome) 22.dp else 26.dp),
                            )
                        }
                        IconActionButton(
                            selected = false,
                            onClick = onOpenSettings,
                            focusModifier = Modifier
                                .focusRequester(settingsFocus)
                                .landingFocusLinks(focusLinks["settings"]),
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(if (compactChrome) 22.dp else 26.dp),
                            )
                        }
                    }
                }
            }

            hotspots.forEachIndexed { index, spot ->
                val requester = hotspotRequesters.getValue(spot.key)
                ScreenTheme(spot.chrome) {
                    RoomHotspotFrame(
                        label = spot.label,
                        kind = spot.kind,
                        compact = compactChrome,
                        onOpen = spot.onOpen,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = coverW * spot.x + coverOffsetX,
                                y = coverH * spot.y + coverOffsetY,
                            )
                            .width(coverW * spot.w)
                            .height(coverH * spot.h)
                            .focusRequester(requester)
                            .landingFocusLinks(focusLinks[spot.key])
                            .staggeredEntrance(entered, index + 1),
                    )
                }
            }

            if (corridor.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            bottom = if (compactChrome) 10.dp else 20.dp,
                            // Keep clear of the on-screen clock/date (bottom-right).
                            end = if (compactChrome) 160.dp else 220.dp,
                        )
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
                            modifier = Modifier
                                .focusRequester(corridorRequesters[index])
                                .landingFocusLinks(focusLinks["corridor_$index"])
                                .staggeredEntrance(entered, index + 7),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Explicit D-pad graph from room geometry so Right from Library goes YouTube/Live TV,
 * not top-chrome Remote/Settings or bottom MORE NAS plaques.
 */
private fun buildLandingFocusLinks(
    hotspots: List<RoomHotspot>,
    hotspotRequesters: Map<String, FocusRequester>,
    remoteFocus: FocusRequester,
    settingsFocus: FocusRequester,
    corridorRequesters: List<FocusRequester>,
): Map<String, FocusLinks> {
    val nodes = buildList {
        hotspots.forEach { spot ->
            add(
                FocusNode(
                    key = spot.key,
                    cx = spot.x + spot.w / 2f,
                    cy = spot.y + spot.h / 2f,
                    requester = hotspotRequesters.getValue(spot.key),
                ),
            )
        }
        add(FocusNode("remote", cx = 0.90f, cy = 0.06f, requester = remoteFocus))
        add(FocusNode("settings", cx = 0.96f, cy = 0.06f, requester = settingsFocus))
        corridorRequesters.forEachIndexed { index, requester ->
            // Bottom strip, left→right; keep clear of clock on the far right.
            val cx = 0.55f + index * 0.08f
            add(FocusNode("corridor_$index", cx = cx.coerceAtMost(0.82f), cy = 0.93f, requester = requester))
        }
    }
    return nodes.associate { node ->
        node.key to FocusLinks(
            left = nearestInDirection(node, nodes, dirX = -1f, dirY = 0f),
            right = nearestInDirection(node, nodes, dirX = 1f, dirY = 0f),
            up = nearestInDirection(node, nodes, dirX = 0f, dirY = -1f),
            down = nearestInDirection(node, nodes, dirX = 0f, dirY = 1f),
        )
    }
}

private fun nearestInDirection(
    from: FocusNode,
    nodes: List<FocusNode>,
    dirX: Float,
    dirY: Float,
): FocusRequester? {
    val axisWeight = 2.4f
    var best: FocusNode? = null
    var bestScore = Float.MAX_VALUE
    for (candidate in nodes) {
        if (candidate.key == from.key) continue
        val dx = candidate.cx - from.cx
        val dy = candidate.cy - from.cy
        // Must lie in the requested half-plane with a small dead-zone.
        val along = dx * dirX + dy * dirY
        if (along < 0.04f) continue
        val across = abs(dx * dirY - dy * dirX)
        // Prefer aligned neighbors (Library→YouTube/Live TV over Library→Remote).
        val score = along + across * axisWeight + hypot(dx.toDouble(), dy.toDouble()).toFloat() * 0.15f
        if (score < bestScore) {
            bestScore = score
            best = candidate
        }
    }
    return best?.requester
}

private fun Modifier.landingFocusLinks(links: FocusLinks?): Modifier {
    if (links == null) return this
    return focusProperties {
        links.left?.let { left = it }
        links.right?.let { right = it }
        links.up?.let { up = it }
        links.down?.let { down = it }
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
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .glassInteract(focused = focused, selected = false, idleSurface = Color.Transparent)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
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
                color = if (focused) chrome.accent else TextMuted,
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
    val chrome = LocalScreenChrome.current
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .glassInteract(focused = focused, selected = false)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
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
            tint = if (focused) chrome.accent else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            color = if (focused) chrome.accent else TextCream,
            fontFamily = PallasFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
