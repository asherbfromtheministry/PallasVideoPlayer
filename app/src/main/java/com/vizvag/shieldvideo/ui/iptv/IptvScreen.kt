package com.vizvag.shieldvideo.ui.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.data.iptv.EpgChannelEntry
import com.vizvag.shieldvideo.data.iptv.GroupChannelOrder
import com.vizvag.shieldvideo.data.iptv.IptvChannel
import com.vizvag.shieldvideo.data.iptv.IptvNowNext
import com.vizvag.shieldvideo.data.iptv.IptvProgramme
import com.vizvag.shieldvideo.data.iptv.IptvRecording
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStatus
import com.vizvag.shieldvideo.data.iptv.XmltvParser
import com.vizvag.shieldvideo.data.settings.metrics
import com.vizvag.shieldvideo.playback.NasWatchHistoryEntry
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.RailPlayerVisibility
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.notice.ForwardFlashNotice
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun IptvScreen(
    viewModel: IptvViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMultiview: () -> Unit,
    onOpenBrowser: () -> Unit = onBack,
    onSelectShare: (String) -> Unit = {},
    onOpenRadio: () -> Unit = {},
    onOpenYouTube: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var pinInput by remember { mutableStateOf("") }
    var previewError by remember { mutableStateOf<String?>(null) }
    var browsingChannels by remember { mutableStateOf(false) }
    val guideFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val controllingRemote by com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.target.collectAsState()
    val remoteSession = controllingRemote != null
    val exoPlayer = rememberIptvExoPlayer()
    // Never decode IPTV on the tablet while controlling a room — stream runs on the TV.
    BindIptvStream(
        exoPlayer,
        state.previewChannel.takeUnless { remoteSession },
    ) { previewError = it }
    LaunchedEffect(remoteSession) {
        if (!remoteSession) return@LaunchedEffect
        runCatching {
            exoPlayer.volume = 0f
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.volume = 1f
        }
        if (state.fullscreen) viewModel.closeFullscreen()
    }
    // Tegra/Shield: never attach SurfaceView (fullscreen) in the same frame the TextureView
    // preview detaches — that MediaCodec handoff kernel-panics (`module_put` / `cdev_put`).
    var previewSurfaceAttached by remember { mutableStateOf(true) }
    var fullscreenSurfaceAttached by remember { mutableStateOf(false) }
    LaunchedEffect(state.fullscreen) {
        onFullscreenChanged(state.fullscreen)
        if (state.fullscreen) {
            focusManager.clearFocus(force = true)
            previewSurfaceAttached = false
            fullscreenSurfaceAttached = false
            delay(220)
            fullscreenSurfaceAttached = true
        } else {
            fullscreenSurfaceAttached = false
            previewSurfaceAttached = false
            delay(220)
            previewSurfaceAttached = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }
    // Shared by the preview HUD and the fullscreen HUD (one frame listener per player).
    val videoDetails = rememberLiveVideoDetails(exoPlayer, state.previewChannel?.id)
    val liveBadges = videoDetails.badges
    // Live measurement resets on every zap and needs ~1s of rendered frames; until it is
    // confirmed, keep showing badges measured the last time this channel played (if any).
    val cachedBadges = remember(state.previewChannel?.id) {
        state.previewChannel
            ?.takeIf { viewModel.badgesConfirmedFor(it) }
            ?.let { viewModel.badgesFor(it) }
            .orEmpty()
    }
    val streamBadges = liveBadges.ifEmpty { cachedBadges }
    val audioStatus = rememberAudioTrackStatus(exoPlayer, state.previewChannel?.id)
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val sleepState by app.sleepTimer.state.collectAsState()
    DisposableEffect(exoPlayer, remoteSession) {
        if (remoteSession) {
            onDispose { }
        } else {
            app.sleepTimer.bindPlayback(
                onStop = {
                    exoPlayer.pause()
                    viewModel.closeFullscreen()
                    browsingChannels = true
                }
            )
            onDispose { app.sleepTimer.unbindPlayback() }
        }
    }
    // Cache per channel once the measurement is complete (fps present ⇒ decoder output seen),
    // so guide/search rows can show real info for channels watched before.
    LaunchedEffect(liveBadges, state.previewChannel?.id) {
        val channelId = state.previewChannel?.id ?: return@LaunchedEffect
        if (liveBadges.any { it.endsWith("fps") }) {
            viewModel.reportStreamInfo(channelId, liveBadges)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSettings()
        viewModel.refreshNasWatchHistory()
    }
    LaunchedEffect(state.showRecordings) {
        while (state.showRecordings) {
            viewModel.refreshRecordings()
            delay(2_000)
        }
    }

    fun focusGuide() {
        runCatching { guideFocusRequester.requestFocus() }
    }

    fun returnToGroupCards() {
        browsingChannels = false
    }

    // Enter Live TV on the last channel in fullscreen; Back → channel list → groups → leave.
    // Remote session: start that channel on the TV — never tablet fullscreen/decode.
    var didAutoStartStream by remember { mutableStateOf(false) }
    LaunchedEffect(state.previewChannel?.id, remoteSession) {
        if (didAutoStartStream) return@LaunchedEffect
        if (state.previewChannel == null) return@LaunchedEffect
        didAutoStartStream = true
        browsingChannels = false
        if (remoteSession) {
            viewModel.selectChannel(state.previewChannel!!)
        } else {
            viewModel.openFullscreen()
        }
    }

    BackHandler {
        when {
            state.epgMatching || state.epgMatchLog.isNotEmpty() || state.epgMatchSummary != null ->
                viewModel.cancelEpgMatch()
            state.fullscreen -> {
                viewModel.closeFullscreen()
                browsingChannels = true
            }
            state.searchOpen -> viewModel.closeSearch()
            state.assignEpgChannel != null -> viewModel.closeAssignEpg()
            state.detailChannel != null -> viewModel.closeGuide()
            state.showRecordings -> viewModel.toggleRecordings()
            browsingChannels -> returnToGroupCards()
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val railShares = rememberOrderedShares(state.settings)
        val recordingFolder = remember(state.settings) { recordingFolderForRail(state.settings) }
        AppWithNavRail(
            destination = RailDestination.LiveTv,
            shares = railShares,
            selectedShare = state.settings.defaultShare,
            onSelectShare = onSelectShare,
            recordingFolder = recordingFolder,
            onLiveTv = {},
            onYouTube = onOpenYouTube,
            onRadio = onOpenRadio,
            onMusic = onOpenMusic,
            onPodcasts = onOpenPodcasts,
            sleepTimerActive = sleepState.active,
            sleepTimerLabel = sleepState.label,
            onCycleSleepTimer = app.sleepTimer::cycle,
            onSettings = onOpenSettings,
            showRail = !state.fullscreen,
            players = RailPlayerVisibility.from(state.settings),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IptvTopBar(
                playlistName = state.settings.activeIptvPlaylist().name,
                channelCount = state.rows.size,
                epgCount = state.epgChannelCount,
                refreshing = state.refreshing,
                onBack = onBack,
                onSearch = viewModel::openSearch,
                onRefresh = { viewModel.reload(force = true) },
                onMultiview = {
                    if (remoteSession) {
                        viewModel.showRemoteOnlyMessage("Multiview is only available on the TV")
                    } else {
                        onOpenMultiview()
                    }
                },
                onRecordings = viewModel::toggleRecordings,
                onSettings = onOpenSettings,
                onFullscreen = if (state.previewChannel != null) viewModel::openFullscreen else null,
                onMoveFocusToGuide = ::focusGuide,
            )

            if (state.settings.iptvPlaylists.size > 1) {
                PlaylistChips(
                    playlists = state.settings.iptvPlaylists.filter { it.enabled },
                    activeId = state.settings.activeIptvPlaylistId,
                    onSelect = viewModel::selectPlaylist,
                    onMoveFocusToGuide = ::focusGuide
                )
            }

            if (state.showRecordings) {
                RecordingsPane(
                    recordings = state.recordings,
                    onStop = viewModel::stopRecording,
                    onRemove = viewModel::removeRecording,
                    onClose = viewModel::toggleRecordings
                )
            } else {
                LiveTvStage(
                    state = state,
                    exoPlayer = exoPlayer,
                    previewError = previewError,
                    streamBadges = streamBadges,
                    audioNotice = audioStatus.notice,
                    attachPlayer = previewSurfaceAttached && !state.fullscreen,
                    browsingChannels = browsingChannels,
                    onBrowsingChannelsChange = { browsingChannels = it },
                    onReturnToGroupCards = ::returnToGroupCards,
                    guideFocusRequester = guideFocusRequester,
                    blockGuideFocus = state.epgMatching ||
                        state.epgMatchLog.isNotEmpty() ||
                        state.epgMatchSummary != null,
                    onSelectGroup = viewModel::selectGroup,
                    onSetGroupOrder = viewModel::setGroupOrderMode,
                    onRenameGroup = viewModel::renameGroup,
                    onMoveGroup = viewModel::moveGroup,
                    onMoveGroupToEdge = viewModel::moveGroupToEdge,
                    onCommitGroupOrder = viewModel::commitGroupOrder,
                    onToggleGroupHidden = viewModel::toggleGroupHidden,
                    onAutoMatchEpg = { key -> viewModel.autoMatchEpg(groupKey = key, userInitiated = true) },
                    onConfirm = viewModel::selectChannel,
                    onOpenFullscreen = viewModel::openFullscreen,
                    onAssignEpg = viewModel::openAssignEpg,
                    onRecord = viewModel::recordLive,
                    onRecordProgramme = viewModel::scheduleRecording,
                    onCancelRecording = viewModel::cancelProgrammeRecording,
                    onFavorite = viewModel::toggleFavorite,
                    onRenameChannel = viewModel::renameChannel,
                    onMoveChannel = viewModel::moveChannel,
                    onMoveChannelToEdge = viewModel::moveChannelToEdge,
                    onMoveChannelToEdgeAndCommit = viewModel::moveChannelToEdgeAndCommit,
                    onCommitChannelOrder = viewModel::commitChannelOrder,
                    onOpenExternal = { viewModel.playExternal(it) },
                    programmesFor = viewModel::programmesFor
                )
            }
        }
        }

        if (state.fullscreen && state.previewChannel != null) {
            FullscreenLiveOverlay(
                player = exoPlayer,
                channel = state.previewChannel!!,
                groupName = state.groupDisplayNames[state.previewChannel!!.group]
                    ?: state.previewChannel!!.group,
                streamBadges = streamBadges,
                audioType = audioStatus.typeLabel,
                audioTrackCount = audioStatus.trackCount,
                audioNotice = audioStatus.notice,
                videoDetails = videoDetails,
                audioStatus = audioStatus,
                history = state.watchHistory,
                nasHistory = state.nasWatchHistory,
                recordings = state.recordings,
                attachVideo = fullscreenSurfaceAttached,
                onClose = {
                    viewModel.closeFullscreen()
                    browsingChannels = true
                },
                onChannelUp = { viewModel.fullscreenStep(-1) },
                onChannelDown = { viewModel.fullscreenStep(1) },
                onLastChannel = viewModel::fullscreenLastChannel,
                onSelectHistory = viewModel::playHistoryChannel,
                onSelectNasHistory = viewModel::playHistoryVideo,
                badgesFor = { viewModel.badgesFor(it) },
                badgesConfirmedFor = { viewModel.badgesConfirmedFor(it) },
                programmesFor = viewModel::programmesFor,
                epgVersion = state.epgVersion,
            )
        }
    }

    if (state.searchOpen) {
        IptvSearchOverlay(
            query = state.searchQuery,
            channelResults = state.searchChannelResults,
            programmeResults = state.searchProgrammeResults,
            recentQueries = state.searchHistory,
            onQueryChange = viewModel::setSearchQuery,
            onRemoveRecent = viewModel::removeSearchHistory,
            onClose = viewModel::closeSearch,
            onPlayChannel = {
                viewModel.recordSearch()
                viewModel.selectChannel(it)
                browsingChannels = true
                viewModel.closeSearch()
            },
            onToggleFavorite = { ch ->
                viewModel.toggleFavorite(ch)
                viewModel.setSearchQuery(state.searchQuery)
            },
            onOpenProgramme = { ch, _ ->
                viewModel.recordSearch()
                viewModel.closeSearch()
                viewModel.openGuide(ch)
            }
        )
    }

    state.assignEpgChannel?.let { channel ->
        AssignEpgDialog(
            channel = channel,
            query = state.assignEpgQuery,
            results = state.assignEpgResults,
            currentEpgId = state.assignEpgCurrentId,
            onQueryChange = viewModel::setAssignEpgQuery,
            onPick = { viewModel.assignEpgToChannel(channel, it) },
            onClear = { viewModel.assignEpgToChannel(channel, null) },
            onDismiss = viewModel::closeAssignEpg
        )
    }

    state.detailChannel?.let { channel ->
        GuideDialog(
            channel = channel,
            programmes = state.detailProgrammes,
            isRecording = { isProgrammeBeingRecorded(state.recordings, channel, it) },
            canCatchup = { viewModel.canCatchup(channel, it) },
            onPlayLive = {
                viewModel.selectChannel(channel)
                viewModel.closeGuide()
            },
            onCatchup = { viewModel.playCatchup(channel, it) },
            onRecord = { viewModel.scheduleRecording(channel, it) },
            onCancelRecording = { viewModel.cancelProgrammeRecording(channel, it) },
            onDismiss = viewModel::closeGuide
        )
    }

    if (state.pinPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPin,
            title = { Text("Parental PIN", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("PIN") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalScreenChrome.current.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = LocalScreenChrome.current.accent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.submitPin(pinInput)
                    pinInput = ""
                }) { Text("Unlock", color = LocalScreenChrome.current.accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pinInput = ""
                    viewModel.dismissPin()
                }) { Text("Cancel", color = TextMuted) }
            },
            containerColor = CardSurface
        )
    }

    if (state.showVlcMissing) {
        AlertDialog(
            onDismissRequest = viewModel::dismissVlcMissing,
            title = { Text("Player missing", color = Color.White) },
            text = { Text("Install VLC or pick another player in Settings.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissVlcMissing) { Text("OK", color = LocalScreenChrome.current.accent) }
            },
            containerColor = CardSurface
        )
    }

    if (state.epgMatching || state.epgMatchLog.isNotEmpty() || state.epgMatchSummary != null) {
        EpgMatchProgressPanel(
            matching = state.epgMatching,
            done = state.epgMatchDone,
            total = state.epgMatchTotal,
            lines = state.epgMatchLog,
            summary = state.epgMatchSummary,
            onCancel = viewModel::cancelEpgMatch,
        )
    }

    state.message?.let { msg ->
        if (!state.epgMatching && state.epgMatchLog.isEmpty() && !state.pinPrompt) {
            ForwardFlashNotice(
                message = msg,
                title = "Live TV",
                onConsumed = viewModel::dismissMessage,
            )
        }
    }
}

@Composable
private fun EpgMatchProgressPanel(
    matching: Boolean,
    done: Int,
    total: Int,
    lines: List<EpgMatchLogLine>,
    summary: String?,
    onCancel: () -> Unit,
) {
    val listState = rememberLazyListState()
    val matchedCount = lines.count { it.matched }
    val skippedCount = lines.count { !it.matched }
    val cancelFocus = remember { FocusRequester() }
    val feedback = com.vizvag.shieldvideo.ui.theme.rememberTvFeedback()

    BackHandler(onBack = onCancel)

    LaunchedEffect(Unit) {
        delay(40)
        runCatching { cancelFocus.requestFocus() }
    }
    LaunchedEffect(matching) {
        // Keep focus on Cancel/Close whenever matching state flips.
        delay(40)
        runCatching { cancelFocus.requestFocus() }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && lines.size % 5 == 0) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Swallow D-pad so focus never falls through to Live TV behind the dialog.
                    val nav = event.key == Key.DirectionLeft ||
                        event.key == Key.DirectionRight ||
                        event.key == Key.DirectionUp ||
                        event.key == Key.DirectionDown ||
                        event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Back
                    if (nav && event.type == KeyEventType.KeyDown) {
                        if (event.key == Key.Back) {
                            onCancel()
                            return@onPreviewKeyEvent true
                        }
                        // Re-assert cancel focus if somehow lost
                        runCatching { cancelFocus.requestFocus() }
                    }
                    false
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF012141A))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .padding(20.dp),
            ) {
                Text(
                    text = if (matching) "AI EPG MATCHING" else "AI EPG MATCH RESULTS",
                    color = LocalScreenChrome.current.accent,
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = when {
                        matching && summary != null -> summary
                        matching && total > 0 -> "$done / $total channels · $matchedCount matched"
                        matching -> "Preparing…"
                        else -> summary ?: "$matchedCount matched · $skippedCount no match"
                    },
                    color = Color.White,
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (total > 0) {
                    val fraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.12f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(LocalScreenChrome.current.accent),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    userScrollEnabled = false,
                ) {
                    items(lines.size, key = { idx -> "${lines[idx].channelName}-$idx" }) { idx ->
                        val line = lines[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = line.channelName,
                                color = Color.White.copy(alpha = 0.9f),
                                fontFamily = PallasFontFamily,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "→",
                                color = TextMuted,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                            Text(
                                text = if (line.matched) line.epgName.orEmpty() else "no match",
                                color = if (line.matched) LocalScreenChrome.current.accent else TextMuted,
                                fontFamily = PallasFontFamily,
                                fontSize = 15.sp,
                                fontWeight = if (line.matched) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                var cancelFocused by remember { mutableStateOf(false) }
                Text(
                    text = if (matching) "Cancel" else "Close",
                    color = Color.White,
                    fontFamily = PallasFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .align(Alignment.End)
                        .glassInteract(focused = cancelFocused, selected = false)
                        .focusRequester(cancelFocus)
                        .onFocusChanged { cancelFocused = it.isFocused }
                        .clickable(role = Role.Button) {
                            feedback.click()
                            onCancel()
                        }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                )
                Text(
                    text = if (matching) "Back / Cancel stops matching" else "Back / Close dismisses",
                    color = TextMuted,
                    fontFamily = PallasFontFamily,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveTvStage(
    state: IptvUiState,
    exoPlayer: ExoPlayer,
    previewError: String?,
    streamBadges: List<String>,
    audioNotice: String?,
    attachPlayer: Boolean,
    browsingChannels: Boolean,
    onBrowsingChannelsChange: (Boolean) -> Unit,
    onReturnToGroupCards: () -> Unit,
    guideFocusRequester: FocusRequester,
    blockGuideFocus: Boolean,
    onSelectGroup: (String) -> Unit,
    onSetGroupOrder: (String, GroupChannelOrder) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onMoveGroup: (groupKey: String, delta: Int) -> Unit,
    onMoveGroupToEdge: (groupKey: String, toTop: Boolean) -> Unit,
    onCommitGroupOrder: () -> Unit,
    onToggleGroupHidden: (String) -> Unit,
    onAutoMatchEpg: (groupKey: String) -> Unit,
    onConfirm: (IptvChannel) -> Unit,
    onOpenFullscreen: () -> Unit,
    onAssignEpg: (IptvChannel) -> Unit,
    onRecord: (IptvChannel, Int) -> Unit,
    onRecordProgramme: (IptvChannel, IptvProgramme) -> Unit,
    onCancelRecording: (IptvChannel, IptvProgramme) -> Unit,
    onFavorite: (IptvChannel) -> Unit,
    onRenameChannel: (channelId: String, newName: String) -> Unit,
    onMoveChannel: (channelId: String, delta: Int) -> Unit,
    onMoveChannelToEdge: (channelId: String, toTop: Boolean) -> Unit,
    onMoveChannelToEdgeAndCommit: (channelId: String, toTop: Boolean) -> Unit,
    onCommitChannelOrder: () -> Unit,
    onOpenExternal: (IptvChannel) -> Unit,
    programmesFor: (IptvChannel) -> List<IptvProgramme>
) {
    var optionsRow by remember { mutableStateOf<IptvChannelRow?>(null) }
    var recordChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var renameRow by remember { mutableStateOf<IptvChannelRow?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    // Channel picked up via "Move channel…" — Up/Down carry it through the group, OK drops it.
    var movingChannelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selectedGroup, browsingChannels) { movingChannelId = null }
    var groupOptionsKey by remember { mutableStateOf<String?>(null) }
    var renameGroupKey by remember { mutableStateOf<String?>(null) }
    var renameGroupDraft by remember { mutableStateOf("") }
    // Group picked up via "Move group…" — Up/Down carry it through the wheel, OK drops it.
    var movingGroupKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(browsingChannels) { movingGroupKey = null }
    var pendingGroupOpen by remember { mutableStateOf<String?>(null) }
    // Bumped only when fullscreen closes — entering fullscreen must not touch the wheel,
    // or its delayed focus request steals the D-pad from the fullscreen overlay.
    var fullscreenExits by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.fullscreen) {
        if (!state.fullscreen) fullscreenExits++
    }

    var groupRestoreEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(browsingChannels) {
        if (!browsingChannels) groupRestoreEpoch++
    }

    LaunchedEffect(browsingChannels, state.selectedGroup, optionsRow, renameRow, state.fullscreen, blockGuideFocus) {
        if (blockGuideFocus) return@LaunchedEffect
        if (state.fullscreen || optionsRow != null || renameRow != null) return@LaunchedEffect
        // Wait for shared-bounds morph to settle before stealing focus.
        delay(if (browsingChannels) 560 else 480)
        if (blockGuideFocus) return@LaunchedEffect
        runCatching { guideFocusRequester.requestFocus() }
    }
    val previewChannel = state.previewChannel

    // Idle browse chrome → fade and enter normal fullscreen viewing (Up/Down zap).
    var browseChromeAlpha by remember { mutableFloatStateOf(1f) }
    var browseIdleEpoch by remember { mutableIntStateOf(0) }
    val browseIdleBlocked = groupOptionsKey != null ||
        renameGroupKey != null ||
        movingGroupKey != null ||
        movingChannelId != null ||
        optionsRow != null ||
        renameRow != null ||
        state.pinPrompt ||
        state.searchOpen ||
        state.showRecordings
    val latestFullscreen by rememberUpdatedState(state.fullscreen)
    val latestPreviewChannel by rememberUpdatedState(previewChannel)
    val latestOpenFullscreen by rememberUpdatedState(onOpenFullscreen)
    LaunchedEffect(
        previewChannel?.id,
        state.fullscreen,
        browseIdleBlocked,
        browseIdleEpoch
    ) {
        browseChromeAlpha = 1f
        if (previewChannel == null || state.fullscreen || browseIdleBlocked) {
            return@LaunchedEffect
        }
        delay(10_000)
        val fade = Animatable(1f)
        fade.animateTo(0f, animationSpec = tween(500)) {
            browseChromeAlpha = value
        }
        if (latestPreviewChannel != null && !latestFullscreen) {
            latestOpenFullscreen()
        }
        browseChromeAlpha = 1f
    }

    // Advance now/next while the browse stage stays open (was frozen at first composition).
    var epgClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            epgClockMs = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val previewNow = remember(previewChannel?.id, state.epgChannelCount, state.epgVersion, epgClockMs) {
        previewChannel?.let { programmesFor(it) }?.let { XmltvParser.nowNext(it, epgClockMs) }
    }
    val showEpgInPreview = state.settings.iptvShowEpgInList
    val guideSize = state.settings.iptvGuideSize
    val guideMetrics = remember(guideSize) { guideSize.metrics() }
    // One shared bottom guide frame for groups AND channels — content swaps inside it.
    val guidePanelModifier = if (showEpgInPreview) {
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(guideMetrics.channelWheelHeightFraction)
    } else {
        Modifier
            .padding(start = 12.dp)
            .width(guideMetrics.channelWheelWidthNoEpg)
            .fillMaxHeight(guideMetrics.channelWheelHeightFraction)
    }

    // Open channel list only once rows are rebuilt for the chosen group (PIN-aware).
    LaunchedEffect(pendingGroupOpen, state.pinPrompt, state.selectedGroup, state.rowsForGroup) {
        val want = pendingGroupOpen ?: return@LaunchedEffect
        if (state.pinPrompt) return@LaunchedEffect
        if (!state.selectedGroup.equals(want, ignoreCase = true)) return@LaunchedEffect
        if (!state.rowsForGroup.equals(want, ignoreCase = true)) return@LaunchedEffect
        pendingGroupOpen = null
        onBrowsingChannelsChange(true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .focusProperties { canFocus = !state.fullscreen }
            .onPreviewKeyEvent { event ->
                if (!state.fullscreen &&
                    previewChannel != null &&
                    event.type == KeyEventType.KeyDown
                ) {
                    browseIdleEpoch++
                }
                false
            }
    ) {
        // Preview stays below the top bar (TextureView + clip — SurfaceView was bleeding over it).
        // Keep it out of the D-pad focus chain so focus stays on the guide wheel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false }
                .clickable(enabled = state.previewChannel != null, onClick = onOpenFullscreen)
        ) {
            if (attachPlayer && state.previewChannel != null) {
                IptvPlayerSurface(
                    player = exoPlayer,
                    modifier = Modifier.fillMaxSize(),
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                )
            } else if (!browsingChannels) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("OK on a group to open channels", color = TextMuted)
                }
            } else if (state.previewChannel == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("OK on a channel to play", color = TextMuted)
                }
            }
        }

        // Edge scrims so overlays stay readable
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (browsingChannels && showEpgInPreview) {
                        Brush.horizontalGradient(
                            0f to Color(0xB3000000),
                            0.55f to Color(0x66000000),
                            1f to Color(0x88000000)
                        )
                    } else {
                        Brush.horizontalGradient(
                            0f to Color(0xCC000000),
                            0.42f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color(0x66000000)
                        )
                    }
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x66000000),
                        0.25f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color(0x99000000)
                    )
                )
        )

        // Playing-channel HUD (guide itself lives in the channel wheel)
        AnimatedVisibility(
            visible = browsingChannels && previewChannel != null,
            modifier = Modifier
                .align(if (showEpgInPreview) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(
                    start = 36.dp,
                    end = 28.dp,
                    top = if (showEpgInPreview) 12.dp else 0.dp,
                    bottom = if (showEpgInPreview) 0.dp else 24.dp
                )
                .fillMaxWidth(if (showEpgInPreview) 0.28f else 0.34f)
                .graphicsLayer { alpha = browseChromeAlpha },
            enter = fadeIn(tween(280, delayMillis = 120)),
            exit = fadeOut(tween(160))
        ) {
            Column {
                Text(
                    text = "PLAYING",
                    color = LocalScreenChrome.current.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = previewChannel?.name.orEmpty(),
                        color = Color.White,
                        fontSize = if (showEpgInPreview) 18.sp else 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Confirmed decoder badges only (live or cached from a prior watch).
                    streamBadges.forEach {
                        QualityChip(
                            label = it,
                            compact = true,
                            confirmed = true
                        )
                    }
                }
                audioNotice?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(
                                Color(0xFFB3261E).copy(alpha = 0.75f),
                                RoundedCornerShape(5.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (showEpgInPreview) {
                    Text(
                        text = "OK · fullscreen",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    Text(
                        text = previewNow?.now?.title?.let { "Now: $it" } ?: "No EPG",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "OK · fullscreen",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (!previewError.isNullOrBlank()) {
                    Text(
                        text = previewError,
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !browsingChannels,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(start = 400.dp, end = 36.dp, bottom = 28.dp)
                .graphicsLayer { alpha = browseChromeAlpha },
            enter = fadeIn(tween(280, delayMillis = 80)),
            exit = fadeOut(tween(160))
        ) {
            Column {
                Text(
                    text = "LIVE TV",
                    color = LocalScreenChrome.current.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Pick a category",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // One glass shell + shared-bounds morph: hero category cards ↔ channel/EPG guide.
        val guideShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(guidePanelModifier)
                .graphicsLayer { alpha = browseChromeAlpha }
                .clip(guideShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xF0181A20), Color(0xF00A0B0E))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.12f), guideShape)
                .clipToBounds()
        ) {
            // Instant swap — AnimatedContent/SharedTransition caused Shield crashes/ANRs on large groups.
            if (browsingChannels) {
                ChannelWheelPicker(
                    rows = state.rows,
                    selectedChannelId = state.previewChannel?.id,
                    onConfirm = { onConfirm(it.channel) },
                    onLongPressOptions = { optionsRow = it },
                    onProgrammeLongPress = { channel, programme ->
                        if (isProgrammeBeingRecorded(state.recordings, channel, programme)) {
                            onCancelRecording(channel, programme)
                        } else {
                            onRecordProgramme(channel, programme)
                        }
                    },
                    onBackToGroups = onReturnToGroupCards,
                    groupTitle = state.groupDisplayNames[state.selectedGroup]
                        ?: state.selectedGroup,
                    programmesFor = programmesFor,
                    isProgrammeRecording = { channel, programme ->
                        isProgrammeBeingRecorded(state.recordings, channel, programme)
                    },
                    showEpg = showEpgInPreview,
                    guideSize = guideSize,
                    epgVersion = state.epgVersion,
                    recenterKey = fullscreenExits,
                    movingChannelId = movingChannelId,
                    onMoveStep = { id, delta -> onMoveChannel(id, delta) },
                    onMoveJumpToEdge = { id, toTop -> onMoveChannelToEdge(id, toTop) },
                    onMoveDone = {
                        onCommitChannelOrder()
                        movingChannelId = null
                    },
                    focusRequester = guideFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                    requestFocus = browsingChannels,
                    drawChrome = false,
                )
            } else {
                // Remount when the active group changes so scroll/focus cannot stick on Favorites.
                key(state.selectedGroup, groupRestoreEpoch) {
                    GroupHeroGallery(
                        groups = state.groups,
                        selectedGroup = state.selectedGroup,
                        displayNames = state.groupDisplayNames,
                        hiddenGroups = state.hiddenGroups,
                        channelCounts = state.groupChannelCounts,
                        orderModes = state.groupOrderModes,
                        restoreEpoch = groupRestoreEpoch,
                        onConfirm = { group ->
                            pendingGroupOpen = group
                            onSelectGroup(group)
                        },
                        onLongPressOptions = { groupOptionsKey = it },
                        movingGroupKey = movingGroupKey,
                        onMoveStep = { key, delta -> onMoveGroup(key, delta) },
                        onMoveJumpToEdge = { key, toTop -> onMoveGroupToEdge(key, toTop) },
                        onMoveDone = {
                            onCommitGroupOrder()
                            movingGroupKey = null
                        },
                        focusRequester = guideFocusRequester,
                        modifier = Modifier.fillMaxSize(),
                        requestFocus = !browsingChannels && !blockGuideFocus,
                    )
                }
            }
        }
    }

    groupOptionsKey?.let { groupKey ->
        GroupOptionsSheet(
            groupName = state.groupDisplayNames[groupKey] ?: groupKey,
            currentOrder = state.groupOrderModes[groupKey] ?: GroupChannelOrder.CUSTOM,
            hidden = groupKey in state.hiddenGroups,
            onOrder = { mode -> onSetGroupOrder(groupKey, mode) },
            onRename = {
                groupOptionsKey = null
                renameGroupKey = groupKey
                renameGroupDraft = state.groupDisplayNames[groupKey] ?: groupKey
            },
            onMove = {
                groupOptionsKey = null
                movingGroupKey = groupKey
            },
            onToggleHidden = { onToggleGroupHidden(groupKey) },
            onAutoMatchEpg = {
                groupOptionsKey = null
                onAutoMatchEpg(groupKey)
            },
            onDismiss = { groupOptionsKey = null }
        )
    }

    renameGroupKey?.let { groupKey ->
        AlertDialog(
            onDismissRequest = { renameGroupKey = null },
            title = { Text("Rename group", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameGroupDraft,
                    onValueChange = { renameGroupDraft = it },
                    singleLine = true,
                    label = { Text("Display name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalScreenChrome.current.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = LocalScreenChrome.current.accent,
                        focusedLabelColor = LocalScreenChrome.current.accent,
                        unfocusedLabelColor = TextMuted
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameGroup(groupKey, renameGroupDraft)
                        renameGroupKey = null
                    }
                ) {
                    Text("Save", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameGroupKey = null }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = Color(0xFF2E342A)
        )
    }

    optionsRow?.let { row ->
        ChannelOptionsSheet(
            row = row,
            canMove = state.rows.size > 1,
            onAssignEpg = {
                optionsRow = null
                onAssignEpg(row.channel)
            },
            onRecord = {
                optionsRow = null
                recordChannel = row.channel
            },
            onFavorite = {
                onFavorite(row.channel)
                optionsRow = null
            },
            onRename = {
                optionsRow = null
                renameDraft = row.channel.name
                renameRow = row
            },
            onMove = {
                optionsRow = null
                movingChannelId = row.channel.id
            },
            onMoveToTop = {
                optionsRow = null
                onMoveChannelToEdgeAndCommit(row.channel.id, true)
            },
            onMoveToBottom = {
                optionsRow = null
                onMoveChannelToEdgeAndCommit(row.channel.id, false)
            },
            onFullscreen = {
                optionsRow = null
                onConfirm(row.channel)
                // Remote session: onConfirm already plays on the TV — never force tablet fullscreen.
                if (!com.vizvag.shieldvideo.playback.remote.RemoteTargetStore.isControllingRemote()) {
                    onOpenFullscreen()
                }
            },
            onOpenExternal = {
                optionsRow = null
                onOpenExternal(row.channel)
            },
            onDismiss = { optionsRow = null }
        )
    }

    recordChannel?.let { channel ->
        RecordChannelDialog(
            channel = channel,
            programmes = programmesFor(channel),
            isRecording = { isProgrammeBeingRecorded(state.recordings, channel, it) },
            onRecordMinutes = { minutes ->
                onRecord(channel, minutes)
                recordChannel = null
            },
            onRecordProgramme = { programme ->
                onRecordProgramme(channel, programme)
                recordChannel = null
            },
            onCancelProgramme = { programme ->
                onCancelRecording(channel, programme)
                recordChannel = null
            },
            onDismiss = { recordChannel = null }
        )
    }

    renameRow?.let { row ->
        AlertDialog(
            onDismissRequest = { renameRow = null },
            title = { Text("Rename channel", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("Display name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalScreenChrome.current.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = LocalScreenChrome.current.accent,
                        focusedLabelColor = LocalScreenChrome.current.accent,
                        unfocusedLabelColor = TextMuted
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameChannel(row.channel.id, renameDraft)
                        renameRow = null
                    }
                ) { Text("Save", color = LocalScreenChrome.current.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameRow = null }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardSurface
        )
    }
}

@Composable
private fun IptvTopBar(
    playlistName: String,
    channelCount: Int,
    epgCount: Int,
    refreshing: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onMultiview: () -> Unit,
    onRecordings: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
    onMoveFocusToGuide: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .padding(horizontal = 32.dp, vertical = 18.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveFocusToGuide()
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IconActionButton(selected = false, onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Live TV",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                fontFamily = PallasFontFamily,
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 6.dp)
                    .width(56.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LocalScreenChrome.current.accent,
                                LocalScreenChrome.current.accent.copy(alpha = 0.15f),
                            ),
                        ),
                    ),
            )
            Text(
                text = "$playlistName · $channelCount shown · EPG $epgCount ch" + if (refreshing) " · refreshing…" else "",
                color = TextMuted,
                fontSize = 13.sp,
                fontFamily = PallasFontFamily,
            )
        }
        if (onFullscreen != null) {
            IconActionButton(selected = false, onClick = onFullscreen) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        IconActionButton(selected = false, onClick = onSearch) {
            Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconActionButton(selected = false, onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconActionButton(selected = false, onClick = onMultiview) {
            Icon(Icons.Filled.GridView, contentDescription = "Multiview", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconActionButton(selected = false, onClick = onRecordings) {
            Icon(Icons.Filled.VideoLibrary, contentDescription = "Recordings", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconActionButton(selected = false, onClick = onSettings) {
            Icon(Icons.Filled.Settings, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun FullscreenLiveOverlay(
    player: androidx.media3.exoplayer.ExoPlayer,
    channel: IptvChannel,
    groupName: String,
    streamBadges: List<String>,
    audioType: String?,
    audioTrackCount: Int,
    audioNotice: String?,
    videoDetails: LiveVideoDetails,
    audioStatus: AudioTrackStatus,
    history: List<IptvChannel>,
    nasHistory: List<NasWatchHistoryEntry>,
    recordings: List<IptvRecording>,
    attachVideo: Boolean,
    onClose: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onLastChannel: () -> Unit,
    onSelectHistory: (IptvChannel) -> Unit,
    onSelectNasHistory: (NasWatchHistoryEntry) -> Unit,
    badgesFor: (IptvChannel) -> List<String>,
    badgesConfirmedFor: (IptvChannel) -> Boolean,
    programmesFor: (IptvChannel) -> List<IptvProgramme>,
    epgVersion: Int,
) {
    var hudVisible by remember { mutableStateOf(true) }
    var hudPulse by remember { mutableIntStateOf(0) }
    var historyVisible by remember { mutableStateOf(false) }
    var streamInfoVisible by remember { mutableStateOf(false) }
    var infoLongPressJob by remember { mutableStateOf<Job?>(null) }
    var infoLongPressFired by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Keep now/next current while watching — HUD reveal alone only refreshed on keypress.
    var epgClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            epgClockMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    fun revealHud() {
        hudVisible = true
        hudPulse++
    }

    BackHandler(onBack = onClose)

    LaunchedEffect(channel.id) {
        revealHud()
        focusRequester.requestFocus()
    }

    LaunchedEffect(hudVisible, hudPulse, channel.id) {
        if (!hudVisible) return@LaunchedEffect
        delay(7_000)
        hudVisible = false
    }

    LaunchedEffect(historyVisible, streamInfoVisible) {
        if (!historyVisible && !streamInfoVisible) {
            delay(16)
            focusRequester.requestFocus()
        }
    }

    // Ensure zap keys land here after browse chrome auto-hides into viewing mode.
    LaunchedEffect(Unit) {
        delay(80)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    streamInfoVisible = true
                    return@onPreviewKeyEvent true
                }
                if (isSelect && event.type == KeyEventType.KeyDown) {
                    if (infoLongPressJob == null) {
                        infoLongPressFired = false
                        infoLongPressJob = scope.launch {
                            delay(longPressTimeout)
                            infoLongPressFired = true
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    // Open only on release — opening while the key is held sends the
                    // KeyUp into the new dialog, which clicks Close and dismisses it.
                    val wasLongPress = infoLongPressFired
                    infoLongPressJob?.cancel()
                    infoLongPressJob = null
                    infoLongPressFired = false
                    if (wasLongPress) streamInfoVisible = true else revealHud()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack) return@onPreviewKeyEvent false
                when (event.key) {
                    // Zap keys always act, even with HUD hidden.
                    Key.DirectionUp -> {
                        onChannelUp(); revealHud(); true
                    }
                    Key.DirectionDown -> {
                        onChannelDown(); revealHud(); true
                    }
                    Key.DirectionRight -> {
                        onLastChannel(); revealHud(); true
                    }
                    Key.DirectionLeft -> {
                        historyVisible = true
                        true
                    }
                    else -> {
                        if (!hudVisible) {
                            revealHud()
                            true // first press only brings the HUD back
                        } else {
                            hudPulse++ // reset auto-hide timer
                            false
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { revealHud() }
            )
    ) {
        // SurfaceView is required for HDR passthrough — safe here because fullscreen covers everything.
        // Only attach after the preview TextureView has fully detached (see IptvScreen handoff delay).
        if (attachVideo) {
            IptvPlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
                useController = false,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                useSurfaceView = true
            )
        }
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconActionButton(selected = false, onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (groupName.isNotBlank()) {
                        Text(
                            text = groupName,
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                streamBadges.forEach { QualityChip(it) }
                audioType?.let { AudioTypeChip(it) }
                if (audioTrackCount > 1) {
                    AudioTypeChip("♫ $audioTrackCount")
                }
                audioNotice?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                Color(0xFFB3261E).copy(alpha = 0.75f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
        // Recomputed on HUD reveal and on the clock tick so a programme change while watching shows.
        val nowNext = remember(channel.id, epgVersion, hudPulse, epgClockMs) {
            XmltvParser.nowNext(programmesFor(channel), epgClockMs)
        }
        if (nowNext.now != null || nowNext.next != null) {
            AnimatedVisibility(
                visible = hudVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
            ) {
                FullscreenEpgTile(
                    nowNext = nowNext,
                    recordingNow = nowNext.now?.let {
                        isProgrammeBeingRecorded(recordings, channel, it)
                    } == true
                )
            }
        }
    }

    if (historyVisible) {
        FullscreenHistoryDialog(
            channels = history,
            nasVideos = nasHistory,
            currentChannelId = channel.id,
            badgesFor = badgesFor,
            badgesConfirmedFor = badgesConfirmedFor,
            onSelect = {
                historyVisible = false
                onSelectHistory(it)
                revealHud()
            },
            onSelectNas = {
                historyVisible = false
                onSelectNasHistory(it)
            },
            onDismiss = { historyVisible = false }
        )
    }
    if (streamInfoVisible) {
        FullscreenStreamInfoDialog(
            channel = channel,
            groupName = groupName,
            video = videoDetails,
            audio = audioStatus,
            onDismiss = { streamInfoVisible = false }
        )
    }
}

@Composable
private fun AudioTypeChip(label: String) {
    val audioColor = Color(0xFFFFA726)
    Text(
        text = label,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(audioColor.copy(alpha = 0.30f))
            .border(1.dp, audioColor.copy(alpha = 0.70f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun FullscreenStreamInfoDialog(
    channel: IptvChannel,
    groupName: String,
    video: LiveVideoDetails,
    audio: AudioTrackStatus,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.64f)
                    .fillMaxHeight(0.84f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurface)
                    .border(1.dp, LocalScreenChrome.current.accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stream information", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${channel.name} · $groupName", color = TextMuted, fontSize = 13.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = LocalScreenChrome.current.accent) }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    item { StreamInfoHeading("VIDEO") }
                    item {
                        StreamInfoRow(
                            "Resolution",
                            if (video.width > 0 && video.height > 0) {
                                val playing = "${video.width} × ${video.height}"
                                if (video.maxHeight > video.height) {
                                    "$playing (max ${resolutionBadgeLabel(video.maxHeight) ?: "${video.maxHeight}p"})"
                                } else {
                                    playing
                                }
                            } else {
                                "Unknown"
                            }
                        )
                    }
                    item { StreamInfoRow("Frame rate", video.fps.takeIf { it > 0 }?.let { "$it fps" } ?: "Unknown") }
                    item { StreamInfoRow("Dynamic range", if (video.isHdr) "HDR" else "SDR") }
                    item { StreamInfoRow("Video codec", videoCodecLabel(video.codec)) }
                    item {
                        StreamInfoRow(
                            "Video bitrate",
                            if (video.bitrate > 0) formatStreamBitrate(video.bitrate) else "Not declared by stream"
                        )
                    }
                    item {
                        StreamInfoRow(
                            "Stream bitrate",
                            if (video.measuredBitrate > 0) {
                                "${formatStreamBitrate(video.measuredBitrate)} (measured, video + audio)"
                            } else {
                                "Measuring…"
                            }
                        )
                    }
                    item {
                        StreamInfoRow(
                            "Measurement",
                            if (video.confirmed) "Decoder output (confirmed)" else "Stream metadata (not yet confirmed)"
                        )
                    }
                    item { StreamInfoHeading("AUDIO") }
                    item { StreamInfoRow("Layout", audio.typeLabel ?: "Unknown") }
                    item {
                        val selectedCodec = (audio.tracks.firstOrNull { it.selected }
                            ?: audio.tracks.firstOrNull { it.supported })?.codec
                        StreamInfoRow("Audio codec", audioCodecLabel(selectedCodec))
                    }
                    item { StreamInfoRow("Audio tracks", audio.trackCount.toString()) }
                    audio.notice?.let { notice ->
                        item { StreamInfoRow("Audio status", notice) }
                    }
                    items(audio.tracks.size) { index ->
                        val track = audio.tracks[index]
                        val title = buildString {
                            append("♫ ${index + 1}")
                            if (track.selected) append("  SELECTED")
                            if (!track.supported) append("  UNSUPPORTED")
                        }
                        val details = buildList {
                            track.label?.takeIf { it.isNotBlank() }?.let(::add)
                            track.language?.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
                            track.codec?.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
                            audioLayoutLabel(track.channelCount)?.let(::add)
                            track.sampleRate.takeIf { it > 0 }?.let { add("${it / 1000f} kHz") }
                            if (track.bitrate > 0) add(formatStreamBitrate(track.bitrate))
                        }.joinToString(" · ").ifBlank { "No format details" }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.28f))
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(
                                title,
                                color = if (track.selected) Color(0xFFFFA726) else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(details, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamInfoHeading(text: String) {
    Text(
        text = text,
        color = LocalScreenChrome.current.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 5.dp)
    )
}

@Composable
private fun StreamInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(150.dp))
        Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun videoCodecLabel(codec: String?): String {
    val c = codec?.lowercase() ?: return "Unknown"
    return when {
        "avc" in c || "h264" in c -> "H.264 (AVC)"
        "hevc" in c || "h265" in c || "hvc1" in c || "hev1" in c -> "H.265 (HEVC)"
        "av01" in c || c == "av1" -> "AV1"
        "vp9" in c -> "VP9"
        "mpeg2" in c || "mp2v" in c -> "MPEG-2"
        else -> codec.uppercase()
    }
}

private fun audioCodecLabel(codec: String?): String {
    val c = codec?.lowercase() ?: return "Unknown"
    return when {
        "eac3" in c || "ec-3" in c || "ec3" in c -> "Dolby Digital Plus (E-AC-3)"
        "ac-3" in c || "ac3" in c -> "Dolby Digital (AC-3)"
        "aac" in c || "mp4a" in c -> "AAC"
        "dts" in c -> "DTS"
        "opus" in c -> "Opus"
        "mpeg-l2" in c || "mp2" in c || c == "mpeg" -> "MPEG Layer II (MP2)"
        "mp3" in c || "mpeg-l3" in c -> "MP3"
        "pcm" in c || "raw" in c -> "PCM"
        else -> codec.uppercase()
    }
}

private fun formatStreamBitrate(bitsPerSecond: Int): String =
    when {
        bitsPerSecond <= 0 -> "Unknown / variable"
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000f)
        else -> "${bitsPerSecond / 1000} kbps"
    }

private fun audioLayoutLabel(channels: Int): String? = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    3 -> "3.0"
    4 -> "Quad"
    5 -> "5.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> channels.takeIf { it > 0 }?.let { "${it}ch" }
}

private fun isProgrammeBeingRecorded(
    recordings: List<IptvRecording>,
    channel: IptvChannel,
    programme: IptvProgramme
): Boolean = recordings.any { recording ->
    recording.channelId == channel.id &&
        (recording.status == IptvRecordingStatus.SCHEDULED ||
            recording.status == IptvRecordingStatus.RECORDING) &&
        recording.startMs < programme.stopMs &&
        recording.stopMs > programme.startMs
}

@Composable
private fun FullscreenEpgTile(nowNext: IptvNowNext, recordingNow: Boolean) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    fun range(p: IptvProgramme) =
        "${timeFmt.format(Date(p.startMs))}–${timeFmt.format(Date(p.stopMs))}"
    var progressClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowNext.now?.startMs, nowNext.now?.stopMs) {
        if (nowNext.now == null) return@LaunchedEffect
        while (true) {
            progressClockMs = System.currentTimeMillis()
            delay(15_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .wrapContentHeight()
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        nowNext.now?.let { now ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "NOW",
                        color = LocalScreenChrome.current.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    if (recordingNow) {
                        Text(
                            text = "● REC",
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = now.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = range(now),
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            now.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            val progress = ((progressClockMs - now.startMs).toFloat() /
                (now.stopMs - now.startMs).toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (recordingNow) Color(0xFFFF5252) else LocalScreenChrome.current.accent)
                )
            }
        }
        nowNext.next?.let { next ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (nowNext.now != null) 10.dp else 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "NEXT",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = next.title,
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = range(next),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                next.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenHistoryDialog(
    channels: List<IptvChannel>,
    nasVideos: List<NasWatchHistoryEntry>,
    currentChannelId: String,
    badgesFor: (IptvChannel) -> List<String>,
    badgesConfirmedFor: (IptvChannel) -> Boolean,
    onSelect: (IptvChannel) -> Unit,
    onSelectNas: (NasWatchHistoryEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(0.48f)
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurface)
                    .border(1.dp, LocalScreenChrome.current.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Recently watched",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("BACK · close", color = TextMuted, fontSize = 11.sp)
                }
                Box(modifier = Modifier.height(10.dp))
                if (channels.isEmpty() && nasVideos.isEmpty()) {
                    Text("No watch history yet", color = TextMuted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (channels.isNotEmpty()) {
                            item {
                                Text(
                                    "LIVE TV",
                                    color = LocalScreenChrome.current.accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                        items(channels, key = { it.id }) { recent ->
                            SearchChannelRow(
                                row = IptvChannelRow(
                                    channel = recent,
                                    favorite = false,
                                    nowNext = IptvNowNext(null, null),
                                    locked = false,
                                    badges = badgesFor(recent),
                                    badgesConfirmed = badgesConfirmedFor(recent)
                                ),
                                onPlay = { onSelect(recent) },
                                onLongPress = {}
                            )
                            if (recent.id == currentChannelId) {
                                Text(
                                    text = "NOW PLAYING",
                                    color = LocalScreenChrome.current.accent,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                        }
                        if (nasVideos.isNotEmpty()) {
                            item {
                                Text(
                                    "VIDEOS",
                                    color = Color(0xFFFFA726),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                        items(nasVideos, key = { "${it.share}:${it.path}" }) { video ->
                            HistoryVideoRow(video = video, onPlay = { onSelectNas(video) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryVideoRow(
    video: NasWatchHistoryEntry,
    onPlay: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = false)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "▶",
            color = Color(0xFFFFA726),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${video.share} · ${video.path}",
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlaylistChips(
    playlists: List<com.vizvag.shieldvideo.data.iptv.IptvPlaylistConfig>,
    activeId: String,
    onSelect: (String) -> Unit,
    onMoveFocusToGuide: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 28.dp, vertical = 4.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveFocusToGuide()
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        playlists.forEach { p ->
            val selected = p.id == activeId
            var focused by remember(p.id) { mutableStateOf(false) }
            Text(
                text = p.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassInteract(focused = focused, selected = selected)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(p.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun GroupSidebar(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE0121216))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(groups, key = { it }) { group ->
            val isSelected = group.equals(selected, true)
            var focused by remember { mutableStateOf(false) }
            Text(
                text = group,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassInteract(focused = focused, selected = isSelected)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Button) { onSelect(group) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ChannelList(
    rows: List<IptvChannelRow>,
    compact: Boolean,
    showEpg: Boolean,
    previewChannelId: String?,
    onPlay: (IptvChannel) -> Unit,
    onFavorite: (IptvChannel) -> Unit,
    onGuide: (IptvChannel) -> Unit,
    onRecordLive: (IptvChannel) -> Unit,
    listModifier: Modifier = Modifier
) {
    if (rows.isEmpty()) {
        Box(modifier = listModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No channels in this group — add favorites with the heart, or pick another group",
                color = TextMuted,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }
    LazyColumn(
        modifier = listModifier
            .fillMaxSize()
            .padding(end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(rows, key = { it.channel.id }) { row ->
            ChannelRowCard(
                row = row,
                compact = compact,
                showEpg = showEpg,
                previewing = row.channel.id == previewChannelId,
                onPlay = { onPlay(row.channel) },
                onFavorite = { onFavorite(row.channel) },
                onGuide = { onGuide(row.channel) },
                onRecordLive = { onRecordLive(row.channel) }
            )
        }
    }
}

@Composable
private fun ChannelRowCard(
    row: IptvChannelRow,
    compact: Boolean,
    showEpg: Boolean,
    previewing: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onGuide: () -> Unit,
    onRecordLive: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val rowHeight = if (compact) 64.dp else 88.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .glassInteract(focused = focused, selected = previewing)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 44.dp else 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            if (!row.channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = row.channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Text(row.channel.name.take(2), color = TextMuted, fontWeight = FontWeight.Bold)
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.channel.name,
                    color = Color.White,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.locked) {
                    Box(modifier = Modifier.width(6.dp))
                    Icon(Icons.Filled.Lock, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
            if (showEpg) {
                val nowTitle = row.nowNext.now?.title ?: "No EPG"
                val nextTitle = row.nowNext.next?.title
                Text(
                    text = buildString {
                        append("Now: $nowTitle")
                        if (!nextTitle.isNullOrBlank()) append("  ·  Next: $nextTitle")
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(row.channel.group, color = TextMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
        IconActionButton(selected = row.favorite, onClick = onFavorite) {
            Icon(
                if (row.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (row.favorite) LocalScreenChrome.current.accent else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Box(modifier = Modifier.width(6.dp))
        Text(
            text = "Guide",
            color = LocalScreenChrome.current.accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onGuide)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Text(
            text = "Rec",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onRecordLive)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AssignEpgDialog(
    channel: IptvChannel,
    query: String,
    results: List<EpgChannelEntry>,
    currentEpgId: String?,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(channel.id) {
        delay(80)
        runCatching { searchFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Assign EPG", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    text = channel.name,
                    color = TextMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Suggestions ranked by name match (strips UK:/FHD/…)",
                    color = TextMuted.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!currentEpgId.isNullOrBlank()) {
                    Text(
                        text = "Current: $currentEpgId",
                        color = LocalScreenChrome.current.accent.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "No EPG mapped (M3U has no tvg-id)",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus),
                    singleLine = true,
                    label = { Text("Search EPG channels") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalScreenChrome.current.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = LocalScreenChrome.current.accent,
                        focusedLabelColor = LocalScreenChrome.current.accent,
                        unfocusedLabelColor = TextMuted
                    )
                )
                if (results.isEmpty()) {
                Text(
                    text = if (query.isBlank()) {
                        "No ranked matches — refresh Live TV after EPG downloads"
                    } else {
                        "No matches"
                    },
                    color = TextMuted,
                    fontSize = 14.sp
                )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(results, key = { it.id }) { entry ->
                            val selected = entry.id.equals(currentEpgId, ignoreCase = true)
                            var focused by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassInteract(focused = focused, selected = selected)
                                    .onFocusChanged { focused = it.isFocused }
                                    .clickable { onPick(entry.id) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = entry.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.id,
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = LocalScreenChrome.current.accent)
            }
        },
        dismissButton = {
            if (!currentEpgId.isNullOrBlank()) {
                TextButton(onClick = onClear) {
                    Text("Clear mapping", color = TextMuted)
                }
            }
        },
        containerColor = CardSurface
    )
}

@Composable
private fun RecordChannelDialog(
    channel: IptvChannel,
    programmes: List<IptvProgramme>,
    isRecording: (IptvProgramme) -> Boolean,
    onRecordMinutes: (Int) -> Unit,
    onRecordProgramme: (IptvProgramme) -> Unit,
    onCancelProgramme: (IptvProgramme) -> Unit,
    onDismiss: () -> Unit
) {
    var customMinutes by remember(channel.id) { mutableStateOf("60") }
    val now = System.currentTimeMillis()
    val upcoming = remember(programmes, now / 60_000L) {
        programmes.filter { it.stopMs > now }.sortedBy { it.startMs }.take(4)
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(channel.id, upcoming.firstOrNull()?.startMs) {
        delay(80)
        runCatching { firstActionFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Record IPTV", color = Color.White, fontWeight = FontWeight.Bold)
                Text(channel.name, color = TextMuted, fontSize = 13.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (upcoming.isNotEmpty()) {
                    Text("PROGRAMME", color = LocalScreenChrome.current.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    upcoming.forEachIndexed { index, programme ->
                        val airing = programme.startMs <= now && programme.stopMs > now
                        val recording = isRecording(programme)
                        RecordingProgrammeRow(
                            label = buildString {
                                if (airing) append("Now · ")
                                append(timeFmt.format(Date(programme.startMs)))
                                append("–")
                                append(timeFmt.format(Date(programme.stopMs)))
                                append("  ")
                                append(programme.title)
                            },
                            recording = recording,
                            airing = airing,
                            focusRequester = if (index == 0) firstActionFocus else null,
                            onRecord = { onRecordProgramme(programme) },
                            onStop = { onCancelProgramme(programme) },
                        )
                    }
                    Text(
                        "Focus the stop icon on a recording to end it.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                } else {
                    Text("No current or upcoming EPG programme found.", color = TextMuted, fontSize = 13.sp)
                }
                Text("TIME PERIOD", color = LocalScreenChrome.current.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120).forEach { minutes ->
                        TextButton(onClick = { onRecordMinutes(minutes) }) {
                            Text(
                                if (minutes < 60) "${minutes}m" else "${minutes / 60}h",
                                color = Color.White
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { value ->
                            customMinutes = value.filter(Char::isDigit).take(4)
                        },
                        label = { Text("Minutes") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocalScreenChrome.current.accent,
                            unfocusedBorderColor = TextMuted,
                            focusedLabelColor = LocalScreenChrome.current.accent,
                            unfocusedLabelColor = TextMuted
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            customMinutes.toIntOrNull()
                                ?.coerceIn(1, 24 * 60)
                                ?.let(onRecordMinutes)
                        }
                    ) {
                        Text("Record", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
        },
        containerColor = CardSurface
    )
}

@Composable
private fun RecordingProgrammeRow(
    label: String,
    recording: Boolean,
    airing: Boolean,
    focusRequester: FocusRequester? = null,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val requester = focusRequester ?: localFocus
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = recording)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                role = Role.Button
                contentDescription = if (recording) "Stop recording $label" else "Record $label"
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    if (recording) onStop() else onRecord()
                    true
                } else {
                    false
                }
            }
            .clickable {
                if (recording) onStop() else onRecord()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (recording) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            color = when {
                recording -> Color(0xFFFF8A80)
                airing -> LocalScreenChrome.current.accent
                else -> Color.White
            },
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = if (recording) "Stop recording" else "Record",
            tint = if (recording) Color(0xFFFF5252) else LocalScreenChrome.current.accent,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun GuideDialog(
    channel: IptvChannel,
    programmes: List<IptvProgramme>,
    isRecording: (IptvProgramme) -> Boolean,
    canCatchup: (IptvProgramme) -> Boolean,
    onPlayLive: () -> Unit,
    onCatchup: (IptvProgramme) -> Unit,
    onRecord: (IptvProgramme) -> Unit,
    onCancelRecording: (IptvProgramme) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(channel.id) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                TextButton(
                    onClick = onPlayLive,
                    modifier = Modifier.focusRequester(firstFocus)
                ) {
                    Text("Preview / fullscreen", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.height(8.dp))
                if (programmes.isEmpty()) {
                    Text("No EPG for this channel (check tvg-id / EPG URL).", color = TextMuted)
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(programmes, key = { "${it.startMs}-${it.title}" }) { p ->
                            val past = p.stopMs < System.currentTimeMillis()
                            val catchup = past && canCatchup(p)
                            val recording = isRecording(p)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    buildString {
                                        append(timeFmt.format(Date(p.startMs)))
                                        append("–")
                                        append(timeFmt.format(Date(p.stopMs)))
                                        append("  ")
                                        append(p.title)
                                    },
                                    color = if (recording) Color(0xFFFF8A80) else Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    if (catchup) {
                                        TextButton(onClick = { onCatchup(p) }) {
                                            Text("Catch-up", color = LocalScreenChrome.current.accent, fontSize = 13.sp)
                                        }
                                    } else if (!past) {
                                        TextButton(onClick = onPlayLive) {
                                            Text("Watch", color = TextMuted, fontSize = 13.sp)
                                        }
                                    }
                                    if (!past || recording) {
                                        RecordingActionIcon(
                                            recording = recording,
                                            onRecord = { onRecord(p) },
                                            onStop = { onCancelRecording(p) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = LocalScreenChrome.current.accent) }
        },
        containerColor = CardSurface
    )
}

@Composable
private fun RecordingActionIcon(
    recording: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = { if (recording) onStop() else onRecord() },
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .glassInteract(
                focused = focused,
                selected = recording,
                idleSurface = Color.White.copy(alpha = 0.06f),
            )
            .semantics {
                contentDescription = if (recording) "Stop recording" else "Record programme"
            }
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = if (recording) Color(0xFFFF5252) else LocalScreenChrome.current.accent,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun RecordingsPane(
    recordings: List<com.vizvag.shieldvideo.data.iptv.IptvRecording>,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = LocalScreenChrome.current.accent) }
        }
        if (recordings.isEmpty()) {
            Text("No recordings yet. Use Rec on a channel or Record in the guide.", color = TextMuted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.id }) { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurface)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${r.channelName} · ${r.status.name}",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            r.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                        }
                        if (r.status == IptvRecordingStatus.RECORDING) {
                            TextButton(onClick = { onStop(r.id) }) {
                                Text("Stop & save", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                            }
                        } else if (r.status == IptvRecordingStatus.SAVING) {
                            Text("Converting…", color = LocalScreenChrome.current.accent, fontSize = 13.sp)
                        } else {
                            TextButton(onClick = { onRemove(r.id) }) {
                                Text("Remove", color = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IptvSearchOverlay(
    query: String,
    channelResults: List<IptvChannelRow>,
    programmeResults: List<Pair<IptvChannel, IptvProgramme>>,
    recentQueries: List<String>,
    onQueryChange: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClose: () -> Unit,
    onPlayChannel: (IptvChannel) -> Unit,
    onToggleFavorite: (IptvChannel) -> Unit,
    onOpenProgramme: (IptvChannel, IptvProgramme) -> Unit
) {
    var favMenuRow by remember { mutableStateOf<IptvChannelRow?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurface)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Search channels & shows") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LocalScreenChrome.current.accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = LocalScreenChrome.current.accent
                        )
                    )
                    Box(modifier = Modifier.width(12.dp))
                    TextButton(onClick = onClose) { Text("Close", color = LocalScreenChrome.current.accent) }
                }
                Box(modifier = Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (query.isBlank() && recentQueries.isNotEmpty()) {
                        item { Text("Recent searches", color = TextMuted, fontWeight = FontWeight.Bold) }
                        items(recentQueries, key = { it.lowercase() }) { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onQueryChange(recent) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = recent,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onRemoveRecent(recent) }) {
                                    Text("Remove", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    if (channelResults.isNotEmpty()) {
                        item { Text("Channels", color = TextMuted, fontWeight = FontWeight.Bold) }
                        items(channelResults, key = { it.channel.id }) { row ->
                            SearchChannelRow(
                                row = row,
                                onPlay = { onPlayChannel(row.channel) },
                                onLongPress = { favMenuRow = row }
                            )
                        }
                    }
                    if (programmeResults.isNotEmpty()) {
                        item { Text("Shows", color = TextMuted, fontWeight = FontWeight.Bold) }
                        items(programmeResults, key = { "${it.first.id}-${it.second.startMs}" }) { (ch, p) ->
                            Text(
                                text = "${p.title} · ${ch.name}",
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenProgramme(ch, p) }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    favMenuRow?.let { row ->
        AlertDialog(
            onDismissRequest = { favMenuRow = null },
            title = { Text(row.channel.name, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (row.favorite) {
                        "Remove this channel from favorites?"
                    } else {
                        "Add this channel to favorites?"
                    },
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onToggleFavorite(row.channel)
                        favMenuRow = null
                    }
                ) {
                    Text(
                        if (row.favorite) "Remove favorite" else "Add favorite",
                        color = LocalScreenChrome.current.accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { favMenuRow = null }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchChannelRow(
    row: IptvChannelRow,
    onPlay: () -> Unit,
    onLongPress: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val quality = row.badges
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = false, idleSurface = Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        onLongPress(); true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressFired = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressFired = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        val wasLongPress = longPressFired
                        longPressJob?.cancel()
                        longPressJob = null
                        longPressFired = false
                        if (wasLongPress) onLongPress() else onPlay()
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(onLongClick = onLongPress, onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.channel.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(row.channel.group)
                    row.nowNext.now?.title?.let { append("  ·  Now: $it") }
                },
                color = TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End
        ) {
            quality.forEach {
                QualityChip(it, compact = true, confirmed = row.badgesConfirmed)
            }
        }
        if (row.favorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = LocalScreenChrome.current.accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

