package com.vizvag.shieldvideo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

@Composable
fun FolderClearOptionsDialog(
    folder: MediaCardItem,
    onClearIncludingContents: () -> Unit,
    onClearFolderOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val feedback = rememberTvFeedback()
    // Long-press OK release must not immediately activate the first option.
    var armed by remember { mutableStateOf(false) }

    LaunchedEffect(folder.entry.path) {
        armed = false
        delay(280)
        armed = true
        delay(40)
        runCatching { firstFocus.requestFocus() }
    }

    fun run(action: () -> Unit) {
        if (armed) action()
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.58f)
                .clip(RoundedCornerShape(20.dp))
                .background(AppBackground.copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Text(
                text = "Clear metadata",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "“${folder.displayTitle}”",
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ClearOptionRow(
                    label = "This folder and everything inside",
                    subtitle = "Clears nested subfolders and files too",
                    requestInitialFocus = true,
                    focusRequester = firstFocus,
                    onClick = {
                        feedback.click()
                        run(onClearIncludingContents)
                    },
                )
                ClearOptionRow(
                    label = "This folder only",
                    subtitle = "Nested folders and files keep their metadata",
                    onClick = {
                        feedback.click()
                        run(onClearFolderOnly)
                    },
                )
                ClearOptionRow(
                    label = "Cancel",
                    onClick = {
                        feedback.click()
                        run(onDismiss)
                    },
                )
            }
        }
    }
}

@Composable
private fun ClearOptionRow(
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val localFocus = remember { FocusRequester() }
    val requester = focusRequester ?: localFocus
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            delay(60)
            runCatching { requester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = false, idleSurface = Color.White.copy(alpha = 0.04f))
            .focusRequester(requester)
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
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (focused) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "OK",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
