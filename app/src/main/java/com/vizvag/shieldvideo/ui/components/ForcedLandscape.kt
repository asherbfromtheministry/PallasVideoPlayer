package com.vizvag.shieldvideo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

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
        Box(modifier.fillMaxSize()) { content() }
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val portrait = maxHeight > maxWidth
        if (!portrait) {
            Box(Modifier.fillMaxSize()) { content() }
            return@BoxWithConstraints
        }
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
