package com.vizvag.shieldvideo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.theme.HomeChrome
import com.vizvag.shieldvideo.ui.theme.LiveTvChrome
import com.vizvag.shieldvideo.ui.theme.LocalLiteVisuals
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.MusicChrome
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.PodcastChrome
import com.vizvag.shieldvideo.ui.theme.RadioChrome
import com.vizvag.shieldvideo.ui.theme.ScreenChrome
import com.vizvag.shieldvideo.ui.theme.SettingsChrome
import com.vizvag.shieldvideo.ui.theme.VideoChrome
import com.vizvag.shieldvideo.ui.theme.YoutubeChrome
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback

fun chromeFor(destination: RailDestination): ScreenChrome = when (destination) {
    RailDestination.Browser -> VideoChrome
    RailDestination.LiveTv -> LiveTvChrome
    RailDestination.YouTube -> YoutubeChrome
    RailDestination.Radio -> RadioChrome
    RailDestination.Music -> MusicChrome
    RailDestination.Podcasts -> PodcastChrome
}

fun chromeForRoute(route: String?): ScreenChrome = when (route?.substringBefore("/")) {
    "home" -> HomeChrome
    "browser" -> VideoChrome
    "music" -> MusicChrome
    "radio" -> RadioChrome
    "iptv", "multiview" -> LiveTvChrome
    "youtube" -> YoutubeChrome
    "podcasts" -> PodcastChrome
    "settings", "remote" -> SettingsChrome
    else -> VideoChrome
}

/** Selected / playing / active fill — frosted accent wash only. */
const val GlassSelectedAlpha = 0.38f

/**
 * THE Option B interaction grammar — use this on every focusable control/row/chip.
 *
 * - FOCUS → soft shimmer border + scale into a reserved gutter (stays on-screen).
 * - SELECTED → frosted accent wash fill.
 * - IDLE → glass surface + 10% white hairline.
 * - Shape → always [PallasShapes.control] (20dp).
 */
@Composable
fun Modifier.glassInteract(
    focused: Boolean,
    selected: Boolean = false,
    scaleOnFocus: Boolean = true,
    corner: Dp = PallasShapes.control,
    idleSurface: Color = LocalScreenChrome.current.surface.copy(alpha = 0.55f),
    selectedAlpha: Float = GlassSelectedAlpha,
    /** Idle hairline — turn off for inline text links (artist / song / album). */
    showIdleBorder: Boolean = true,
): Modifier {
    val chrome = LocalScreenChrome.current
    val shape = RoundedCornerShape(corner)
    val scale by animateFloatAsState(
        targetValue = if (scaleOnFocus && focused) 1.05f else 1f,
        animationSpec = Motion.focusSpring(),
        label = "glassScale",
    )
    val bg = when {
        selected -> chrome.accent.copy(alpha = selectedAlpha)
        else -> idleSurface
    }
    return this
        // Reserve space so scale-up grows into the gutter, not past the parent/screen.
        .then(if (scaleOnFocus) Modifier.padding(6.dp) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(bg, shape)
        .then(
            if (showIdleBorder && !focused && !selected) {
                Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            } else {
                Modifier
            },
        )
        // Shimmer ring drawn on top of the fill so it’s always visible when focused.
        .glassFocusBorderAnimated(
            focused = focused,
            selected = false,
            accent = chrome.accent,
            accentSecondary = chrome.accentSecondary,
            corner = corner,
        )
}

/**
 * Icon / square control — same grammar as rows/chips.
 */
@Composable
fun GlassControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 60.dp,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassInteract(focused = focused, selected = selected)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            // clickable is already focusable — do not stack .focusable() or focus chrome
            // drops after press (parent isFocused=false while child holds focus).
            .focusProperties { canFocus = enabled }
            .clickable(enabled = enabled, role = Role.Button, onClick = {
                feedback.click()
                onClick()
            }),
        content = content,
    )
}

/**
 * Text / chip / settings-style button — same grammar, flexible padding.
 */
