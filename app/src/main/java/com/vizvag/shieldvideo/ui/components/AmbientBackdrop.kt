package com.vizvag.shieldvideo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.vizvag.shieldvideo.ui.theme.LocalLiteVisuals
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cinematic mesh backdrop — soft theater light, not kids-app blobs.
 * Uses the active screen chrome accent so video/audio stay distinct.
 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val chrome = LocalScreenChrome.current
    val liteVisuals = LocalLiteVisuals.current
    if (liteVisuals) {
        // Static soft wash — no Canvas loop on weak devices.
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            chrome.accent.copy(alpha = 0.04f * intensity),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        return
    }
    val infinite = rememberInfiniteTransition(label = "ambient")
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.AmbientDriftMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.AmbientPulseMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val t = drift * 2f * PI.toFloat()

        // Larger, slower orbs — light spill in a dark theater
        val orb1 = Offset(
            x = w * (0.18f + 0.05f * cos(t)),
            y = h * (0.22f + 0.04f * sin(t * 0.85f)),
        )
        val orb2 = Offset(
            x = w * (0.82f + 0.04f * sin(t * 0.75f)),
            y = h * (0.48f + 0.05f * cos(t * 1.05f)),
        )
        val orb3 = Offset(
            x = w * (0.52f + 0.06f * cos(t * 0.55f + 1.1f)),
            y = h * (0.82f + 0.035f * sin(t * 0.65f)),
        )
        val orb4 = Offset(
            x = w * (0.38f + 0.03f * sin(t * 0.45f + 0.4f)),
            y = h * (0.38f + 0.03f * cos(t * 0.5f)),
        )

        val a = (0.055f * intensity * pulse).coerceIn(0f, 0.12f)
        val a2 = (0.038f * intensity * pulse).coerceIn(0f, 0.09f)
        val a3 = (0.028f * intensity * pulse).coerceIn(0f, 0.07f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(chrome.accent.copy(alpha = a), Color.Transparent),
                center = orb1,
                radius = w * 0.58f,
            ),
            radius = w * 0.58f,
            center = orb1,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(chrome.accentSecondary.copy(alpha = a2), Color.Transparent),
                center = orb2,
                radius = w * 0.52f,
            ),
            radius = w * 0.52f,
            center = orb2,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(chrome.accent.copy(alpha = a2 * 0.7f), Color.Transparent),
                center = orb3,
                radius = w * 0.48f,
            ),
            radius = w * 0.48f,
            center = orb3,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = a3 * 0.45f), Color.Transparent),
                center = orb4,
                radius = w * 0.32f,
            ),
            radius = w * 0.32f,
            center = orb4,
        )

        // Soft vignette — theater edges
        val vignetteAlpha = (0.42f * intensity).coerceIn(0f, 0.55f)
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.82f to Color.Black.copy(alpha = vignetteAlpha * 0.35f),
                    1.0f to Color.Black.copy(alpha = vignetteAlpha),
                ),
                center = Offset(w * 0.5f, h * 0.48f),
                radius = maxOf(w, h) * 0.78f,
            ),
        )
    }
}
