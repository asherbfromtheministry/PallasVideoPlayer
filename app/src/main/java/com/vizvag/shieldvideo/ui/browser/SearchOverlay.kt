package com.vizvag.shieldvideo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.ui.components.HaStyleMediaCard
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.CyanAccent
import com.vizvag.shieldvideo.ui.theme.FocusRing
import com.vizvag.shieldvideo.ui.theme.TextMuted

@Composable
fun SearchOverlay(
    state: BrowserUiState,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onSelectAllFolders: () -> Unit,
    onSetUseIndex: (Boolean) -> Unit,
    onRebuildIndex: () -> Unit,
    onSearch: () -> Unit,
    onOpenResult: (MediaCardItem) -> Unit,
    onLongClickResult: (MediaCardItem) -> Unit
) {
    BackHandler(onBack = onClose)
    var showQueryEditor by remember { mutableStateOf(false) }
    var optionsExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(state.searchLoading, state.searchRan) {
        if (state.searchLoading || state.searchRan) {
            optionsExpanded = false
        }
    }

    val folderSummary = state.settings.shares
        .filter { share -> state.searchFolders.any { it.equals(share, ignoreCase = true) } }
        .joinToString(", ") { NasPaths.labelFor(it) }
        .ifBlank { "None" }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(20.dp))
                .background(AppBackground.copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Search",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.weight(1f)
                )
                if (!optionsExpanded && (state.searchRan || state.searchLoading)) {
                    SearchActionButton(
                        label = "Edit search",
                        onClick = { optionsExpanded = true },
                        compact = true
                    )
                }
                SearchActionButton(label = "Close", onClick = onClose, compact = true)
            }

            if (!optionsExpanded && (state.searchRan || state.searchLoading)) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "“${state.searchQuery}”  ·  $folderSummary  ·  " +
                        (if (state.searchUseIndex) "Index" else "Raw") +
                        if (state.searchResults.isNotEmpty()) "  ·  ${state.searchResults.size} results" else "",
                    color = TextMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (optionsExpanded) {
                Spacer(modifier = Modifier.height(18.dp))
                SearchSectionLabel("Query")
                Spacer(modifier = Modifier.height(8.dp))
                SearchGlassField(
                    label = state.searchQuery.ifBlank { "Search titles, folders, filenames…" },
                    muted = state.searchQuery.isBlank(),
                    onClick = { showQueryEditor = true }
                )

                Spacer(modifier = Modifier.height(18.dp))
                SearchSectionLabel("Search mode")
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchActionButton(
                        label = "Index (fast)",
                        onClick = { onSetUseIndex(true) },
                        selected = state.searchUseIndex,
                        compact = true
                    )
                    SearchActionButton(
                        label = "Raw scan",
                        onClick = { onSetUseIndex(false) },
                        selected = !state.searchUseIndex,
                        compact = true
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        state.indexBuilding -> "Building index… ${state.indexEntryCount} items"
                        state.indexEntryCount > 0 && state.indexBuiltAtMs > 0L -> {
                            val hours = ((System.currentTimeMillis() - state.indexBuiltAtMs) / 3_600_000L)
                                .coerceAtLeast(0)
                            "Index: ${state.indexEntryCount} items · " +
                                if (hours < 1) "updated <1h ago" else "updated ${hours}h ago"
                        }
                        else -> "Index empty — will build on first Index search"
                    },
                    color = TextMuted,
                    fontSize = 12.sp
                )
                if (state.searchUseIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SearchActionButton(
                        label = if (state.indexBuilding) "Indexing…" else "Rebuild index",
                        onClick = onRebuildIndex,
                        compact = true,
                        enabled = !state.indexBuilding
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SearchSectionLabel("Folders")
                    SearchActionButton(label = "All folders", onClick = onSelectAllFolders, compact = true)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(state.settings.shares, key = { it }) { folder ->
                        val selected = state.searchFolders.any { it.equals(folder, ignoreCase = true) }
                        SearchActionButton(
                            label = NasPaths.labelFor(folder),
                            onClick = { onToggleFolder(folder) },
                            selected = selected,
                            compact = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SearchActionButton(
                    label = if (state.searchLoading) "Searching…" else "Search",
                    onClick = {
                        optionsExpanded = false
                        onSearch()
                    },
                    fillWidth = true,
                    emphasized = true,
                    enabled = !state.searchLoading
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            ) {
                when {
                    state.searchLoading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Searching selected folders…", color = TextMuted)
                        }
                    }
                    state.searchError != null -> {
                        Text(
                            text = state.searchError.orEmpty(),
                            color = Color(0xFFFF8A80),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }
                    state.searchRan && state.searchResults.isEmpty() -> {
                        Text(
                            text = "No matches",
                            color = TextMuted,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    state.searchResults.isNotEmpty() -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            itemsIndexed(
                                state.searchResults,
                                key = { _, item -> "${item.videoRoot}/${item.entry.path}" }
                            ) { index, item ->
                                HaStyleMediaCard(
                                    item = item,
                                    entranceIndex = index,
                                    onClick = { onOpenResult(item) },
                                    onLongClick = {
                                        onLongClickResult(item)
                                    }
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = "Pick folders, enter a query, then Search",
                            color = TextMuted,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }

    if (showQueryEditor) {
        SearchQueryEditorDialog(
            initialValue = state.searchQuery,
            onDismiss = { showQueryEditor = false },
            onConfirm = { value ->
                onQueryChange(value)
                showQueryEditor = false
            }
        )
    }
}

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp
    )
}

@Composable
private fun SearchGlassField(
    label: String,
    muted: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.06f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FocusRing else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            )
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
            .focusable(true, interaction)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = if (muted) TextMuted.copy(alpha = 0.75f) else Color.White,
            fontSize = if (muted) 20.sp else 18.sp,
            fontWeight = if (muted) FontWeight.Medium else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchQueryEditorDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141418))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("Search for", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
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
                        "Titles, folders, filenames…",
                        color = TextMuted.copy(alpha = 0.7f),
                        fontSize = 18.sp
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onConfirm(draft) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FocusRing,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Accent,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchActionButton(label = "Cancel", onClick = onDismiss)
                SearchActionButton(
                    label = "Done",
                    onClick = { onConfirm(draft) },
                    emphasized = true
                )
            }
        }
    }
}

@Composable
private fun SearchActionButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    emphasized: Boolean = false,
    compact: Boolean = false,
    fillWidth: Boolean = false,
    muted: Boolean = false,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        !enabled -> Color.White.copy(alpha = 0.04f)
        focused && selected -> CyanAccent
        focused && !selected -> Color.White.copy(alpha = 0.18f)
        selected || emphasized -> CyanAccent.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val borderColor = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        focused -> Color.White
        selected || emphasized -> CyanAccent
        else -> Color.White.copy(alpha = 0.12f)
    }
    val textColor = when {
        !enabled -> TextMuted.copy(alpha = 0.45f)
        focused && selected -> Color(0xFF1A1C16)
        selected || emphasized -> Color(0xFFE8E2D4)
        muted || !selected -> TextMuted.copy(alpha = 0.7f)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = if (focused || selected || emphasized) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
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
            .focusable(enabled, interaction)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 12.dp
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = if (selected || emphasized) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