@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassInteract(focused = focused, selected = selected)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .focusProperties { canFocus = enabled }
            .clickable(enabled = enabled, role = Role.Button, onClick = {
                feedback.click()
                onClick()
            })
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Focus border with a clearly visible slow shimmer (moving highlight on the ring).
 * Slow (~6.5s sweep) — not a fast spin. Always animates while focused (even in lite).
 */
@Composable
fun Modifier.glassFocusBorderAnimated(
    focused: Boolean,
    selected: Boolean = false,
    accent: Color = LocalScreenChrome.current.accent,
    accentSecondary: Color = LocalScreenChrome.current.accentSecondary,
    corner: Dp = PallasShapes.control,
    stroke: Dp = 3.dp,
): Modifier {
    val lite = LocalLiteVisuals.current
    val infinite = rememberInfiniteTransition(label = "glassShimmer")
    // Highlight travels along the frame. Keep duration slow and constant (never 1ms).
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (lite) 9_000 else 6_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerPulse",
    )
    return this.drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent

        val radius = CornerRadius(corner.toPx(), corner.toPx())
        val strokePx = stroke.toPx()
        val inset = strokePx / 2f
        val topLeft = Offset(inset, inset)
        val rect = Size(size.width - strokePx, size.height - strokePx)

        // Soft outer bloom so focus reads from the couch
        drawRoundRect(
            color = accent.copy(alpha = 0.22f * pulse),
            topLeft = Offset(-3f, -3f),
            size = Size(size.width + 6f, size.height + 6f),
            cornerRadius = CornerRadius(corner.toPx() + 3f, corner.toPx() + 3f),
            style = Stroke(width = 6f),
        )

        // Base ring
        drawRoundRect(
            color = accent.copy(alpha = 0.65f),
            topLeft = topLeft,
            size = rect,
            cornerRadius = radius,
            style = Stroke(width = strokePx),
        )

        // Moving shimmer highlight on the ring (wide bright band, slow travel)
        val travel = sweep * (size.width + size.height)
        val band = size.minDimension * 0.55f
        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.35f to accentSecondary.copy(alpha = 0.35f),
                0.48f to Color.White.copy(alpha = 0.95f),
                0.52f to Color.White.copy(alpha = 0.95f),
                0.65f to accent.copy(alpha = 0.75f),
                1.00f to Color.Transparent,
            ),
            start = Offset(travel - band, travel - band),
            end = Offset(travel + band * 0.15f, travel + band * 0.15f),
            tileMode = TileMode.Clamp,
        )
        drawRoundRect(
            brush = brush,
            topLeft = topLeft,
            size = rect,
            cornerRadius = radius,
            style = Stroke(width = strokePx),
        )
    }
}

/** @deprecated Use [glassInteract] / [glassFocusBorderAnimated]. */
fun Modifier.glassFocusBorder(
    visible: Boolean,
    accent: Color,
    accentSecondary: Color,
    selected: Boolean,
    focused: Boolean,
    corner: Dp = PallasShapes.control,
    stroke: Dp = 2.5.dp,
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        if (!visible || !focused) return@drawWithContent
        val radius = CornerRadius(corner.toPx(), corner.toPx())
        val strokePx = stroke.toPx()
        val inset = strokePx / 2f
        val brush = Brush.linearGradient(
            colors = listOf(accent, accentSecondary, Color.White, accent),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = radius,
            style = Stroke(width = strokePx),
        )
    },
)

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val chrome = LocalScreenChrome.current
    val shape = RoundedCornerShape(PallasShapes.panel)
    Box(
        modifier = modifier
            .clip(shape)
            .background(chrome.surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        content = content,
    )
}

/** Diagonal shimmer sweep for loading placeholders. */
@Composable
fun GlassShimmer(
    modifier: Modifier = Modifier,
) {
    val chrome = LocalScreenChrome.current
    val lite = LocalLiteVisuals.current
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (lite) 1 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerT",
    )
    val shape = RoundedCornerShape(PallasShapes.art)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF121218), shape)
            .drawBehind {
                if (lite) return@drawBehind
                val w = size.width
                val x = -w + (w * 2.2f * t)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            chrome.accent.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.18f),
                            chrome.accentSecondary.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x + w * 0.55f, size.height),
                    ),
                )
            },
    )
}
