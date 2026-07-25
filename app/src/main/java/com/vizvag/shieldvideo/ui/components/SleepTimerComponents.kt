package com.vizvag.shieldvideo.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome

@Composable
fun SleepTimerButton(
    active: Boolean,
    label: String?,
    onCycle: () -> Unit,
    enabled: Boolean = true,
) {
    val chrome = LocalScreenChrome.current
    if (!label.isNullOrBlank()) {
        Text(
            text = label,
            color = if (active) chrome.accent else chrome.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
    IconActionButton(selected = active, onClick = onCycle, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = "Sleep timer",
            tint = if (active) chrome.accent else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}
