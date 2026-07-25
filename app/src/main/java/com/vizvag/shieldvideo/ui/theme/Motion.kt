package com.vizvag.shieldvideo.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared motion language — cinematic enters, snappy focus, shared-element feel.
 * Springs for focus/selection; longer tweens for entrances and route changes.
 */
object Motion {
    /** Focus / selection — snappy, low bounce */
    fun <T> focusSpring() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = 900f,
    )

    /** Slightly softer for larger surfaces (cards) — still responsive */
    fun <T> cardSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Screen / overlay enter — cinematic */
    fun <T> enter(): TweenSpec<T> = tween(560, easing = EmphasizedDecelerate)

    /** Screen / overlay exit — quicker than enter */
    fun <T> exit(): TweenSpec<T> = tween(320, easing = EmphasizedAccelerate)

    /** Shared-element style settle */
    fun <T> sharedElement(): TweenSpec<T> = tween(480, easing = EmphasizedDecelerate)

    /** Stagger spacing between list items (ms) */
    const val StaggerMs = 42

    /** Ambient loop periods — slow theater drift */
    const val AmbientDriftMs = 18_000
    const val AmbientPulseMs = 7_200

    /** Material 3 emphasized easing approximations */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = FastOutSlowInEasing
}

/** Soft scale + fade for staggered list entrances. */
@Composable
fun Modifier.staggeredEntrance(
    visible: Boolean,
    index: Int,
    maxIndexForDelay: Int = 12,
): Modifier {
    val delay = (index.coerceAtMost(maxIndexForDelay) * Motion.StaggerMs)
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 520,
            delayMillis = delay,
            easing = Motion.EmphasizedDecelerate,
        ),
        label = "staggerAlpha",
    )
    val translate by animateFloatAsState(
        targetValue = if (visible) 0f else 22f,
        animationSpec = tween(
            durationMillis = 560,
            delayMillis = delay,
            easing = Motion.EmphasizedDecelerate,
        ),
        label = "staggerY",
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.97f,
        animationSpec = tween(
            durationMillis = 560,
            delayMillis = delay,
            easing = Motion.EmphasizedDecelerate,
        ),
        label = "staggerScale",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        translationY = translate
        scaleX = scale
        scaleY = scale
    }
}
