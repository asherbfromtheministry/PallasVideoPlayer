package com.vizvag.shieldvideo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// --- Option B: Glass Drift — cinema black + per-section accents ---

/** Brand lime — video / library default */
val Accent = Color(0xFFC6F255)

/** Warm alert / destructive punch */
val AccentWarm = Color(0xFFFF5C5C)

/** Near-black cinema backdrop */
val AppBackground = Color(0xFF05050A)

/** Soft tonal glass panel / card fill */
val CardSurface = Color(0xCC14141C)

/** Zinc-400 secondary text */
val TextMuted = Color(0xFFA1A1AA)

/** Pure white for strong labels */
val TextCream = Color(0xFFFFFFFF)

/** Cool zinc border / progress track */
val SmokeBlue = Color(0xFF71717A)

/**
 * Focus uses an animated gradient border (accent → accentSecondary), not a solid white ring.
 * Kept as white for hairline contrast inside the glass border where needed.
 */
val FocusRing = Color(0xFFFFFFFF)

/** Back-compat alias */
val CyanAccent = Accent

// --- Audio aliases (point at music chrome via ScreenTheme; values match video defaults) ---

val AudioBackground = AppBackground
val AudioSurface = CardSurface
val AudioAccent = Accent
val AudioAccentWarm = Color(0xFFE8A04A)
val AudioTextMuted = TextMuted
val AudioText = TextCream

/**
 * Unified Glass Drift shape ladder — every interactive control uses [control].
 * No circles for buttons.
 */
object PallasShapes {
    /** All buttons, rail tiles, transport, chips */
    val control: Dp = 20.dp
    /** Cards, panels, glass sheets */
    val panel: Dp = 20.dp
    /** Art / poster insets */
    val art: Dp = 16.dp
    /** Progress track ends (still squared family, not circle) */
    val track: Dp = 6.dp
}

data class ScreenChrome(
    val accent: Color,
    /** Second stop for gradient focus borders / aurora wash */
    val accentSecondary: Color,
    val accentWarm: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
)

val VideoChrome = ScreenChrome(
    accent = Color(0xFFC6F255),
    accentSecondary = Color(0xFF7CF5C8),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val MusicChrome = ScreenChrome(
    accent = Color(0xFF7C6CFF),
    accentSecondary = Color(0xFFB4A0FF),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val LiveTvChrome = ScreenChrome(
    accent = Color(0xFF00E5C8),
    accentSecondary = Color(0xFF3D8BFF),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val RadioChrome = ScreenChrome(
    accent = Color(0xFFFF8A5C),
    accentSecondary = Color(0xFFFF4D8D),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val PodcastChrome = ScreenChrome(
    accent = Color(0xFFFF2D95),
    accentSecondary = Color(0xFFC026FF),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val YoutubeChrome = ScreenChrome(
    accent = Color(0xFFFF6A00),
    accentSecondary = Color(0xFFFFB020),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val SettingsChrome = ScreenChrome(
    // Sky blue — distinct from muted zinc text and from Live TV teal / Music indigo.
    accent = Color(0xFF38BDF8),
    accentSecondary = Color(0xFF818CF8),
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

val HomeChrome = VideoChrome

/** @deprecated Prefer [MusicChrome] via [ScreenTheme]. */
val AudioChrome = MusicChrome

val LocalScreenChrome = staticCompositionLocalOf { VideoChrome }

/**
 * When true, skip expensive continuous visuals (blur, looping EQ/ambient, border orbit).
 * Set from Settings → Display → Lite visuals.
 */
val LocalLiteVisuals = staticCompositionLocalOf { false }

@Composable
fun ScreenTheme(
    chrome: ScreenChrome,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalScreenChrome provides chrome, content = content)
}

/** @deprecated Use [ScreenTheme] with [MusicChrome] / [RadioChrome]. */
@Composable
fun AudioScreenTheme(content: @Composable () -> Unit) {
    ScreenTheme(MusicChrome, content)
}

@Composable
fun ShieldVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = AppBackground,
            secondary = AccentWarm,
            onSecondary = TextCream,
            background = AppBackground,
            onBackground = TextCream,
            surface = Color(0xFF0B0B0D),
            onSurface = TextCream,
            surfaceVariant = CardSurface,
            onSurfaceVariant = TextMuted,
            outline = SmokeBlue,
            error = AccentWarm,
            onError = TextCream,
        ),
        typography = PallasTypography,
        content = content,
    )
}
