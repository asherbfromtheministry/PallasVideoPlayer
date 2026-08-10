package com.vizvag.shieldvideo.ui.settings

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
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
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

sealed class OpmlPick {
    data class Nas(val path: String) : OpmlPick()
    data class Local(val absolutePath: String) : OpmlPick()
}

private sealed class BrowseRoot {
    data object Choice : BrowseRoot()
    data object Nas : BrowseRoot()
    data object Local : BrowseRoot()
}

private data class BrowseRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val isFile: Boolean,
    val onOpen: () -> Unit,
)

/**
 * TV dialog to pick a Podcast OPML from the NAS or this device's Downloads.
 * OK on a folder navigates; OK on a `.opml` file confirms.
 */
@Composable
fun OpmlFilePickerDialog(
    settings: AppSettings,
    nasRepository: NasRepository,
    initialNasPath: String = "",
    onDismiss: () -> Unit,
    onPick: (OpmlPick) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var root by remember { mutableStateOf<BrowseRoot>(BrowseRoot.Choice) }

    // NAS navigation
    var atShareRoot by remember { mutableStateOf(true) }
    var shareName by remember { mutableStateOf<String?>(null) }
    var nasStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var nasShares by remember { mutableStateOf<List<String>>(emptyList()) }
    var nasFolders by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var nasFiles by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }

    // Local navigation
    var localDir by remember { mutableStateOf<File?>(null) }
    var localFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var localFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var localRoots by remember { mutableStateOf<List<File>>(emptyList()) }

    val listState = rememberLazyListState()
    val firstRowFocus = remember { FocusRequester() }

    val currentPathLabel = when (root) {
        BrowseRoot.Choice -> "Choose a location"
        BrowseRoot.Nas -> when {
            atShareRoot -> "NAS"
            else -> NasPaths.toFolderPath(shareName.orEmpty(), nasStack.joinToString("/"))
        }
        BrowseRoot.Local -> localDir?.absolutePath ?: "On this device"
    }

    LaunchedEffect(Unit) {
        localRoots = localDownloadRoots(context)
        val initial = initialNasPath.trim().replace('\\', '/')
        if (initial.isBlank() || !initial.startsWith("/")) return@LaunchedEffect
        val parts = initial.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return@LaunchedEffect
        root = BrowseRoot.Nas
        shareName = parts.first()
        nasStack = parts.drop(1).dropLast(1)
        atShareRoot = false
    }

    LaunchedEffect(root, atShareRoot, shareName, nasStack, settings.host, settings.password) {
        if (root != BrowseRoot.Nas) return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            if (atShareRoot) {
                nasRepository.listShares(settings).map { list ->
                    list.map { SmbEntry(name = it, path = it, isDirectory = true) }
                }
            } else {
                nasRepository.list(
                    settings,
                    shareName.orEmpty(),
                    nasStack.joinToString("/"),
                    allowedExtensions = setOf("opml"),
                    hideEmptyFolders = false,
                )
            }
        }
        result.fold(
            onSuccess = { entries ->
                if (atShareRoot) {
                    nasShares = entries.map { it.name }
                    nasFolders = emptyList()
                    nasFiles = emptyList()
                } else {
                    nasFolders = entries.filter { it.isDirectory }
                    nasFiles = entries.filter { !it.isDirectory }
                }
                loading = false
            },
            onFailure = { err ->
                error = err.message ?: "Unable to browse NAS"
                loading = false
            },
        )
    }

    LaunchedEffect(root, localDir) {
        if (root != BrowseRoot.Local) return@LaunchedEffect
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            if (localDir == null) {
                localFolders = localRoots.filter { it.isDirectory }
                localFiles = emptyList()
            } else {
                val children = localDir?.listFiles().orEmpty().toList()
                localFolders = children
                    .filter { it.isDirectory && !it.name.startsWith('.') }
                    .sortedBy { it.name.lowercase() }
                localFiles = children
                    .filter { it.isFile && it.name.endsWith(".opml", ignoreCase = true) }
                    .sortedByDescending { it.lastModified() }
            }
        }
        loading = false
    }

    LaunchedEffect(root, atShareRoot, shareName, nasStack, localDir, loading, nasShares, nasFolders, nasFiles, localFolders, localFiles) {
        if (loading || error != null) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(48)
        runCatching { firstRowFocus.requestFocus() }
    }

    fun goUp() {
        when (root) {
            BrowseRoot.Choice -> onDismiss()
            BrowseRoot.Nas -> {
                if (atShareRoot) {
                    root = BrowseRoot.Choice
                } else if (nasStack.isEmpty()) {
                    atShareRoot = true
                    shareName = null
                } else {
                    nasStack = nasStack.dropLast(1)
                }
            }
            BrowseRoot.Local -> {
                val current = localDir
                if (current == null) {
                    root = BrowseRoot.Choice
                } else {
                    val parent = current.parentFile
                    localDir = when {
                        localRoots.any { it.absolutePath.equals(current.absolutePath, ignoreCase = true) } -> null
                        parent != null && localRoots.any { rootDir ->
                            current.absolutePath.startsWith(rootDir.absolutePath, ignoreCase = true) &&
                                parent.absolutePath.startsWith(rootDir.absolutePath, ignoreCase = true)
                        } -> parent
                        else -> null
                    }
                }
            }
        }
    }

    val rows: List<BrowseRow> = when (root) {
        BrowseRoot.Choice -> listOf(
            BrowseRow(
                key = "nas",
                title = "NAS",
                subtitle = "Browse Synology shares for an .opml file",
                isFile = false,
                onOpen = {
                    root = BrowseRoot.Nas
                    atShareRoot = true
                    shareName = null
                    nasStack = emptyList()
                },
            ),
            BrowseRow(
                key = "local",
                title = "On this device",
                subtitle = "Downloads and app folders",
                isFile = false,
                onOpen = {
                    root = BrowseRoot.Local
                    localDir = null
                },
            ),
        )
        BrowseRoot.Nas -> if (atShareRoot) {
            nasShares.map { share ->
                BrowseRow(
                    key = "share:$share",
                    title = share,
                    subtitle = NasPaths.toFolderPath(share),
                    isFile = false,
                    onOpen = {
                        shareName = share
                        nasStack = emptyList()
                        atShareRoot = false
                    },
                )
            }
        } else {
            nasFolders.map { folder ->
                BrowseRow(
                    key = "dir:${folder.path}",
                    title = folder.name,
                    subtitle = NasPaths.toFolderPath(shareName.orEmpty(), folder.path),
                    isFile = false,
                    onOpen = {
                        nasStack = folder.path.split('/').filter { it.isNotBlank() }
                    },
                )
            } + nasFiles.map { file ->
                val full = NasPaths.toFolderPath(shareName.orEmpty(), file.path)
                BrowseRow(
                    key = "file:$full",
                    title = "OPML · ${file.name}",
                    subtitle = full,
                    isFile = true,
                    onOpen = { onPick(OpmlPick.Nas(full)) },
                )
            }
        }
        BrowseRoot.Local -> if (localDir == null) {
            localFolders.map { dir ->
                BrowseRow(
                    key = "lroot:${dir.absolutePath}",
                    title = dir.name.ifBlank { dir.absolutePath },
                    subtitle = dir.absolutePath,
                    isFile = false,
                    onOpen = { localDir = dir },
                )
            }
        } else {
            localFolders.map { dir ->
                BrowseRow(
                    key = "ldir:${dir.absolutePath}",
                    title = dir.name,
                    subtitle = dir.absolutePath,
                    isFile = false,
                    onOpen = { localDir = dir },
                )
            } + localFiles.map { file ->
                BrowseRow(
                    key = "lfile:${file.absolutePath}",
                    title = "OPML · ${file.name}",
                    subtitle = file.absolutePath,
                    isFile = true,
                    onOpen = { onPick(OpmlPick.Local(file.absolutePath)) },
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2E342A))
                .border(1.dp, LocalScreenChrome.current.accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = "Import OPML",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentPathLabel,
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OK on a folder to open · OK on a .opml file to import",
                color = LocalScreenChrome.current.accent.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurface)
                    .padding(8.dp),
            ) {
                when {
                    loading -> Text(
                        "Loading…",
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    error != null -> Text(
                        error.orEmpty(),
                        color = Color(0xFFFF8A80),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    rows.isEmpty() -> Text(
                        "No folders or .opml files here",
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (root != BrowseRoot.Choice) {
                                item {
                                    OpmlPickerRow(
                                        title = "‥ Up",
                                        subtitle = "Go up one level",
                                        selected = false,
                                        focusRequester = firstRowFocus,
                                        onClick = { goUp() },
                                    )
                                }
                            }
                            items(rows, key = { it.key }) { row ->
                                val isFirst = root == BrowseRoot.Choice && row.key == rows.firstOrNull()?.key
                                OpmlPickerRow(
                                    title = row.title,
                                    subtitle = row.subtitle,
                                    selected = row.isFile,
                                    focusRequester = if (isFirst) firstRowFocus else null,
                                    onClick = row.onOpen,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OpmlPickerButton(label = "Cancel", onClick = onDismiss)
        }
    }
}

private fun localDownloadRoots(context: Context): List<File> {
    val candidates = listOfNotNull(
        File("/sdcard/Download"),
        File("/storage/emulated/0/Download"),
        context.getExternalFilesDir(null),
        context.filesDir,
    )
    return candidates
        .filter { it.exists() }
        .distinctBy { it.absolutePath.lowercase() }
}

@Composable
private fun OpmlPickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = selected)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OpmlPickerButton(
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .glassInteract(focused = focused, selected = false)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}
