package com.vizvag.shieldvideo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.StatusPane
import com.vizvag.shieldvideo.ui.notice.ForwardFlashNotice
import com.vizvag.shieldvideo.ui.theme.CyanAccent
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenSettings: () -> Unit,
    onOpenLiveTv: () -> Unit = {},
    onOpenYouTube: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val folderAssign by viewModel.folderAssign.collectAsState()
    val folderClearOptions by viewModel.folderClearOptions.collectAsState()
    val itemOptions by viewModel.itemOptions.collectAsState()
    val archiveExtract by viewModel.archiveExtract.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAccessHint by remember { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }
    val browseLocation = remember(state.selectedShare, state.pathStack, state.flatView) {
        Triple(state.selectedShare, state.pathStack, state.flatView)
    }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.vizvag.shieldvideo.ShieldVideoApp
    val sleepState by app.sleepTimer.state.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onBrowserResumed()
                BrowseNavRequests.takeShare()?.let { viewModel.selectShare(it) }
                if (BrowseNavRequests.takeOpenSearch()) viewModel.openSearch()
                showAccessHint = viewModel.needsNotificationAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.pathStack.isNotEmpty() && !state.searchOpen) {
        viewModel.goUp()
    }

    // Request focus after the list has scrolled so the focused row (not index 0) owns FocusRequester.
    LaunchedEffect(browseLocation, state.loading, state.focusedPath) {
        if (state.loading || state.searchOpen || state.items.isEmpty() || state.focusedPath == null) {
            return@LaunchedEffect
        }
        delay(64)
        runCatching { listFocus.requestFocus() }
    }

    val orderedShares = rememberOrderedShares(state.settings)

    val shareLabel = NasPaths.labelFor(state.selectedShare)
    val showExtractMini = archiveExtract.running && archiveExtract.minimized && archiveExtract.item != null
    val showExtractDialog = archiveExtract.item != null && !archiveExtract.minimized

    fun onItemClick(item: MediaCardItem) {
        if (item.entry.isDirectory) {
            viewModel.openFolderEntry(item.entry)
        } else {
            if (viewModel.needsNotificationAccess()) {
                showAccessHint = true
            }
            viewModel.play(item)
        }
    }

    fun onItemLongClick(item: MediaCardItem) {
        viewModel.openItemOptions(item)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showExtractMini) {
            ExtractMiniProgressBar(
                state = archiveExtract,
                onExpand = viewModel::expandArchiveExtract,
            )
        }
        Row(modifier = Modifier.weight(1f).fillMaxSize()) {
        BrowserNavRail(
            shares = orderedShares,
            selectedShare = state.selectedShare,
            onSelectShare = viewModel::selectShare,
            recordingFolder = recordingFolderForRail(state.settings),
            onLiveTv = onOpenLiveTv,
            onYouTube = onOpenYouTube,
            onRadio = onOpenRadio,
            onMusic = onOpenMusic,
            onPodcasts = onOpenPodcasts,
            sleepTimerActive = sleepState.active,
            sleepTimerLabel = sleepState.label,
            onCycleSleepTimer = app.sleepTimer::cycle,
            onSettings = onOpenSettings,
            destination = RailDestination.Browser,
            players = RailPlayerVisibility.from(state.settings),
        )

        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (showAccessHint) {
                Text(
                    text = "Enable resume tracking (Settings → Playback) so Continue Watching updates after VLC.",
                    color = CyanAccent.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading || state.error != null || state.items.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            StatusPane(
                                loading = state.loading,
                                error = state.error,
                                empty = !state.loading &&
                                    state.error == null &&
                                    state.items.isEmpty(),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                            ) {
                                val chrome = LocalScreenChrome.current
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconActionButton(
                                        selected = state.indexBuilding,
                                        onClick = { viewModel.refreshLibrary() },
                                        enabled = !state.indexBuilding,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = if (state.indexBuilding) {
                                                "Refreshing library"
                                            } else {
                                                "Refresh library"
                                            },
                                            tint = chrome.accent,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    IconActionButton(
                                        selected = false,
                                        onClick = { viewModel.openSearch() },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = "Search videos",
                                            tint = chrome.accent,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val folderTitle = state.pathStack.lastOrNull() ?: shareLabel
                        val cleaned = folderTitle.replace('.', ' ').replace('_', ' ').trim()
                            .ifBlank { folderTitle }
                        val listTitle = if (state.flatView) {
                            "$cleaned · all videos"
                        } else {
                            cleaned
                        }
                        val focused = state.focusedPath?.let { path ->
                            state.items.find { it.entry.path.equals(path, ignoreCase = true) }
                                ?: state.items.find {
                                    it.entry.path.replace('\\', '/').trim('/')
                                        .equals(path.replace('\\', '/').trim('/'), ignoreCase = true)
                                }
                        }
                        SplitFolderBrowser(
                            title = listTitle,
                            itemCount = state.items.size,
                            items = state.items,
                            focused = focused,
                            onFocusedChange = { viewModel.setFocusedPath(it.entry.path) },
                            onClick = ::onItemClick,
                            onLongClick = ::onItemLongClick,
                            onPlay = { item -> onItemClick(item) },
                            onSearch = viewModel::openSearch,
                            onRefresh = viewModel::refreshLibrary,
                            refreshing = state.indexBuilding,
                            listFocusRequester = listFocus,
                        )
                    }
                }

                val toastMessage = state.message?.takeIf { msg ->
                    msg.contains("metadata", ignoreCase = true) ||
                        msg.startsWith("Extracted ", ignoreCase = true) ||
                        msg.contains("Extract already", ignoreCase = true) ||
                        msg.startsWith("Deleted ", ignoreCase = true) ||
                        msg.startsWith("Delete failed", ignoreCase = true) ||
                        msg.contains("Synced", ignoreCase = true) ||
                        msg.contains("Refreshing library", ignoreCase = true) ||
                        msg.contains("Library refreshed", ignoreCase = true) ||
                        msg.contains("Library refresh", ignoreCase = true) ||
                        msg.contains("Video Station", ignoreCase = true) ||
                        msg.contains("Indexing", ignoreCase = true)
                }
                ForwardFlashNotice(
                    message = toastMessage,
                    title = "Library",
                    onConsumed = viewModel::dismissMessage,
                )
            }
        }
        }
    }

    val blockingMessage = state.message?.takeUnless { msg ->
        msg.contains("metadata", ignoreCase = true) ||
            msg.startsWith("Extracted ", ignoreCase = true) ||
            msg.contains("Extract already", ignoreCase = true) ||
            msg.contains("Synced", ignoreCase = true) ||
            msg.contains("Refreshing library", ignoreCase = true) ||
            msg.contains("Library refreshed", ignoreCase = true) ||
            msg.contains("Library refresh", ignoreCase = true) ||
            msg.contains("Video Station", ignoreCase = true) ||
            msg.contains("Indexing", ignoreCase = true) ||
            msg.contains("Scanning", ignoreCase = true)
    }
    if (state.showVlcMissing || blockingMessage != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = {
                Text(
                    text = if (state.showVlcMissing) "Player required" else "Playback",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = blockingMessage
                        ?: "Install a video player or choose another one in Settings.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) {
                    Text("OK", color = CyanAccent)
                }
            },
            containerColor = Color(0xFF2E342A)
        )
    }

    if (state.searchOpen) {
        SearchOverlay(
            state = state,
            onClose = viewModel::closeSearch,
            onQueryChange = viewModel::setSearchQuery,
            onToggleFolder = viewModel::toggleSearchFolder,
            onSelectAllFolders = viewModel::selectAllSearchFolders,
            onSetUseIndex = viewModel::setSearchUseIndex,
            onRebuildIndex = viewModel::rebuildVideoIndex,
            onSearch = viewModel::runSearch,
            onOpenResult = viewModel::openSearchResult,
            onLongClickResult = { item -> viewModel.openItemOptions(item) }
        )
    }

    itemOptions.item?.let { item ->
        BrowserItemOptionsDialog(
            item = item,
            confirmingDelete = itemOptions.confirmingDelete,
            onShowAllVideos = { viewModel.showAllVideosInFolder(item) },
            onAssignMetadata = { viewModel.openFolderAssign(item) },
            onClearIncludingContents = {
                viewModel.clearItemMetadata(item, includeDescendants = true)
            },
            onClearFolderOnly = {
                viewModel.clearItemMetadata(item, includeDescendants = false)
            },
            onClearOrRestoreMetadata = {
                if (item.metadataCleared) viewModel.restoreItemMetadata(item)
                else viewModel.clearItemMetadata(item)
            },
            onExtractArchive = { viewModel.openArchiveExtract(item) },
            onRequestDelete = viewModel::requestDeleteFromOptions,
            onConfirmDelete = viewModel::confirmDeleteItem,
            onCancelDelete = viewModel::cancelDeleteFromOptions,
            onDismiss = viewModel::dismissItemOptions,
        )
    }

    folderClearOptions.folder?.let { folder ->
        FolderClearOptionsDialog(
            folder = folder,
            onClearIncludingContents = {
                viewModel.clearItemMetadata(folder, includeDescendants = true)
            },
            onClearFolderOnly = {
                viewModel.clearItemMetadata(folder, includeDescendants = false)
            },
            onDismiss = viewModel::dismissFolderClearOptions,
        )
    }

    if (folderAssign.folder != null) {
        FolderAssignDialog(
            state = folderAssign,
            onQueryChange = viewModel::setFolderAssignQuery,
            onSearch = viewModel::searchFolderAssign,
            onSelect = viewModel::assignFolderMetadata,
            onKeepEmpty = viewModel::keepFolderEmpty,
            onDismiss = viewModel::dismissFolderAssign,
        )
    }

    if (showExtractDialog) {
        ArchiveExtractDialog(
            state = archiveExtract,
            onExtract = viewModel::confirmArchiveExtract,
            onDismiss = viewModel::dismissArchiveExtract,
            onMinimize = viewModel::minimizeArchiveExtract,
        )
    }
}
