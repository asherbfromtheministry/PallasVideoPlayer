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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.vizvag.shieldvideo.data.trakt.TraktMatch
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

@Composable
fun FolderAssignDialog(
    state: FolderAssignUi,
    onSelect: (TraktMatch) -> Unit,
    onKeepEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    val folder = state.folder ?: return
    val firstFocus = remember { FocusRequester() }
    val feedback = rememberTvFeedback()

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(AppBackground.copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Text(
                text = "Assign show or movie",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Choose metadata for “${folder.displayTitle}”, or keep it empty.",
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(18.dp))

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Accent)
                    }
                    LaunchedEffect(Unit) {
                        delay(80)
                        runCatching { firstFocus.requestFocus() }
                    }
                    AssignActionRow(
                        keepEmptyFocus = firstFocus,
                        onKeepEmpty = {
                            feedback.click()
                            onKeepEmpty()
                        },
                        onDismiss = {
                            feedback.click()
                            onDismiss()
                        },
                    )
                }
                else -> {
                    if (state.error != null) {
                        Text(
                            text = state.error,
                            color = TextMuted,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            state.candidates,
                            key = { _, match -> "${match.mediaType}:${match.tmdbId}" },
                        ) { index, match ->
                            AssignCandidateRow(
                                match = match,
                                requestInitialFocus = index == 0,
                                onClick = {
                                    feedback.click()
                                    onSelect(match)
                                },
                            )
                        }
                    }
                    AssignActionRow(
                        keepEmptyFocus = if (state.candidates.isEmpty()) firstFocus else null,
                        onKeepEmpty = {
                            feedback.click()
                            onKeepEmpty()
                        },
                        onDismiss = {
                            feedback.click()
                            onDismiss()
                        },
                    )
                    if (state.candidates.isEmpty()) {
                        LaunchedEffect(state.error) {
                            delay(80)
                            runCatching { firstFocus.requestFocus() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignActionRow(
    keepEmptyFocus: FocusRequester?,
    onKeepEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AssignChip(
            label = "Keep empty",
            focusRequester = keepEmptyFocus,
            onClick = onKeepEmpty,
            modifier = Modifier.weight(1f),
        )
        AssignChip(
            label = "Cancel",
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AssignCandidateRow(
    match: TraktMatch,
    requestInitialFocus: Boolean,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    val kind = when (match.mediaType) {
        "movie" -> "Movie"
        else -> "TV show"
    }
    val year = match.year?.toString().orEmpty()

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Accent else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
            )
            .focusRequester(focusRequester)
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
                text = match.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = listOf(kind, year).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextMuted,
                fontSize = 13.sp,
            )
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

@Composable
private fun AssignChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Accent else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
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
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Accent else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
