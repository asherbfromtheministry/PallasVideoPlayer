package com.vizvag.shieldvideo.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs

/**
 * True while [ForcedLandscape] is applying a 90° Compose rotation (portrait window).
 * Touch axes are swapped relative to what users expect — scroll helpers should remap.
 */
val LocalForcedLandscapeRotated = compositionLocalOf { false }

/**
 * Tablets (esp. Samsung) ignore [android:screenOrientation] on large screens.
 * When the window is portrait, measure a landscape stage and rotate it 90° so the
 * UI stays landscape — including hit-testing via [placeWithLayer].
 */
@Composable
fun ForcedLandscape(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        CompositionLocalProvider(LocalForcedLandscapeRotated provides false) {
            Box(modifier.fillMaxSize()) { content() }
        }
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val portrait = maxHeight > maxWidth
        if (!portrait) {
            CompositionLocalProvider(LocalForcedLandscapeRotated provides false) {
                Box(Modifier.fillMaxSize()) { content() }
            }
            return@BoxWithConstraints
        }
        CompositionLocalProvider(LocalForcedLandscapeRotated provides true) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val stageW = constraints.maxHeight
                        val stageH = constraints.maxWidth
                        val placeable = measurable.measure(
                            Constraints(
                                minWidth = stageW,
                                maxWidth = stageW,
                                minHeight = stageH,
                                maxHeight = stageH,
                            ),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.placeRelativeWithLayer(
                                x = (constraints.maxWidth - placeable.width) / 2,
                                y = (constraints.maxHeight - placeable.height) / 2,
                            ) {
                                rotationZ = 90f
                            }
                        }
                    },
            ) {
                content()
            }
        }
    }
}

/**
 * Vertical scroll that still works under [ForcedLandscape] rotation and with
 * clickable children. Uses [PointerEventPass.Initial] so drags are not stolen by
 * rail item `clickable` handlers; when rotated, physical vertical motion (local X
 * after the 90° transform) drives the scroll.
 */
fun Modifier.touchFriendlyVerticalScroll(scrollState: ScrollState, rotated: Boolean): Modifier {
    return this
        .verticalScroll(scrollState, enabled = false)
        .pointerInput(scrollState, rotated) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                var dragged = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val deltaPos = change.positionChange()
                    val delta = if (rotated) {
                        if (abs(deltaPos.x) >= abs(deltaPos.y)) -deltaPos.x else -deltaPos.y
                    } else {
                        -deltaPos.y
                    }
                    if (abs(delta) > 0.5f) {
                        dragged = true
                        scrollState.dispatchRawDelta(delta)
                        change.consume()
                    }
                }
                // If we never dragged, leave the down unconsumed so clickable still fires.
                if (!dragged) {
                    // no-op: down was requireUnconsumed=false and we didn't consume it
                }
            }
        }
}
