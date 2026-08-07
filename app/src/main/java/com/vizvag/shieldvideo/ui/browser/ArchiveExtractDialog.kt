package com.vizvag.shieldvideo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

@Composable
fun ArchiveExtractDialog(
    state: ArchiveExtractUi,
    onExtract: () -> Unit,
    onDismiss: () -> Unit,
    onMinimize: () -> Unit,
) {
    val item = state.item ?: return
    val firstFocus = remember { FocusRequester() }
    val hideFocus = remember { FocusRequester() }
    val feedback = rememberTvFeedback()
    BackHandler(onBack = {
        if (state.running) onMinimize() else onDismiss()
    })
    Dialog(
        onDismissRequest = {
            if (state.running) onMinimize() else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .clip(RoundedCornerShape(16.dp))
                .background(AppBackground.copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.entry.name,
                color = TextCream,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                state.running -> {
                    Text(state.status.ifBlank { "Extracting…" }, color = TextMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ArchiveActionRow(
                        title = "Hide",
                        subtitle = "Keeps extracting on the NAS",
                        focusRequester = hideFocus,
                        preferred = true,
                    ) {
                        feedback.click()
                        onMinimize()
                    }
                }
                state.error != null -> {
                    Text(state.error, color = Color(0xFFFF8A80), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    ArchiveActionRow("Retry extract", "Same folder · keeps the .rar", firstFocus, true) {
                        feedback.click(); onExtract()
                    }
                    ArchiveActionRow("Close", null, null, false) {
                        feedback.click(); onDismiss()
                    }
                }
                else -> {
                    Text(
                        "Extract into this folder (keeps the .rar)",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ArchiveActionRow("Extract", "Files land next to the archive", firstFocus, true) {
                        feedback.click(); onExtract()
                    }
                    ArchiveActionRow("Cancel", null, null, false) {
                        feedback.click(); onDismiss()
                    }
                }
            }
        }
    }
    LaunchedEffect(state.error, state.confirming, state.running) {
        delay(60)
        val target = if (state.running) hideFocus else firstFocus
        runCatching { target.requestFocus() }
    }
}

/** Slim top strip while extract runs in the background. OK expands the dialog. */
@Composable
fun ExtractMiniProgressBar(
    state: ArchiveExtractUi,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    val pct = (state.progress.coerceIn(0f, 1f) * 100).toInt()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) Accent else Color.Transparent,
            )
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    feedback.click()
                    onExpand()
                    true
                } else false
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onExpand,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Extracting ${item.entry.name}",
                color = if (focused) Accent else TextCream.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$pct%  ·  OK to show",
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.progress.coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .background(Accent),
            )
        }
    }
}

@Composable
private fun ArchiveActionRow(
    title: String,
    subtitle: String?,
    focusRequester: FocusRequester?,
    preferred: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(
                focused = focused,
                selected = preferred,
                idleSurface = Color.White.copy(alpha = 0.03f),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    feedback.click()
                    onClick()
                    true
                } else false
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, color = if (focused) Accent else TextCream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
    }
}
