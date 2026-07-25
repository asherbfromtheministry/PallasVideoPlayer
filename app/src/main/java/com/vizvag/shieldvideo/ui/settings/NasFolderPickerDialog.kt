package com.vizvag.shieldvideo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.smb.SmbEntry
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.CyanAccent
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class FolderPickerMode {
    VIDEO_FOLDERS,
    MUSIC_FOLDERS,
    BACKGROUND_FOLDER,
    BACKUP_FOLDER,
    IPTV_RECORDING_FOLDER
}

@Composable
fun NasFolderPickerDialog(
    mode: FolderPickerMode,
    settings: AppSettings,
    nasRepository: NasRepository,
    initialSelection: List<String>,
    title: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var atShareRoot by remember { mutableStateOf(true) }
    var shareName by remember { mutableStateOf<String?>(null) }
    var pathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var folders by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var selected by remember {
        mutableStateOf(
            initialSelection
                .map { normalizePath(it) }
                .filter { it.isNotBlank() }
                .toMutableSet()
        )
    }
    val listState = rememberLazyListState()
    val firstRowFocus = remember { FocusRequester() }

    val currentPath = when {
        atShareRoot -> "/"
        else -> NasPaths.toFolderPath(shareName.orEmpty(), pathStack.joinToString("/"))
    }

    fun reload() {
        loading = true
        error = null
    }

    LaunchedEffect(atShareRoot, shareName, pathStack, settings.host, settings.password) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            if (atShareRoot) {
                nasRepository.listShares(settings).map { list ->
                    list.map { SmbEntry(name = it, path = it, isDirectory = true) }
                }
            } else {
                nasRepository.listDirectories(
                    settings,
                    shareName.orEmpty(),
                    pathStack.joinToString("/")
                )
            }
        }
        result.fold(
            onSuccess = { entries ->
                if (atShareRoot) {
                    shares = entries.map { it.name }
                    folders = emptyList()
                } else {
                    folders = entries
                }
                loading = false
            },
            onFailure = { err ->
                error = err.message ?: "Unable to list folders"
                loading = false
            }
        )
    }

    // Always land on the first row of the current folder — not a previously selected share
    // such as Download that may appear later in the list.
    LaunchedEffect(atShareRoot, shareName, pathStack, loading, shares, folders) {
        if (loading || error != null) return@LaunchedEffect
        val hasRows = if (atShareRoot) shares.isNotEmpty() else true
        if (!hasRows) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(48)
        runCatching { firstRowFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2E342A))
                .border(1.dp, CyanAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                text = title ?: when (mode) {
                    FolderPickerMode.VIDEO_FOLDERS -> "Select video folders"
                    FolderPickerMode.MUSIC_FOLDERS -> "Select music folders"
                    FolderPickerMode.BACKGROUND_FOLDER -> "Select background folder"
                    FolderPickerMode.BACKUP_FOLDER -> "Select settings backup folder"
                    FolderPickerMode.IPTV_RECORDING_FOLDER -> "Select IPTV recording folder"
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentPath,
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (mode == FolderPickerMode.VIDEO_FOLDERS || mode == FolderPickerMode.MUSIC_FOLDERS) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Selected: " + selected.sorted().joinToString(", ").ifBlank { "none" },
                    color = CyanAccent.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurface)
                    .padding(8.dp)
            ) {
                when {
                    loading -> Text("Loading…", color = TextMuted, modifier = Modifier.align(Alignment.Center))
                    error != null -> Text(error.orEmpty(), color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center))
                    else -> {
                        val rows = if (atShareRoot) {
                            shares.map { share ->
                                FolderRow(
                                    title = share,
                                    path = NasPaths.toFolderPath(share),
                                    isShareRootItem = true
                                )
                            }
                        } else {
                            folders.map { folder ->
                                FolderRow(
                                    title = folder.name,
                                    path = NasPaths.toFolderPath(shareName.orEmpty(), folder.path),
                                    isShareRootItem = false,
                                    entry = folder
                                )
                            }
                        }
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!atShareRoot) {
                                item {
                                    PickerRow(
                                        title = "‥ Up",
                                        subtitle = "Go up one level",
                                        selected = false,
                                        focusRequester = firstRowFocus,
                                        onClick = {
                                            if (pathStack.isEmpty()) {
                                                atShareRoot = true
                                                shareName = null
                                            } else {
                                                pathStack = pathStack.dropLast(1)
                                            }
                                            reload()
                                        }
                                    )
                                }
                            }
                            items(rows, key = { it.path }) { row ->
                                val isSelected = selected.contains(normalizePath(row.path))
                                val isFirstFocusTarget = atShareRoot && row.path == rows.firstOrNull()?.path
                                PickerRow(
                                    title = row.title,
                                    subtitle = row.path,
                                    selected = isSelected,
                                    focusRequester = if (isFirstFocusTarget) firstRowFocus else null,
                                    onClick = {
                                        if (atShareRoot) {
                                            shareName = row.title
                                            pathStack = emptyList()
                                            atShareRoot = false
                                            reload()
                                        } else {
                                            val entry = row.entry ?: return@PickerRow
                                            pathStack = entry.path.split('/').filter { it.isNotBlank() }
                                            reload()
                                        }
                                    },
                                    onToggle = {
                                        val key = normalizePath(row.path)
                                        selected = selected.toMutableSet().also { set ->
                                            if (mode != FolderPickerMode.VIDEO_FOLDERS &&
                                                mode != FolderPickerMode.MUSIC_FOLDERS
                                            ) {
                                                set.clear()
                                                set += key
                                            } else {
                                                if (!set.add(key)) set.remove(key)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerButton(label = "Cancel", onClick = onDismiss)
                if (!atShareRoot) {
                    PickerButton(
                        label = if (mode != FolderPickerMode.VIDEO_FOLDERS &&
                            mode != FolderPickerMode.MUSIC_FOLDERS
                        ) {
                            "Use this folder"
                        } else {
                            "Add this folder"
                        },
                        onClick = {
                            val key = normalizePath(currentPath)
                            selected = selected.toMutableSet().also { set ->
                                if (mode != FolderPickerMode.VIDEO_FOLDERS &&
                                    mode != FolderPickerMode.MUSIC_FOLDERS
                                ) {
                                    set.clear()
                                    set += key
                                } else {
                                    set += key
                                }
                            }
                            if (mode != FolderPickerMode.VIDEO_FOLDERS &&
                                mode != FolderPickerMode.MUSIC_FOLDERS
                            ) {
                                onConfirm(selected.toList())
                            }
                        }
                    )
                }
                PickerButton(
                    label = "Done",
                    emphasized = true,
                    onClick = {
                        val result = when (mode) {
                            FolderPickerMode.BACKGROUND_FOLDER ->
                                selected.toList().ifEmpty {
                                    if (!atShareRoot) listOf(normalizePath(currentPath)) else emptyList()
                                }
                            FolderPickerMode.BACKUP_FOLDER ->
                                selected.toList().ifEmpty {
                                    if (!atShareRoot) listOf(normalizePath(currentPath)) else emptyList()
                                }
                            FolderPickerMode.IPTV_RECORDING_FOLDER ->
                                selected.toList().ifEmpty {
                                    if (!atShareRoot) listOf(normalizePath(currentPath)) else emptyList()
                                }
                            FolderPickerMode.VIDEO_FOLDERS,
                            FolderPickerMode.MUSIC_FOLDERS -> selected.toList().sorted()
                        }
                        onConfirm(result)
                    }
                )
            }
        }
    }
}

private data class FolderRow(
    val title: String,
    val path: String,
    val isShareRootItem: Boolean,
    val entry: SmbEntry? = null
)

private fun normalizePath(path: String): String {
    val trimmed = path.trim().replace('\\', '/')
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> CyanAccent.copy(alpha = 0.4f)
                    selected -> CyanAccent.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.04f)
                }
            )
            .border(
                width = if (focused || selected) 2.dp else 1.dp,
                color = when {
                    focused -> CyanAccent
                    selected -> CyanAccent.copy(alpha = 0.75f)
                    else -> Color.White.copy(alpha = 0.1f)
                },
                shape = RoundedCornerShape(10.dp)
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interaction)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onToggle != null) {
            Text(
                text = if (selected) "✓" else "Select",
                color = if (selected) CyanAccent else Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> CyanAccent
                    emphasized -> CyanAccent.copy(alpha = 0.4f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFFE8E2D4) else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp)
            )
            .focusable(interactionSource = interaction)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}
