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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.data.trakt.TraktMatch
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

@Composable
fun FolderAssignDialog(
    state: FolderAssignUi,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (TraktMatch) -> Unit,
    onKeepEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    val folder = state.folder ?: return
    val firstFocus = remember { FocusRequester() }
    val feedback = rememberTvFeedback()
    var showQueryEditor by remember { mutableStateOf(false) }

    BackHandler(onBack = {
        if (showQueryEditor) showQueryEditor = false else onDismiss()
    })

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
                text = "Pick a match, or edit the search (OK on the search row) and search again.",
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // TV-safe: not a TextField — OK opens the editor (keyboard only then).
                AssignSearchSummaryRow(
                    query = state.searchQuery,
                    enabled = !state.loading,
                    onEdit = {
                        feedback.click()
                        showQueryEditor = true
                    },
                    modifier = Modifier.weight(1f),
                )
                AssignChip(
                    label = if (state.loading) "…" else "Search",
                    enabled = !state.loading && state.searchQuery.trim().length >= 2,
                    onClick = {
                        feedback.click()
                        onSearch()
                    },
                    modifier = Modifier.width(120.dp),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = LocalScreenChrome.current.accent)
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
                        LaunchedEffect(state.error, state.searchQuery) {
                            delay(80)
                            runCatching { firstFocus.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    if (showQueryEditor) {
        AssignQueryEditorDialog(
            initialValue = state.searchQuery,
            onDismiss = { showQueryEditor = false },
            onConfirm = { value ->
                onQueryChange(value)
                showQueryEditor = false
                onSearch()
            },
        )
    }
}

@Composable
private fun AssignSearchSummaryRow(
    query: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    val label = query.ifBlank { "Enter a show or movie title…" }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .glassInteract(focused = focused && enabled, selected = false)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained && enabled) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    onEdit()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onEdit,
            )
            .focusable(enabled = enabled, interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "SEARCH",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.45f)
                query.isBlank() -> TextMuted.copy(alpha = 0.75f)
                else -> Color.White
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (focused && enabled) {
            Text(
                text = "OK to edit",
                color = LocalScreenChrome.current.accent.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun AssignQueryEditorDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141418))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = "Edit search",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = {
                    Text(
                        "Show or movie title…",
                        color = TextMuted.copy(alpha = 0.7f),
                        fontSize = 18.sp,
                    )
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onConfirm(draft) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalScreenChrome.current.accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = LocalScreenChrome.current.accent,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                ),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssignChip(
                    label = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                AssignChip(
                    label = "Search",
                    onClick = { onConfirm(draft) },
                    modifier = Modifier.weight(1f),
                )
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
            .glassInteract(focused = focused, selected = false)
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
                color = LocalScreenChrome.current.accent,
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
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(48.dp)
            .glassInteract(focused = focused && enabled, selected = false)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained && enabled) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
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
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(enabled = enabled, interactionSource = interaction)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                focused -> LocalScreenChrome.current.accent
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
