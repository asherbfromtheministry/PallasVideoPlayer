package com.vizvag.shieldvideo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// --- App chrome: cinema black + luminous brand lime (all surfaces) ---

/** Brand lime — focus / selected / primary accent */
val Accent = Color(0xFFC6F255)

/** Warm alert / destructive punch */
val AccentWarm = Color(0xFFFF5C5C)

/** Near-black cinema backdrop */
val AppBackground = Color(0xFF070708)

/** Soft tonal glass panel / card fill */
val CardSurface = Color(0xE0141418)

/** Zinc-400 secondary text */
val TextMuted = Color(0xFFA1A1AA)

/** Pure white for strong labels (alias kept for callers) */
val TextCream = Color(0xFFFFFFFF)

/** Cool zinc border / progress track */
val SmokeBlue = Color(0xFF71717A)

/** Crisp white focus ring (+ soft outer glow applied at call sites) */
val FocusRing = Color(0xFFFFFFFF)

/** Back-compat alias used across screens */
val CyanAccent = Accent

// --- Audio aliases → same chrome as NAS / IPTV / video ---

/** @deprecated Use [AppBackground]; kept so Music/Radio call sites compile. */
val AudioBackground = AppBackground

/** @deprecated Use [CardSurface]. */
val AudioSurface = CardSurface

/** @deprecated Use [Accent]. */
val AudioAccent = Accent

/** Soft warm secondary (kept distinct from destructive [AccentWarm]). */
val AudioAccentWarm = Color(0xFFE8A04A)

/** @deprecated Use [TextMuted]. */
val AudioTextMuted = TextMuted

/** @deprecated Use [TextCream]. */
val AudioText = TextCream

data class ScreenChrome(
    val accent: Color,
    val accentWarm: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
)

val VideoChrome = ScreenChrome(
    accent = Accent,
    accentWarm = AccentWarm,
    background = AppBackground,
    surface = CardSurface,
    text = TextCream,
    muted = TextMuted,
)

/** Same as [VideoChrome] — Music/Radio no longer fork the palette. */
val AudioChrome = VideoChrome

val LocalScreenChrome = staticCompositionLocalOf { VideoChrome }

@Composable
fun AudioScreenTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScreenChrome provides VideoChrome, content = content)
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
