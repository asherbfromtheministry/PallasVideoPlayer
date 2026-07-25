package com.vizvag.shieldvideo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.data.settings.ClockCorner
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BoxScope.AppClockOverlay(
    corner: ClockCorner,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    val dateFmt = remember {
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
    }
    val align = when (corner) {
        ClockCorner.BottomRight -> Alignment.BottomEnd
        ClockCorner.BottomLeft -> Alignment.BottomStart
        ClockCorner.TopRight -> Alignment.TopEnd
        ClockCorner.TopLeft -> Alignment.TopStart
    }
    val textAlign = when (corner) {
        ClockCorner.BottomRight, ClockCorner.TopRight -> TextAlign.End
        ClockCorner.BottomLeft, ClockCorner.TopLeft -> TextAlign.Start
    }
    Column(
        modifier = modifier
            .align(align)
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .focusProperties { canFocus = false },
        horizontalAlignment = when (corner) {
            ClockCorner.BottomRight, ClockCorner.TopRight -> Alignment.End
            ClockCorner.BottomLeft, ClockCorner.TopLeft -> Alignment.Start
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = timeFmt.format(Date(nowMs)),
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = PallasFontFamily,
            letterSpacing = 0.8.sp,
            textAlign = textAlign,
            maxLines = 1,
        )
        Text(
            text = dateFmt.format(Date(nowMs)),
            color = Color.White.copy(alpha = 0.28f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = PallasFontFamily,
            letterSpacing = 0.3.sp,
            textAlign = textAlign,
            maxLines = 1,
        )
    }
}
