package com.vizvag.shieldvideo.ui.notice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.PallasShapes
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.delay

/** Global glass notice toast — non-focusable so D-pad stays on the underlying UI. */
@Composable
fun BoxScope.AppNoticeHost(
    bottomInset: Dp = 28.dp,
) {
    val notice by AppNoticeBus.current.collectAsState()
    val chrome = LocalScreenChrome.current

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = bottomInset)
            .zIndex(80f)
            .focusProperties { canFocus = false },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(Motion.enter()) + slideInVertically(
                animationSpec = tween(420, easing = Motion.EmphasizedDecelerate),
                initialOffsetY = { it / 3 },
            ),
            exit = fadeOut(Motion.exit()) + slideOutVertically(
                animationSpec = tween(280, easing = Motion.EmphasizedAccelerate),
                targetOffsetY = { it / 4 },
            ),
        ) {
            val current = notice
            if (current != null) {
                key(current.id) {
                    LaunchedEffect(current.id, current.durationMs) {
                        if (current.durationMs > 0L) {
                            delay(current.durationMs)
                            AppNoticeBus.dismiss(current.id)
                        }
                    }
                    AppNoticeCard(
                        notice = current,
                        accent = chrome.accent,
                        accentWarm = chrome.accentWarm,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNoticeCard(
    notice: AppNotice,
    accent: Color,
    accentWarm: Color,
) {
    val tone = when (notice.kind) {
        AppNoticeKind.Success -> accent
        AppNoticeKind.Error -> accentWarm
        AppNoticeKind.Info -> Color(0xFF8AB4FF)
        AppNoticeKind.Progress -> accent.copy(alpha = 0.85f)
    }
    val label = when (notice.kind) {
        AppNoticeKind.Success -> "Success"
        AppNoticeKind.Error -> "Error"
        AppNoticeKind.Info -> "Notice"
        AppNoticeKind.Progress -> "Working"
    }
    val glyph = when (notice.kind) {
        AppNoticeKind.Success -> "✓"
        AppNoticeKind.Error -> "!"
        AppNoticeKind.Info -> "i"
        AppNoticeKind.Progress -> null
    }
    val shape = RoundedCornerShape(PallasShapes.panel)

    Row(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 640.dp)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black,
            )
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xF016161E),
                        Color(0xE012121A),
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tone),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = tone,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = glyph,
                    color = tone,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.uppercase(),
                    color = tone.copy(alpha = 0.95f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                notice.title?.let { title ->
                    Text(
                        text = "·  $title",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = notice.message,
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
            )
        }
    }
}
