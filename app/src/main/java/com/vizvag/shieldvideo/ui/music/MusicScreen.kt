package com.vizvag.shieldvideo.ui.music

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.music.MusicViewModelFactory
import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.lyrics.LyricLine
import com.vizvag.shieldvideo.music.data.lyrics.LrcParser
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.music.data.synology.FileEntry
import com.vizvag.shieldvideo.music.player.PlayerUiState
import com.vizvag.shieldvideo.music.ui.AlbumArt
import com.vizvag.shieldvideo.music.ui.viewmodel.MusicViewModel
import com.vizvag.shieldvideo.music.data.settings.NasSettings
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.SleepTimerButton
import com.vizvag.shieldvideo.ui.browser.AppWithNavRail
import com.vizvag.shieldvideo.ui.browser.RailDestination
import com.vizvag.shieldvideo.ui.browser.rememberOrderedShares
import com.vizvag.shieldvideo.ui.browser.recordingFolderForRail
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.ui.theme.AudioAccent
import com.vizvag.shieldvideo.ui.theme.AudioAccentWarm
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.AudioScreenTheme
import com.vizvag.shieldvideo.ui.theme.AudioText
import com.vizvag.shieldvideo.ui.theme.AudioTextMuted

private enum class BrowseMode(val label: String) {
    Folders("Folders"),
    Artists("Artists"),
    Albums("Albums"),
    Random("Random"),
}

private enum class Panel { None, Browse, Queue }

/** Playlist reorder highlight — ice blue, never gold (gold = now playing). */
private val QueueMoveAccent = Color(0xFF5EC8FF)

/** D-pad focus ring — same ice blue so focus is never confused with gold selected. */
private val FocusRing = QueueMoveAccent

/** While >0, navigation BackHandlers stay off so one Back only dismisses the modal. */
private class ModalBackGate {
    var openCount by mutableIntStateOf(0)
        private set

    fun enter() {
        openCount++
    }

    fun leave() {
        openCount = (openCount - 1).coerceAtLeast(0)
    }
}

private val LocalModalBackGate = staticCompositionLocalOf<ModalBackGate?> { null }

@Composable
private fun modalBlocksBack(): Boolean =
    (LocalModalBackGate.current?.openCount ?: 0) > 0

@Composable
fun MusicScreen(
    appSettings: AppSettings,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit = onBack,
    onSelectShare: (String) -> Unit = {},
    onOpenLiveTv: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    AudioScreenTheme {
        val modalGate = remember { ModalBackGate() }
        CompositionLocalProvider(LocalModalBackGate provides modalGate) {
            ImmersiveMusicScreen(
                appSettings = appSettings,
                onBack = onBack,
                onOpenBrowser = onOpenBrowser,
                onSelectShare = onSelectShare,
                onOpenLiveTv = onOpenLiveTv,
                onOpenRadio = onOpenRadio,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImmersiveMusicScreen(
    appSettings: AppSettings,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectShare: (String) -> Unit,
    onOpenLiveTv: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val viewModel: MusicViewModel = viewModel(factory = MusicViewModelFactory(app.musicModule))
    val sleepState by app.sleepTimer.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val settings by viewModel.settings.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val coverUrl by viewModel.coverUrl.collectAsState()
    val trackArtUrl by viewModel.trackArtUrl.collectAsState()
    val trackTags by viewModel.trackTags.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val queueIndex by viewModel.queueIndex.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val indexState by viewModel.indexState.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val lyricsPath by viewModel.lyricsPath.collectAsState()
    val lyricsVisible by viewModel.lyricsVisible.collectAsState()
    val artDetails by viewModel.artDetails.collectAsState()

    var panel by remember { mutableStateOf(Panel.None) }
    var browseMode by remember { mutableStateOf(BrowseMode.Artists) }
    var folderPath by remember { mutableStateOf<String?>(null) }
    var screenBlack by remember { mutableStateOf(false) }
    val browseFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val blackFocus = remember { FocusRequester() }

    val musicPaths = remember(settings.musicPaths) {
        NasSettings.normalizePaths(settings.musicPaths)
    }
    val multiMusicRoots = musicPaths.size > 1
    /** Empty string = virtual root listing all configured music folders. */
    val musicRoot = if (multiMusicRoots) "" else musicPaths.first()
    val currentFolder = (folderPath ?: musicRoot).let { if (multiMusicRoots && it.isEmpty()) "" else it.trimEnd('/') }
    val browseOpen = panel == Panel.Browse
    val queueOpen = panel == Panel.Queue

    var confirmLeave by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var artistDrill by remember { mutableStateOf<ArtistEntity?>(null) }
    var albumYearFilter by remember { mutableStateOf<Int?>(null) }
    var albumTitleFilter by remember { mutableStateOf<String?>(null) }
    /** Albums that include this performer (track artist), when they're not a library artist row. */
    var performerAlbumFilter by remember { mutableStateOf<String?>(null) }
    /** Songs matching this title (player / radio track-name click). */
    var songTitleFilter by remember { mutableStateOf<String?>(null) }
    /** Artists matching this name (radio artist click). */
    var artistNameFilter by remember { mutableStateOf<String?>(null) }
    val artistsListState = rememberLazyListState()
    val albumsListState = rememberLazyListState()
    val songsListState = rememberLazyListState()
    val folderScrollPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    /** Parent folder path → child path to focus after Back. */
    val folderReturnFocus = remember { mutableStateMapOf<String, String>() }

    // Only request focus when opening Browse/Queue — not on every mode change
    // (mode switches must keep focus on the clicked ModePill / list without a fight).
    LaunchedEffect(panel) {
        when (panel) {
            Panel.Browse -> {
                kotlinx.coroutines.delay(320)
                if (albumTitleFilter.isNullOrBlank() &&
                    songTitleFilter.isNullOrBlank() &&
                    artistNameFilter.isNullOrBlank()
                ) {
                    runCatching { browseFocus.requestFocus() }
                }
            }
            Panel.Queue -> {
                kotlinx.coroutines.delay(320)
                runCatching { queueFocus.requestFocus() }
            }
            Panel.None -> {
                kotlinx.coroutines.delay(80)
                runCatching { playFocus.requestFocus() }
            }
        }
    }

    fun openBrowseArtist(artist: ArtistEntity) {
        browseMode = BrowseMode.Artists
        artistDrill = artist
        albumYearFilter = null
        albumTitleFilter = null
        performerAlbumFilter = null
        songTitleFilter = null
        artistNameFilter = null
        panel = Panel.Browse
    }

    fun openBrowseYear(year: Int) {
        browseMode = BrowseMode.Albums
        artistDrill = null
        albumYearFilter = year
        albumTitleFilter = null
        performerAlbumFilter = null
        songTitleFilter = null
        artistNameFilter = null
        panel = Panel.Browse
    }

    fun openBrowseAlbum(albumTitle: String) {
        val name = albumTitle.trim()
        if (name.isEmpty()) return
        android.util.Log.i("PallasMusic", "openBrowseAlbum query='$name'")
        browseMode = BrowseMode.Albums
        artistDrill = null
        albumYearFilter = null
        albumTitleFilter = name
        performerAlbumFilter = null
        songTitleFilter = null
        artistNameFilter = null
        panel = Panel.Browse
    }

    fun openBrowsePerformer(performer: String) {
        val name = performer.trim()
        if (name.isEmpty() || isVariousArtistsName(name)) return
        android.util.Log.i("PallasMusic", "openBrowsePerformer query='$name'")
        browseMode = BrowseMode.Albums
        artistDrill = null
        albumYearFilter = null
        albumTitleFilter = null
        performerAlbumFilter = name
        songTitleFilter = null
        artistNameFilter = null
        panel = Panel.Browse
    }

    fun openBrowseSongTitle(songTitle: String) {
        val name = songTitle.trim()
        if (name.isEmpty()) return
        android.util.Log.i("PallasMusic", "openBrowseSongTitle query='$name'")
        browseMode = BrowseMode.Albums
        artistDrill = null
        albumYearFilter = null
        albumTitleFilter = null
        performerAlbumFilter = null
        songTitleFilter = name
        artistNameFilter = null
        panel = Panel.Browse
    }

    fun openBrowseArtistName(artistName: String) {
        val name = artistName.trim()
        if (name.isEmpty() || isVariousArtistsName(name)) return
        android.util.Log.i("PallasMusic", "openBrowseArtistName query='$name'")
        browseMode = BrowseMode.Artists
        artistDrill = null
        albumYearFilter = null
        albumTitleFilter = null
        performerAlbumFilter = null
        songTitleFilter = null
        artistNameFilter = name
        panel = Panel.Browse
    }

    // Consume Radio→Music handoffs when Music becomes active.
    LaunchedEffect(Unit) {
        MusicNavRequests.takeArtist()?.let { openBrowseArtistName(it) }
        MusicNavRequests.takeTrack()?.let { openBrowseSongTitle(it) }
    }

    fun leave() {
        viewModel.stopPlayback()
        onBack()
    }

    fun requestLeave() {
        confirmLeave = true
    }

    DisposableEffect(viewModel.playerController) {
        app.sleepTimer.bindPlayback(
            onVolume = { viewModel.playerController.player.volume = it },
            onStop = {
                screenBlack = false
                viewModel.playerController.stop()
            },
        )
        onDispose {
            app.sleepTimer.unbindPlayback()
            viewModel.stopPlayback()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.keepScreenOn = true
                Lifecycle.Event.ON_PAUSE -> view.keepScreenOn = false
                Lifecycle.Event.ON_STOP -> viewModel.stopPlayback()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            viewModel.stopPlayback()
        }
    }

    BackHandler(enabled = !modalBlocksBack()) {
        when {
            screenBlack -> screenBlack = false
            infoOpen -> infoOpen = false
            confirmLeave -> confirmLeave = false
            panel == Panel.Browse && artistDrill != null -> artistDrill = null
            panel == Panel.Browse &&
                browseMode == BrowseMode.Folders &&
                currentFolder != musicRoot &&
                (currentFolder.isNotBlank() || !multiMusicRoots) -> {
                val child = currentFolder.trimEnd('/')
                val parent = when {
                    multiMusicRoots && musicPaths.any { it.equals(child, ignoreCase = true) } ->
                        musicRoot
                    else ->
                        child.substringBeforeLast('/', musicRoot).ifBlank { musicRoot }
                }
                folderReturnFocus[parent.trimEnd('/')] = child
                folderPath = parent.ifEmpty { null }
            }
            panel != Panel.None -> panel = Panel.None
            else -> requestLeave()
        }
    }

    LaunchedEffect(screenBlack) {
        if (screenBlack) {
            delay(80)
            runCatching { blackFocus.requestFocus() }
        } else {
            delay(80)
            runCatching { playFocus.requestFocus() }
        }
    }

    val railShares = rememberOrderedShares(appSettings)
    val recordingFolder = remember(appSettings) { recordingFolderForRail(appSettings) }

    if (!settings.isConfigured) {
        AppWithNavRail(
            destination = RailDestination.Music,
            shares = railShares,
            selectedShare = appSettings.defaultShare,
            onSelectShare = onSelectShare,
            recordingFolder = recordingFolder,
            onLiveTv = onOpenLiveTv,
            onRadio = onOpenRadio,
            onMusic = {},
            sleepTimerActive = sleepState.active,
            sleepTimerLabel = sleepState.label,
            onCycleSleepTimer = app.sleepTimer::cycle,
            onSettings = onOpenSettings,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(AppBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Set NAS credentials in Settings to use Music",
                    color = AudioTextMuted,
                    fontSize = 18.sp,
                    fontFamily = PallasFontFamily,
                )
            }
        }
        return
    }

    AppWithNavRail(
        destination = RailDestination.Music,
        shares = railShares,
        selectedShare = appSettings.defaultShare,
        onSelectShare = onSelectShare,
        recordingFolder = recordingFolder,
        onLiveTv = onOpenLiveTv,
        onRadio = onOpenRadio,
        onMusic = {},
        sleepTimerActive = sleepState.active,
        sleepTimerLabel = sleepState.label,
        onCycleSleepTimer = app.sleepTimer::cycle,
        onSettings = onOpenSettings,
        showRail = !screenBlack,
        railFocusEnabled = panel == Panel.None && !screenBlack,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Side panels share the row with the player — sized so both always fit.
            val sidePanelWidth = minOf(maxWidth * 0.40f, 560.dp)
            val playerMin = maxWidth * 0.48f
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                AnimatedVisibility(
                    visible = browseOpen,
                    enter = expandHorizontally(
                        animationSpec = tween(300),
                        expandFrom = Alignment.Start,
                    ) + fadeIn(tween(220)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(280),
                        shrinkTowards = Alignment.Start,
                    ) + fadeOut(tween(180)),
                ) {
                    BrowseDrawer(
                        mode = browseMode,
                        onMode = {
                            artistDrill = null
                            albumYearFilter = null
                            albumTitleFilter = null
                            performerAlbumFilter = null
                            songTitleFilter = null
                            artistNameFilter = null
                            browseMode = it
                        },
                        folderPath = currentFolder,
                        musicRoot = musicRoot,
                        musicPaths = musicPaths,
                        artists = artists,
                        albums = albums,
                        tracks = tracks,
                        indexing = indexState?.isIndexing == true,
                        indexProgress = indexState?.progress ?: 0f,
                        indexMessage = indexState?.statusMessage,
                        indexError = indexState?.statusMessage
                            ?.takeIf { it.startsWith("Error:", ignoreCase = true) },
                        indexTrackCount = indexState?.trackCount ?: 0,
                        indexBuiltAtMs = indexState?.lastIndexedAt ?: 0L,
                        onSync = viewModel::indexNow,
                        viewModel = viewModel,
                        onOpenFolder = { child ->
                            val parent = currentFolder.trimEnd('/')
                            val dest = child.trimEnd('/')
                            if (dest != parent) {
                                folderReturnFocus[parent] = dest
                            }
                            folderPath = dest.ifEmpty { null }
                        },
                        onClose = { panel = Panel.None },
                        onPlayed = { panel = Panel.None },
                        listFocusRequester = browseFocus,
                        artistsListState = artistsListState,
                        albumsListState = albumsListState,
                        songsListState = songsListState,
                        folderScrollPositions = folderScrollPositions,
                        folderReturnFocus = folderReturnFocus,
                        artistDrill = artistDrill,
                        onArtistDrill = { artistDrill = it },
                        albumYearFilter = albumYearFilter,
                        onClearAlbumYearFilter = { albumYearFilter = null },
                        albumTitleFilter = albumTitleFilter,
                        onClearAlbumTitleFilter = { albumTitleFilter = null },
                        performerAlbumFilter = performerAlbumFilter,
                        onClearPerformerAlbumFilter = { performerAlbumFilter = null },
                        songTitleFilter = songTitleFilter,
                        onClearSongTitleFilter = { songTitleFilter = null },
                        artistNameFilter = artistNameFilter,
                        onClearArtistNameFilter = {
                            artistNameFilter = null
                            artistDrill = null
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(sidePanelWidth)
                            .clipToBounds()
                            .focusProperties {
                                // Browse sits beside the nav rail — Left must not escape into it.
                                exit = { direction ->
                                    if (direction == FocusDirection.Left) FocusRequester.Cancel
                                    else FocusRequester.Default
                                }
                            },
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .widthIn(min = playerMin)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    val playing = playerState.track
                    val libraryAlbum = remember(playing?.albumId, albums) {
                        playing?.albumId?.let { id -> albums.firstOrNull { it.albumId == id } }
                    }
                    val tagArtist = sequenceOf(
                        trackTags?.artist,
                        playing?.artistName,
                    ).mapNotNull { MetadataResolver.fixTagText(it.orEmpty()).trim().takeIf { n -> n.isNotBlank() } }
                        .firstOrNull { !MetadataResolver.isPlaceholderArtist(it) }
                    val tagAlbumArtist = sequenceOf(
                        trackTags?.albumArtist,
                        playing?.albumArtist,
                    ).mapNotNull { MetadataResolver.fixTagText(it.orEmpty()).trim().takeIf { n -> n.isNotBlank() } }
                        .firstOrNull { !MetadataResolver.isPlaceholderArtist(it) }
                    val pathArtist = MetadataResolver.resolveDisplayArtist(
                        artist = null,
                        albumArtist = null,
                        nasPath = playing?.nasPath,
                        albumTitle = trackTags?.album
                            ?: playing?.albumTitle
                            ?: libraryAlbum?.title,
                    )
                    val primaryArtist = tagArtist
                        ?: tagAlbumArtist
                        ?: pathArtist
                        ?: if (playing == null) "Browse to start listening" else "Unknown artist"
                    // Second line only when Album Artist is present and differs from Artist.
                    val albumArtistLine = tagAlbumArtist
                        ?.takeUnless { it.equals(primaryArtist, ignoreCase = true) }
                    val rawTitle = trackTags?.title?.takeIf { it.isNotBlank() }
                        ?: playing?.title?.takeIf { it.isNotBlank() }
                        ?: "Nothing playing"
                    // What the user sees / clicks — never prefer a library row that is really the track name.
                    val rawAlbum = sequenceOf(
                        trackTags?.album,
                        playing?.albumTitle,
                        libraryAlbum?.title,
                    ).mapNotNull { it?.trim()?.takeIf { t -> t.isNotBlank() } }
                        .firstOrNull { !it.equals(rawTitle, ignoreCase = true) }
                    val rawTrackNo = trackTags?.trackNumber ?: playing?.trackNumber
                    val rawYear = trackTags?.year
                        ?: playing?.year
                        ?: libraryAlbum?.year
                    val display = remember(
                        rawTitle,
                        primaryArtist,
                        rawAlbum,
                        rawTrackNo,
                        rawYear,
                        playing?.nasPath,
                    ) {
                        nowPlayingDisplay(
                            rawTitle,
                            primaryArtist,
                            rawAlbum,
                            rawTrackNo,
                            rawYear,
                            nasPath = playing?.nasPath,
                        )
                    }

                    fun resolvePlayingArtist(): ArtistEntity? {
                        val track = playing ?: return null
                        // What the user clicked on screen (track artist), never albumArtist first.
                        val wanted = sequenceOf(
                            display.artist,
                            trackTags?.artist,
                            track.artistName,
                        ).mapNotNull { it?.trim()?.takeIf { n -> n.isNotBlank() } }
                            .firstOrNull { !MetadataResolver.isPlaceholderArtist(it) }

                        if (wanted != null && !isVariousArtistsName(wanted)) {
                            artists.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                                ?.let { return it }
                            artists.firstOrNull {
                                it.name.startsWith(wanted, ignoreCase = true) ||
                                    wanted.startsWith(it.name, ignoreCase = true)
                            }?.let { return it }
                        }

                        val byId = artists.firstOrNull { it.id == track.artistId }
                        if (byId != null) {
                            // Motown comps: artistId is Often "Various Artists" while UI shows Diana Ross.
                            if (wanted != null &&
                                isVariousArtistsName(byId.name) &&
                                !byId.name.equals(wanted, ignoreCase = true)
                            ) {
                                return null
                            }
                            return byId
                        }
                        return libraryAlbum?.let { album ->
                            artists.firstOrNull { it.id == album.artistId }
                                ?.takeUnless {
                                    wanted != null &&
                                        isVariousArtistsName(it.name) &&
                                        !it.name.equals(wanted, ignoreCase = true)
                                }
                        }
                    }

                    fun openArtistFromPlayer() {
                        val wanted = display.artist.trim().takeIf { it.isNotBlank() }
                            ?.takeUnless { MetadataResolver.isPlaceholderArtist(it) }
                            ?: return
                        if (isVariousArtistsName(wanted)) {
                            resolvePlayingArtist()?.let { openBrowseArtist(it) }
                            return
                        }
                        resolvePlayingArtist()?.let {
                            openBrowseArtist(it)
                            return
                        }
                        // Performer only appears on comps — still open their albums, not VA.
                        openBrowsePerformer(wanted)
                    }

                    fun openAlbumArtistFromPlayer() {
                        val wanted = albumArtistLine?.trim()?.takeIf { it.isNotBlank() }
                            ?.takeUnless { MetadataResolver.isPlaceholderArtist(it) }
                            ?: return
                        if (isVariousArtistsName(wanted)) {
                            openBrowsePerformer(wanted)
                            return
                        }
                        artists.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                            ?.let {
                                openBrowseArtist(it)
                                return
                            }
                        openBrowseArtistName(wanted)
                    }

                    fun openAlbumFromPlayer() {
                        // Search for the label on screen — not libraryAlbum.title (often = track name).
                        val albumName = display.albumTitle?.trim()?.takeIf { it.isNotBlank() }
                            ?: return
                        if (albumName.equals(display.title, ignoreCase = true)) return
                        openBrowseAlbum(albumName)
                    }

                    fun openYearFromPlayer() {
                        val year = display.year ?: libraryAlbum?.year ?: return
                        openBrowseYear(year)
                    }

                    fun openTitleFromPlayer() {
                        val name = display.title.trim().takeIf { it.isNotBlank() } ?: return
                        if (name.equals("Nothing playing", ignoreCase = true)) return
                        openBrowseSongTitle(name)
                    }

                    val formatBadge = remember(playing?.nasPath, playing?.mimeType, trackTags?.codec) {
                        audioFormatBadge(
                            nasPath = playing?.nasPath,
                            mimeType = playing?.mimeType,
                            codec = trackTags?.codec ?: playing?.codec,
                        )
                    }

                    val discNo = trackTags?.discNumber ?: playing?.discNumber
                    val showDiscNumber = remember(discNo, display.albumTitle, playing?.albumTitle, tracks) {
                        val title = display.albumTitle?.trim()?.takeIf { it.isNotBlank() }
                            ?: playing?.albumTitle?.trim()?.takeIf { it.isNotBlank() }
                            ?: return@remember (discNo ?: 0) > 1
                        tracks.any {
                            it.albumTitle.equals(title, ignoreCase = true) &&
                                (it.discNumber ?: 1) > 1
                        } || (discNo ?: 0) > 1
                    }

                    ImmersiveStage(
                        playerState = playerState,
                        albumArtUrl = coverUrl,
                        trackArtUrl = trackArtUrl,
                        title = display.title,
                        artist = display.artist,
                        albumArtist = albumArtistLine,
                        album = display.albumTitle,
                        trackNumber = display.trackNumber,
                        discNumber = discNo,
                        showDiscNumber = showDiscNumber,
                        year = display.year,
                        formatBadge = formatBadge,
                        lyrics = lyrics,
                        lyricsVisible = lyricsVisible,
                        controlsEnabled = panel == Panel.None && !infoOpen && !confirmLeave,
                        metaEnabled = playing != null && !infoOpen && !confirmLeave,
                        playFocusRequester = playFocus,
                        onTogglePlay = viewModel::togglePlayPause,
                        onPrev = viewModel::skipPrevious,
                        onNext = viewModel::skipNext,
                        onSeekBack = { viewModel.seekBy(-10_000L) },
                        onSeekForward = { viewModel.seekBy(10_000L) },
                        onTitleClick = ::openTitleFromPlayer,
                        onArtistClick = ::openArtistFromPlayer,
                        onAlbumArtistClick = ::openAlbumArtistFromPlayer,
                        onAlbumClick = ::openAlbumFromPlayer,
                        onYearClick = ::openYearFromPlayer,
                    )

                    // Chrome lives on the player pane so it shifts with Browse/Queue panels
                    val chromeFocusable = panel == Panel.None
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = 6.dp)
                            .focusProperties { canFocus = chromeFocusable },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .focusProperties { canFocus = chromeFocusable }
                                .clickable(
                                    enabled = true,
                                    role = Role.Button,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        if (panel != Panel.None) panel = Panel.None else requestLeave()
                                    },
                                )
                                .focusable(enabled = chromeFocusable)
                                .padding(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AudioAccent)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Music",
                                    tint = Color(0xFF0A0804),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                "Music",
                                color = AudioText,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val chrome = LocalScreenChrome.current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconActionButton(
                                selected = panel == Panel.Browse,
                                enabled = chromeFocusable,
                                onClick = {
                                    panel = if (panel == Panel.Browse) Panel.None else Panel.Browse
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    "Browse",
                                    tint = if (panel == Panel.Browse) chrome.accent else Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            IconActionButton(
                                selected = panel == Panel.Queue,
                                enabled = chromeFocusable,
                                onClick = {
                                    panel = if (panel == Panel.Queue) Panel.None else Panel.Queue
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    "Queue",
                                    tint = if (panel == Panel.Queue) chrome.accent else Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            IconActionButton(
                                selected = lyricsVisible,
                                enabled = chromeFocusable,
                                onClick = viewModel::toggleLyrics,
                            ) {
                                Icon(
                                    Icons.Filled.Lyrics,
                                    "Lyrics",
                                    tint = if (lyricsVisible) chrome.accent else Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            IconActionButton(
                                selected = infoOpen,
                                enabled = chromeFocusable,
                                onClick = { infoOpen = true },
                            ) {
                                Icon(
                                    Icons.Filled.Info,
                                    "Track info",
                                    tint = if (infoOpen) chrome.accent else Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            SleepTimerButton(
                                active = sleepState.active,
                                label = sleepState.label,
                                onCycle = app.sleepTimer::cycle,
                                enabled = chromeFocusable,
                            )
                            IconActionButton(
                                selected = false,
                                enabled = chromeFocusable,
                                onClick = { screenBlack = true },
                            ) {
                                Icon(
                                    Icons.Filled.Brightness2,
                                    "Black screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = queueOpen,
                    enter = expandHorizontally(
                        animationSpec = tween(300),
                        expandFrom = Alignment.End,
                    ) + fadeIn(tween(220)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(280),
                        shrinkTowards = Alignment.End,
                    ) + fadeOut(tween(180)),
                ) {
                    QueueDrawer(
                        queue = queue,
                        queueIndex = queueIndex,
                        isPlaying = playerState.isPlaying,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs.takeIf { it > 0 }
                            ?: playerState.track?.durationMs ?: 0L,
                        nowPlayingAlbumArt = coverUrl,
                        resolveCoverUrl = viewModel::resolveLocalCoverModel,
                        onPlayIndex = viewModel::playQueueIndex,
                        onRemove = viewModel::removeFromPlaylist,
                        onMove = viewModel::movePlaylistItem,
                        onShuffle = viewModel::shufflePlaylist,
                        onClear = viewModel::clearPlaylist,
                        onBrowse = { panel = Panel.Browse },
                        onClose = { panel = Panel.None },
                        listFocusRequester = queueFocus,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(sidePanelWidth)
                            .clipToBounds()
                            .focusProperties {
                                exit = { direction ->
                                    if (direction == FocusDirection.Right) FocusRequester.Cancel
                                    else FocusRequester.Default
                                }
                            },
                    )
                }
            }
        }

        if (indexState?.isIndexing == true) {
            Text(
                text = indexState?.statusMessage?.takeIf { it.isNotBlank() } ?: "Updating library...",
                color = AudioAccent.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp),
            )
        }
        statusMessage?.let { msg ->
            Text(
                text = msg,
                color = AudioAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp),
            )
        }

        if (infoOpen) {
            TrackInfoSheet(
                track = playerState.track,
                tags = trackTags,
                lyricsPath = lyricsPath,
                expectedLyricsPath = playerState.track?.nasPath?.let {
                    MetadataResolver.lyricsPathFor(it)
                },
                artDetails = artDetails,
                onDismiss = { infoOpen = false },
            )
        }

        if (confirmLeave) {
            LeaveMusicSheet(
                onConfirm = {
                    confirmLeave = false
                    leave()
                },
                onDismiss = { confirmLeave = false },
            )
        }

        if (screenBlack) {
            MusicBlackScreenOverlay(
                focusRequester = blackFocus,
                onWake = { screenBlack = false },
            )
        }
    }
    }
}

@Composable
private fun MusicBlackScreenOverlay(
    focusRequester: FocusRequester,
    onWake: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .clickable(role = Role.Button, onClick = onWake),
    )
}

private enum class TransportFocus { Prev, SeekBack, Play, SeekForward, Next }

@Composable
private fun SyncedLyricsPanel(
    lyrics: List<LyricLine>,
    positionMs: Long,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val index = LrcParser.currentLineIndex(lyrics, positionMs)
    val currentSize = (26f * scale).coerceIn(20f, 30f).sp
    val sideSize = (15f * scale).coerceIn(12f, 17f).sp
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
    ) {
        if (lyrics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No lyrics found",
                    color = AudioTextMuted,
                    fontSize = sideSize,
                    fontFamily = PallasFontFamily,
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        val panelHeight = maxHeight
        val lineHeight = (36.dp * scale).coerceIn(30.dp, 44.dp)
        val lineHeightPx = with(density) { lineHeight.toPx() }
        val viewportPx = with(density) { panelHeight.toPx() }
        val edgePad = with(density) {
            ((viewportPx / 2f) - (lineHeightPx / 2f)).coerceAtLeast(0f).toDp()
        }
        val fractional = fractionalLyricIndex(lyrics, positionMs)

        LaunchedEffect(fractional, lyrics.size, lineHeightPx, panelHeight) {
            val idx = fractional.toInt().coerceIn(0, lyrics.lastIndex)
            val frac = (fractional - idx).coerceIn(0f, 0.999f)
            listState.scrollToItem(
                index = idx,
                scrollOffset = (frac * lineHeightPx).toInt(),
            )
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(vertical = edgePad),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
            ) {
                itemsIndexed(
                    lyrics,
                    key = { i, line -> "${line.timeMs}|$i|${line.text}" },
                ) { i, line ->
                    val isCurrent = i == index
                    Text(
                        text = line.text.ifBlank { " " },
                        color = when {
                            isCurrent -> AudioAccent
                            else -> Color.White.copy(
                                alpha = when {
                                    kotlin.math.abs(i - index.coerceAtLeast(0)) <= 1 -> 0.55f
                                    kotlin.math.abs(i - index.coerceAtLeast(0)) <= 3 -> 0.35f
                                    else -> 0.22f
                                },
                            )
                        },
                        fontSize = if (isCurrent) currentSize else sideSize,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = PallasFontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(lineHeight),
                    )
                }
            }
            // Soft fades — movie-credits edges
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeight * 0.28f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF050507).copy(alpha = 0.85f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeight * 0.28f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF050507).copy(alpha = 0.85f)),
                        ),
                    ),
            )
        }
    }
}

/** Continuous credits scroll: index + progress toward the next timed line. */
private fun fractionalLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Float {
    if (lyrics.isEmpty()) return 0f
    val index = LrcParser.currentLineIndex(lyrics, positionMs)
    if (index < 0) return 0f
    if (index >= lyrics.lastIndex) return index.toFloat()
    val cur = lyrics[index].timeMs
    val next = lyrics[index + 1].timeMs
    if (next <= cur) return index.toFloat()
    val progress = ((positionMs - cur).toFloat() / (next - cur).toFloat()).coerceIn(0f, 1f)
    return index + progress
}

@Composable
private fun MusicModalOverlay(
    onDismiss: () -> Unit,
    scrimAlpha: Float = 0.78f,
    /**
     * false = draw in the current composition (same Activity window). Use for root-level
     * sheets like Leave Music — TV Back is reliable. true = separate Dialog window for
     * sheets composed inside a side panel that still need a full-screen scrim.
     */
    windowed: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val gate = LocalModalBackGate.current
    DisposableEffect(Unit) {
        gate?.enter()
        onDispose { gate?.leave() }
    }
    val scrimFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(32)
        runCatching { scrimFocus.requestFocus() }
    }
    val scrimModifier = Modifier
        .fillMaxSize()
        .zIndex(40f)
        .focusRequester(scrimFocus)
        .focusable()
        .onPreviewKeyEvent { event ->
            val isBack = event.key == Key.Back || event.key == Key.Escape
            if (!isBack) return@onPreviewKeyEvent false
            // Consume Down+Up so the key never falls through to browse/nav handlers.
            if (event.type == KeyEventType.KeyUp) onDismiss()
            true
        }
        .background(Color.Black.copy(alpha = scrimAlpha))
        .clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onDismiss,
        )

    if (windowed) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            // Must live inside Dialog — TV Back is delivered to the Dialog window dispatcher.
            BackHandler(onBack = onDismiss)
            Box(
                modifier = scrimModifier,
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    } else {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = scrimModifier,
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun TrackInfoSheet(
    track: TrackEntity?,
    tags: com.vizvag.shieldvideo.music.data.metadata.TrackTagInfo?,
    lyricsPath: String?,
    expectedLyricsPath: String?,
    artDetails: com.vizvag.shieldvideo.music.ui.viewmodel.ArtDetails,
    onDismiss: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40)
        runCatching { closeFocus.requestFocus() }
    }

    fun dash(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: "—"

    val nasPath = track?.nasPath?.replace('\\', '/')
    val fileName = nasPath?.substringAfterLast('/')
    val extension = fileName
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()
    val format = audioFormatBadge(
        nasPath = track?.nasPath,
        mimeType = track?.mimeType,
        codec = tags?.codec ?: track?.codec,
    )
    val durationMs = tags?.durationMs?.takeIf { it > 0 }
        ?: track?.durationMs?.takeIf { it > 0 }
    val bitrate = tags?.bitrateKbps ?: track?.bitrateKbps
        ?: estimatedBitrateKbps(
            fileSize = track?.fileSize ?: 0L,
            durationMs = durationMs ?: 0L,
        )
    val rate = tags?.sampleRateHz ?: track?.sampleRateHz
    val channels = tags?.channels ?: track?.channels
    val channelLabel = when (channels) {
        null -> null
        1 -> "1 (mono)"
        2 -> "2 (stereo)"
        else -> channels.toString()
    }
    val title = dash(tags?.title ?: track?.title)
    val artist = dash(tags?.artist ?: track?.artistName)
    val album = dash(tags?.album ?: track?.albumTitle)
    val coverUrl = artDetails.trackArtUrl?.takeIf { it.isNotBlank() }
        ?: artDetails.albumArtUrl?.takeIf { it.isNotBlank() }
    val trackNo = tags?.trackNumber ?: track?.trackNumber
    val discNo = tags?.discNumber ?: track?.discNumber
    val trackLabel = when {
        trackNo != null && discNo != null -> "$trackNo · disc $discNo"
        trackNo != null -> trackNo.toString()
        discNo != null -> "disc $discNo"
        else -> null
    }
    val lyricsLabel = when {
        !lyricsPath.isNullOrBlank() -> lyricsPath.replace('\\', '/')
        !expectedLyricsPath.isNullOrBlank() ->
            "Not found · expected ${expectedLyricsPath.replace('\\', '/')}"
        else -> "Not found"
    }

    val identity = listOf(
        "Title" to title,
        "Artist" to artist,
        "Album artist" to dash(tags?.albumArtist ?: track?.albumArtist),
        "Album" to album,
        "Track" to dash(trackLabel),
        "Year" to dash((tags?.year ?: track?.year)?.toString()),
        "Genre" to dash(tags?.genre ?: track?.genre),
        "Duration" to dash(durationMs?.let { formatTime(it) }),
    )
    val technical = listOf(
        "Format" to dash(format),
        "Extension" to dash(extension),
        "Codec" to dash(tags?.codec ?: track?.codec),
        "Bitrate" to dash(bitrate?.let { "$it kbps" }),
        "Sample rate" to dash(
            rate?.let {
                "${String.format(java.util.Locale.US, "%.1f", it / 1000f)} kHz"
            },
        ),
        "Bit depth" to dash(tags?.bitsPerSample?.let { "$it-bit" }),
        "Channels" to dash(channelLabel),
        "MIME" to dash(track?.mimeType),
        "Size" to dash(track?.fileSize?.takeIf { it > 0 }?.let { formatFileSize(it) }),
        "Modified" to dash(formatModifiedTime(track?.modifiedTime ?: 0L)),
        "File name" to dash(fileName),
        "File path" to dash(nasPath),
    )
    val credits = listOf(
        "Composer" to dash(tags?.composer ?: track?.composer),
        "Lyricist" to dash(tags?.lyricist ?: track?.lyricist),
        "Conductor" to dash(tags?.conductor ?: track?.conductor),
        "Publisher" to dash(tags?.publisher ?: track?.publisher),
        "Original artist" to dash(tags?.originalArtist ?: track?.originalArtist),
        "Remixer" to dash(tags?.remixer ?: track?.remixer),
        "BPM" to dash((tags?.bpm ?: track?.bpm)?.toString()),
        "ISRC" to dash(tags?.isrc ?: track?.isrc),
        "Encoder" to dash(tags?.encoder ?: track?.encoder),
        "Mood" to dash(tags?.mood ?: track?.mood),
        "Media" to dash(tags?.media ?: track?.media),
        "Language" to dash(tags?.language ?: track?.language),
        "Copyright" to dash(tags?.copyright ?: track?.copyright),
        "Grouping" to dash(tags?.grouping ?: track?.grouping),
        "Comment" to dash(tags?.comment ?: track?.comment),
        "URL" to dash(tags?.url),
    )
    val paths = listOf(
        "File path" to dash(nasPath),
        "Lyrics" to lyricsLabel,
        "Album art" to dash(artDetails.albumArtSource),
        "Album art URL" to dash(artDetails.albumArtUrl),
        "Track art" to dash(artDetails.trackArtSource),
        "Track art URL" to dash(artDetails.trackArtUrl),
        "Local cover tried" to dash(artDetails.localCoverPath?.replace('\\', '/')),
    )
    val techChips = listOfNotNull(
        format?.let { "FORMAT" to it },
        bitrate?.let { "BITRATE" to "$it kbps" },
        rate?.let {
            "SAMPLE" to "${String.format(java.util.Locale.US, "%.1f", it / 1000f)} kHz"
        },
        tags?.bitsPerSample?.let { "DEPTH" to "$it-bit" },
        channelLabel?.let { "CHANNELS" to it },
        track?.fileSize?.takeIf { it > 0 }?.let { "SIZE" to formatFileSize(it) },
        durationMs?.let { "LENGTH" to formatTime(it) },
    )

    MusicModalOverlay(onDismiss = onDismiss, scrimAlpha = 0.82f, windowed = false) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1A1A22), Color(0xFF0E0E14), Color(0xFF08080C)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(maxWidth * 0.45f)
                    .graphicsLayer { alpha = 0.22f }
                    .background(
                        Brush.radialGradient(
                            listOf(AudioAccent.copy(alpha = 0.35f), Color.Transparent),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "TRACK INFO",
                        color = AudioAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily = PallasFontFamily,
                    )
                    Spacer(Modifier.weight(1f))
                    ModePill(
                        label = "Close",
                        selected = true,
                        focusRequester = closeFocus,
                        onClick = onDismiss,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF121018))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                    ) {
                        AlbumArt(
                            title = title,
                            imageUrl = coverUrl,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = PallasFontFamily,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = (-0.4).sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            artist,
                            color = AudioAccent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = PallasFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(
                                album.takeIf { it != "—" },
                                (tags?.year ?: track?.year)?.toString(),
                                trackLabel?.let { "Track $it" },
                            ).joinToString("  ·  ").ifBlank { "—" },
                            color = AudioTextMuted,
                            fontSize = 13.sp,
                            fontFamily = PallasFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (techChips.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        techChips.forEach { (label, value) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    color = AudioAccent.copy(alpha = 0.75f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.9.sp,
                                    fontFamily = PallasFontFamily,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    value,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = PallasFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.10f)),
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TrackInfoColumn("METADATA", identity, Modifier.weight(1f))
                    TrackInfoColumn("FILE / TECHNICAL", technical, Modifier.weight(1.15f))
                    TrackInfoColumn("CREDITS & TAGS", credits, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.10f)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "PATHS & ART",
                    color = AudioAccent.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    fontFamily = PallasFontFamily,
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    paths.forEach { (label, value) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                label,
                                color = AudioTextMuted.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                fontFamily = PallasFontFamily,
                                modifier = Modifier.width(118.dp),
                            )
                            Text(
                                value,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                fontFamily = PallasFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInfoColumn(
    heading: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            heading,
            color = AudioAccent.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = PallasFontFamily,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        label,
                        color = AudioTextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        value,
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = PallasFontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatModifiedTime(epochMs: Long): String? {
    if (epochMs <= 0L) return null
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(epochMs))
    }.getOrNull()
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}

@Composable
private fun LeaveMusicSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40)
        runCatching { firstFocus.requestFocus() }
    }
    MusicModalOverlay(onDismiss = onDismiss, scrimAlpha = 0.72f, windowed = false) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF16161C))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(18.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LEAVE MUSIC?",
                color = AudioAccent.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Playback will stop and you’ll return to the browser.",
                color = AudioTextMuted,
                fontSize = 14.sp,
                fontFamily = PallasFontFamily,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            ModePill(
                label = "Stay in Music",
                selected = false,
                focusRequester = firstFocus,
                onClick = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            ModePill(
                label = "Leave",
                selected = false,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun ImmersiveStage(
    playerState: PlayerUiState,
    albumArtUrl: Any?,
    trackArtUrl: Any?,
    title: String,
    artist: String,
    albumArtist: String? = null,
    album: String?,
    trackNumber: Int?,
    discNumber: Int? = null,
    showDiscNumber: Boolean = false,
    year: Int?,
    formatBadge: String?,
    lyrics: List<LyricLine>,
    lyricsVisible: Boolean,
    controlsEnabled: Boolean,
    metaEnabled: Boolean,
    playFocusRequester: FocusRequester,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: () -> Unit,
    onAlbumArtistClick: () -> Unit = {},
    onAlbumClick: () -> Unit,
    onYearClick: () -> Unit,
) {
    val duration = playerState.durationMs.takeIf { it > 0 } ?: playerState.track?.durationMs ?: 0L
    val position = playerState.positionMs.coerceAtMost(duration.coerceAtLeast(0))
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val foregroundArt = trackArtUrl ?: albumArtUrl
    val backgroundArt = albumArtUrl ?: trackArtUrl
    val prevFocus = remember { FocusRequester() }
    val seekBackFocus = remember { FocusRequester() }
    val seekForwardFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var keepTransport by remember { mutableStateOf(TransportFocus.Play) }
    var transportFocusEpoch by remember { mutableIntStateOf(0) }
    val trackId = playerState.track?.id

    fun pinTransport(target: TransportFocus) {
        keepTransport = target
        transportFocusEpoch++
    }

    LaunchedEffect(trackId, controlsEnabled, keepTransport, transportFocusEpoch) {
        if (!controlsEnabled) return@LaunchedEffect
        kotlinx.coroutines.delay(48)
        runCatching {
            when (keepTransport) {
                TransportFocus.Prev -> prevFocus.requestFocus()
                TransportFocus.SeekBack -> seekBackFocus.requestFocus()
                TransportFocus.Play -> playFocusRequester.requestFocus()
                TransportFocus.SeekForward -> seekForwardFocus.requestFocus()
                TransportFocus.Next -> nextFocus.requestFocus()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val scale = (maxWidth.value / 1180f).coerceIn(0.58f, 1f)
        // Clear the top icon row (Browse / Queue / …) so title never sits under it.
        val topChrome = 104.dp
        val bottomChrome = 20.dp
        val hPad = (40.dp * scale).coerceAtLeast(16.dp)
        val gap = (36.dp * scale).coerceAtLeast(16.dp)
        val titleSize = when {
            title.length > 48 -> (30f * scale).coerceIn(18f, 34f).sp
            title.length > 28 -> (34f * scale).coerceIn(20f, 38f).sp
            else -> (40f * scale).coerceIn(22f, 44f).sp
        }
        val artistSize = (22f * scale).coerceIn(15f, 24f).sp
        val metaSize = (14f * scale).coerceIn(12f, 15f).sp
        val timeSize = (13f * scale).coerceIn(11f, 14f).sp
        val skipSize = (56.dp * scale).coerceAtLeast(44.dp)
        val playSize = (88.dp * scale).coerceAtLeast(60.dp)
        val skipIcon = (32.dp * scale).coerceAtLeast(24.dp)
        val playIcon = (46.dp * scale).coerceAtLeast(30.dp)
        val artSize = minOf(
            (maxHeight - topChrome - bottomChrome - 12.dp) * 0.88f,
            maxWidth * 0.34f,
            380.dp * scale,
        ).coerceIn(120.dp, 380.dp)

        Box(Modifier.fillMaxSize().background(Color(0xFF050507)))
        if (backgroundArt != null) {
            AsyncImage(
                model = backgroundArt,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.1f
                        scaleY = 1.1f
                        alpha = 0.42f
                    }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xE6050507), Color(0x99050507), Color(0xCC050507)),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xBB050507), Color(0x55050507), Color(0xF0050507)),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChrome, bottom = bottomChrome)
                .padding(horizontal = hPad)
                .focusProperties { canFocus = controlsEnabled },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            // Artwork
            Box(
                modifier = Modifier
                    .size(artSize)
                    .graphicsLayer {
                        shadowElevation = 18f
                        shape = RoundedCornerShape(10.dp)
                        clip = true
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF121018))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (playerState.track != null || foregroundArt != null) {
                    AlbumArt(
                        title = title,
                        imageUrl = foregroundArt,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        null,
                        tint = AudioAccent.copy(alpha = 0.5f),
                        modifier = Modifier.size(artSize * 0.3f),
                    )
                }
                if (!formatBadge.isNullOrBlank()) {
                    Text(
                        formatBadge,
                        color = Color(0xFF0A0804),
                        fontSize = (11f * scale).coerceIn(9f, 12f).sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AudioAccent.copy(alpha = 0.95f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                // Track title — white; OK searches matching songs in Browse
                MetaLink(
                    text = title,
                    color = Color.White,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    enabled = metaEnabled &&
                        !title.equals("Nothing playing", ignoreCase = true),
                    onClick = onTitleClick,
                )
                Spacer(Modifier.height((12.dp * scale).coerceAtLeast(8.dp)))
                // Artist — gold; OK opens that artist's albums in Browse
                MetaLink(
                    text = artist,
                    color = AudioAccent,
                    fontSize = artistSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    enabled = metaEnabled &&
                        !artist.equals("Browse to start listening", ignoreCase = true) &&
                        !artist.equals("Unknown artist", ignoreCase = true),
                    onClick = onArtistClick,
                )
                // Album artist — only when it differs from track artist
                if (!albumArtist.isNullOrBlank()) {
                    Spacer(Modifier.height((4.dp * scale).coerceAtLeast(2.dp)))
                    MetaLink(
                        text = albumArtist,
                        color = AudioAccent.copy(alpha = 0.72f),
                        fontSize = metaSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        enabled = metaEnabled,
                        onClick = onAlbumArtistClick,
                    )
                }
                // Album name only (never " | Track N") — OK opens matching albums
                if (!album.isNullOrBlank()) {
                    Spacer(Modifier.height((8.dp * scale).coerceAtLeast(4.dp)))
                    MetaLink(
                        text = album,
                        color = AudioTextMuted,
                        fontSize = metaSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        enabled = metaEnabled,
                        onClick = onAlbumClick,
                    )
                }
                // Track / disc + year — Track text uses same 4.dp inset as MetaLink
                if (trackNumber != null || (year != null && year > 0)) {
                    Spacer(Modifier.height((6.dp * scale).coerceAtLeast(3.dp)))
                    val trackLabel = when {
                        trackNumber == null -> null
                        showDiscNumber && (discNumber ?: 0) > 0 ->
                            "Disc $discNumber · Track $trackNumber"
                        else -> "Track $trackNumber"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (trackLabel != null) {
                            Text(
                                trackLabel,
                                color = AudioTextMuted.copy(alpha = 0.75f),
                                fontSize = metaSize,
                                fontWeight = FontWeight.Medium,
                                fontFamily = PallasFontFamily,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        if (year != null && year > 0) {
                            MetaLink(
                                text = year.toString(),
                                color = AudioTextMuted.copy(alpha = 0.9f),
                                fontSize = metaSize,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                enabled = metaEnabled,
                                onClick = onYearClick,
                            )
                        }
                    }
                }

                if (lyricsVisible) {
                    Spacer(Modifier.height((12.dp * scale).coerceAtLeast(8.dp)))
                    SyncedLyricsPanel(
                        lyrics = lyrics,
                        positionMs = position,
                        scale = scale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                            .heightIn(min = 120.dp, max = 280.dp * scale),
                    )
                    Spacer(Modifier.height((12.dp * scale).coerceAtLeast(8.dp)))
                } else {
                    Spacer(Modifier.height((28.dp * scale).coerceAtLeast(16.dp)))
                }

                // Progress — bar full width; times share the row under it
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((6.dp * scale).coerceAtLeast(4.dp))
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(AudioAccentWarm, AudioAccent, Color(0xFFFFE08A)),
                                ),
                            ),
                    )
                }
                Spacer(Modifier.height((8.dp * scale).coerceAtLeast(5.dp)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(position),
                        color = AudioTextMuted,
                        fontSize = timeSize,
                        fontFamily = PallasFontFamily,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        formatTime(duration),
                        color = AudioTextMuted,
                        fontSize = timeSize,
                        fontFamily = PallasFontFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height((26.dp * scale).coerceAtLeast(14.dp)))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val btnGap = (18.dp * scale).coerceAtLeast(10.dp)
                    TransportButton(
                        enabled = controlsEnabled,
                        highlighted = keepTransport == TransportFocus.Prev,
                        focusRequester = prevFocus,
                        onFocused = { keepTransport = TransportFocus.Prev },
                        onClick = {
                            pinTransport(TransportFocus.Prev)
                            onPrev()
                        },
                        size = skipSize,
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            "Previous track",
                            tint = Color.White,
                            modifier = Modifier.size(skipIcon),
                        )
                    }
                    Spacer(Modifier.width(btnGap))
                    TransportButton(
                        enabled = controlsEnabled,
                        highlighted = keepTransport == TransportFocus.SeekBack,
                        focusRequester = seekBackFocus,
                        onFocused = { keepTransport = TransportFocus.SeekBack },
                        onClick = {
                            pinTransport(TransportFocus.SeekBack)
                            onSeekBack()
                        },
                        size = skipSize,
                    ) {
                        Icon(
                            Icons.Filled.Replay10,
                            "Back 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(skipIcon),
                        )
                    }
                    Spacer(Modifier.width(btnGap))
                    val playHighlighted = keepTransport == TransportFocus.Play
                    var playFocused by remember { mutableStateOf(false) }
                    val playShowFocus = playFocused || playHighlighted
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .focusRequester(playFocusRequester)
                            .size(playSize)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFFFFE08A), AudioAccent, Color(0xFFB8860B)),
                                ),
                            )
                            .border(
                                width = if (playShowFocus) 3.dp else 2.dp,
                                color = if (playShowFocus) {
                                    FocusRing
                                } else {
                                    Color.White.copy(alpha = 0.35f)
                                },
                                shape = CircleShape,
                            )
                            .onFocusChanged {
                                playFocused = it.isFocused
                                if (it.isFocused) keepTransport = TransportFocus.Play
                            }
                            .clickable(
                                enabled = controlsEnabled,
                                role = Role.Button,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    pinTransport(TransportFocus.Play)
                                    onTogglePlay()
                                },
                            )
                            .focusProperties { canFocus = controlsEnabled }
                            .focusable(enabled = controlsEnabled),
                    ) {
                        Icon(
                            if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playerState.isPlaying) "Pause" else "Play",
                            tint = Color(0xFF1A1206),
                            modifier = Modifier.size(playIcon),
                        )
                    }
                    Spacer(Modifier.width(btnGap))
                    TransportButton(
                        enabled = controlsEnabled,
                        highlighted = keepTransport == TransportFocus.SeekForward,
                        focusRequester = seekForwardFocus,
                        onFocused = { keepTransport = TransportFocus.SeekForward },
                        onClick = {
                            pinTransport(TransportFocus.SeekForward)
                            onSeekForward()
                        },
                        size = skipSize,
                    ) {
                        Icon(
                            Icons.Filled.Forward10,
                            "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(skipIcon),
                        )
                    }
                    Spacer(Modifier.width(btnGap))
                    TransportButton(
                        enabled = controlsEnabled,
                        highlighted = keepTransport == TransportFocus.Next,
                        focusRequester = nextFocus,
                        onFocused = { keepTransport = TransportFocus.Next },
                        onClick = {
                            pinTransport(TransportFocus.Next)
                            onNext()
                        },
                        size = skipSize,
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            "Next track",
                            tint = Color.White,
                            modifier = Modifier.size(skipIcon),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused || highlighted
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    showFocus -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
            )
            .border(
                width = if (showFocus) 2.dp else 1.dp,
                color = if (showFocus) FocusRing else Color.White.copy(alpha = 0.2f),
                shape = CircleShape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusProperties { canFocus = enabled }
            .focusable(enabled = enabled)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        content()
    }
}

@Composable
private fun BrowseDrawer(
    mode: BrowseMode,
    onMode: (BrowseMode) -> Unit,
    folderPath: String,
    musicRoot: String,
    musicPaths: List<String>,
    artists: List<ArtistEntity>,
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    indexing: Boolean,
    indexProgress: Float = 0f,
    indexMessage: String? = null,
    indexError: String? = null,
    indexTrackCount: Int = 0,
    indexBuiltAtMs: Long = 0L,
    onSync: () -> Unit,
    viewModel: MusicViewModel,
    onOpenFolder: (String) -> Unit,
    onClose: () -> Unit,
    onPlayed: () -> Unit,
    listFocusRequester: FocusRequester,
    artistsListState: LazyListState,
    albumsListState: LazyListState,
    songsListState: LazyListState,
    folderScrollPositions: MutableMap<String, Pair<Int, Int>>,
    folderReturnFocus: MutableMap<String, String>,
    artistDrill: ArtistEntity?,
    onArtistDrill: (ArtistEntity?) -> Unit,
    albumYearFilter: Int?,
    onClearAlbumYearFilter: () -> Unit,
    albumTitleFilter: String?,
    onClearAlbumTitleFilter: () -> Unit,
    performerAlbumFilter: String?,
    onClearPerformerAlbumFilter: () -> Unit,
    songTitleFilter: String?,
    onClearSongTitleFilter: () -> Unit,
    artistNameFilter: String? = null,
    onClearArtistNameFilter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var libraryAddTarget by remember { mutableStateOf<BrowseAddTarget?>(null) }
    var jumpVisible by remember { mutableStateOf(false) }
    // Prefer list readiness for the active filter. Tracks live in the app-scoped
    // MusicLibraryCache — once warm, song search must not flash a loading state.
    val confirmedEmptyLibrary =
        !indexing && indexError == null && indexBuiltAtMs > 0L && indexTrackCount == 0
    val tracksPending =
        tracks.isEmpty() && !confirmedEmptyLibrary && indexError == null
    val artistsPending =
        artists.isEmpty() && !confirmedEmptyLibrary && indexError == null
    val albumsPending =
        albums.isEmpty() && !confirmedEmptyLibrary && indexError == null
    val libraryEmpty = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
    val songSearchWaiting = tracksPending
    val artistSearchWaiting = artistsPending
    val albumSearchWaiting = albumsPending || (tracksPending && (albumTitleFilter != null || performerAlbumFilter != null))
    val albumsForBrowse = remember(
        albums,
        tracks,
        albumYearFilter,
        albumTitleFilter,
        performerAlbumFilter,
    ) {
        when {
            performerAlbumFilter != null ->
                albumsForPerformer(albums, tracks, performerAlbumFilter)
            else ->
                albumsMatchingName(
                    albums = albums,
                    tracks = tracks,
                    titleQuery = albumTitleFilter,
                    year = albumYearFilter,
                )
        }.also { matched ->
            val label = performerAlbumFilter ?: albumTitleFilter
            if (label != null) {
                android.util.Log.i(
                    "PallasMusic",
                    "album match '$label': ${matched.size} albums " +
                        matched.take(8).joinToString { "${it.artistName}/${it.title}(${it.trackCount})" },
                )
            }
        }
    }
    val songsForBrowse = remember(tracks, songTitleFilter) {
        songTitleFilter?.let { q ->
            tracksMatchingTitle(tracks, q).also { matched ->
                android.util.Log.i(
                    "PallasMusic",
                    "song match '$q': ${matched.size} tracks",
                )
            }
        }
    }

    LaunchedEffect(mode) {
        jumpVisible = false
    }

    BackHandler(enabled = libraryAddTarget != null) {
        libraryAddTarget = null
    }

    BackHandler(
        enabled = !modalBlocksBack() &&
            artistDrill != null &&
            albumTitleFilter == null &&
            performerAlbumFilter == null &&
            songTitleFilter == null &&
            libraryAddTarget == null,
    ) {
        onArtistDrill(null)
        jumpVisible = false
    }

    BackHandler(
        enabled = !modalBlocksBack() &&
            libraryAddTarget == null &&
            (
                albumTitleFilter != null ||
                    performerAlbumFilter != null ||
                    songTitleFilter != null ||
                    artistNameFilter != null
                ),
    ) {
        onClearAlbumTitleFilter()
        onClearAlbumYearFilter()
        onClearPerformerAlbumFilter()
        onClearSongTitleFilter()
        onClearArtistNameFilter()
    }

    Box(modifier = modifier) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF8121218), Color(0xF50A0A0E)),
                ),
            )
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.07f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    songTitleFilter != null -> "MATCHING SONGS"
                    artistNameFilter != null -> "MATCHING ARTISTS"
                    performerAlbumFilter != null -> "ARTIST ALBUMS"
                    albumTitleFilter != null -> "MATCHING ALBUMS"
                    else -> "BROWSE"
                },
                color = AudioAccent.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.weight(1f))
            PanelIconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (albumTitleFilter == null &&
            performerAlbumFilter == null &&
            songTitleFilter == null &&
            artistNameFilter == null
        ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(BrowseMode.entries) { m ->
                val supportsJump = m == BrowseMode.Artists || m == BrowseMode.Albums
                ModePill(
                    label = m.label,
                    selected = mode == m && artistDrill == null && m != BrowseMode.Random,
                    onClick = {
                        if (m == BrowseMode.Random) {
                            viewModel.playRandomTracks(100)
                            onPlayed()
                            return@ModePill
                        }
                        onArtistDrill(null)
                        if (mode != m) jumpVisible = false
                        onMode(m)
                    },
                    onLongClick = if (supportsJump) {
                        {
                            if (mode != m) {
                                onArtistDrill(null)
                                onMode(m)
                                jumpVisible = true
                            } else {
                                jumpVisible = !jumpVisible
                            }
                        }
                    } else {
                        null
                    },
                    focusRequester = if (
                        m == mode &&
                        artistDrill == null &&
                        m != BrowseMode.Random
                    ) {
                        listFocusRequester
                    } else {
                        null
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        }

        when {
            // Player track-name click: songs matching that title.
            songTitleFilter != null -> {
                val matched = songsForBrowse.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "“$songTitleFilter”",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = PallasFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ModePill(
                        label = "Clear",
                        selected = false,
                        focusRequester = listFocusRequester,
                        onClick = onClearSongTitleFilter,
                    )
                }
                if (matched.isEmpty()) {
                    EmptyBrowseHint(
                        message = when {
                            indexError != null -> indexError
                            songSearchWaiting -> indexMessage?.takeIf { it.isNotBlank() }
                                ?: "Searching library…"
                            else -> "No songs named “$songTitleFilter”"
                        },
                        waiting = songSearchWaiting,
                        progress = indexProgress,
                        statusMessage = indexMessage,
                        showSyncAction = !songSearchWaiting && (libraryEmpty || indexError != null),
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "${matched.size} song${if (matched.size == 1) "" else "s"}",
                        color = AudioTextMuted,
                        fontSize = 11.sp,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                    LazyColumn(
                        state = songsListState,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    ) {
                        items(matched, key = { it.id }) { track ->
                            BrowseRow(
                                title = track.title,
                                subtitle = "${queueArtist(track)} · ${track.albumTitle}",
                                onClick = {
                                    viewModel.playTrack(track)
                                    onPlayed()
                                },
                                onLongClick = {
                                    libraryAddTarget = BrowseAddTarget.Track(track)
                                },
                            )
                        }
                    }
                }
            }
            // Player album-name or performer click: albums only.
            albumTitleFilter != null || performerAlbumFilter != null -> {
                val filterLabel = performerAlbumFilter ?: albumTitleFilter.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "“$filterLabel”",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = PallasFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ModePill(
                        label = "Clear",
                        selected = false,
                        focusRequester = listFocusRequester,
                        onClick = {
                            onClearAlbumTitleFilter()
                            onClearAlbumYearFilter()
                            onClearPerformerAlbumFilter()
                        },
                    )
                }
                if (albumsForBrowse.isEmpty()) {
                    EmptyBrowseHint(
                        message = when {
                            indexError != null -> indexError
                            albumSearchWaiting -> indexMessage?.takeIf { it.isNotBlank() }
                                ?: "Searching library…"
                            performerAlbumFilter != null ->
                                "No albums featuring “$performerAlbumFilter”"
                            else -> "No albums named “$albumTitleFilter”"
                        },
                        waiting = albumSearchWaiting,
                        progress = indexProgress,
                        statusMessage = indexMessage,
                        showSyncAction = !albumSearchWaiting && (libraryEmpty || indexError != null),
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "${albumsForBrowse.size} album${if (albumsForBrowse.size == 1) "" else "s"}",
                        color = AudioTextMuted,
                        fontSize = 11.sp,
                        fontFamily = PallasFontFamily,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                    LazyColumn(
                        state = albumsListState,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    ) {
                        items(albumsForBrowse, key = { it.albumId + "\u0000" + it.artistName }) { album ->
                            BrowseRow(
                                title = album.title,
                                subtitle = listOfNotNull(
                                    album.artistName,
                                    album.year?.toString(),
                                    "${album.trackCount} tracks in album",
                                ).joinToString(" · "),
                                onClick = {
                                    libraryAddTarget =
                                        BrowseAddTarget.Album(album.albumId, album.title)
                                },
                                onLongClick = {
                                    libraryAddTarget =
                                        BrowseAddTarget.Album(album.albumId, album.title)
                                },
                            )
                        }
                    }
                }
            }
            mode == BrowseMode.Folders -> FolderBrowser(
                path = folderPath,
                musicRoot = musicRoot,
                musicPaths = musicPaths,
                listFocusRequester = listFocusRequester,
                viewModel = viewModel,
                onOpenFolder = onOpenFolder,
                onPlayed = onPlayed,
                scrollPositions = folderScrollPositions,
                returnFocusPaths = folderReturnFocus,
                modifier = Modifier.weight(1f),
            )
            artistDrill != null && mode == BrowseMode.Artists -> {
                val artist = artistDrill!!
                val artistAlbums by viewModel.libraryAlbumsForArtist(artist.id)
                    .collectAsState(initial = emptyList())
                val artistTracks by viewModel.libraryTracksForArtist(artist.id)
                    .collectAsState(initial = emptyList())
                ArtistAlbumsBrowse(
                    artist = artist,
                    albums = artistAlbums,
                    tracks = artistTracks,
                    indexing = indexing,
                    onSync = onSync,
                    listFocusRequester = listFocusRequester,
                    onBack = {
                        onArtistDrill(null)
                        jumpVisible = false
                    },
                    onPlayAll = {
                        viewModel.playArtist(artist)
                        onPlayed()
                    },
                    onAddAlbum = { id, title ->
                        libraryAddTarget = BrowseAddTarget.Album(id, title)
                    },
                    onPlayTrack = { track ->
                        viewModel.playTracks(listOf(track))
                        onPlayed()
                    },
                    onAddTrack = { track ->
                        libraryAddTarget = BrowseAddTarget.Track(track)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            mode == BrowseMode.Artists -> {
                val artistsForBrowse = remember(artists, artistNameFilter) {
                    val q = artistNameFilter?.trim()?.takeIf { it.isNotEmpty() }
                    if (q == null) {
                        artists
                    } else {
                        artists.filter {
                            it.name.equals(q, ignoreCase = true) ||
                                it.name.contains(q, ignoreCase = true)
                        }
                    }
                }
                if (artistNameFilter != null && artistDrill == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "“$artistNameFilter”",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = PallasFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ModePill(
                            label = "Clear",
                            selected = false,
                            focusRequester = listFocusRequester,
                            onClick = onClearArtistNameFilter,
                        )
                    }
                }
                if (artistsForBrowse.isEmpty()) {
                    EmptyBrowseHint(
                        message = when {
                            indexError != null -> indexError
                            artistSearchWaiting -> indexMessage?.takeIf { it.isNotBlank() }
                                ?: "Searching library…"
                            artistNameFilter != null ->
                                "No artists named “$artistNameFilter”"
                            else -> "No artists yet"
                        },
                        waiting = artistSearchWaiting,
                        progress = indexProgress,
                        statusMessage = indexMessage,
                        showSyncAction = !artistSearchWaiting && (libraryEmpty || indexError != null),
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    JumpableList(
                        items = artistsForBrowse,
                        itemKey = { it.id },
                        jumpModes = listOf(LibraryJumpMode.Alpha),
                        defaultJump = LibraryJumpMode.Alpha,
                        jumpVisible = jumpVisible,
                        listState = artistsListState,
                        letterOf = { it.name },
                        yearOf = { null },
                        artistLetterOf = { it.name },
                        modifier = Modifier.weight(1f),
                    ) { artist, _ ->
                        BrowseRow(
                            title = artist.name,
                            subtitle = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                            onClick = {
                                jumpVisible = false
                                onArtistDrill(artist)
                            },
                            onLongClick = {
                                libraryAddTarget = BrowseAddTarget.Artist(artist)
                            },
                        )
                    }
                }
            }
            mode == BrowseMode.Albums -> {
                if (albumYearFilter != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Year $albumYearFilter",
                            color = AudioAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = PallasFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ModePill(
                            label = "Clear",
                            selected = false,
                            onClick = onClearAlbumYearFilter,
                        )
                    }
                }
                if (albumsForBrowse.isEmpty()) {
                    EmptyBrowseHint(
                        message = when {
                            indexError != null -> indexError
                            albumSearchWaiting -> indexMessage?.takeIf { it.isNotBlank() }
                                ?: "Loading library…"
                            albumYearFilter != null -> "No albums for $albumYearFilter"
                            else -> "No albums yet"
                        },
                        waiting = albumSearchWaiting,
                        progress = indexProgress,
                        statusMessage = indexMessage,
                        showSyncAction = !albumSearchWaiting && (libraryEmpty || indexError != null),
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    JumpableList(
                        items = albumsForBrowse,
                        itemKey = { it.albumId },
                        jumpModes = listOf(
                            LibraryJumpMode.Alpha,
                            LibraryJumpMode.Year,
                            LibraryJumpMode.Artist,
                        ),
                        defaultJump = LibraryJumpMode.Alpha,
                        jumpVisible = jumpVisible,
                        listState = albumsListState,
                        letterOf = { it.title },
                        yearOf = { it.year },
                        artistLetterOf = { it.artistName },
                        modifier = Modifier.weight(1f),
                    ) { album, _ ->
                        BrowseRow(
                            title = album.title,
                            subtitle = listOfNotNull(album.artistName, album.year?.toString())
                                .joinToString(" · "),
                            onClick = {
                                libraryAddTarget = BrowseAddTarget.Album(album.albumId, album.title)
                            },
                            onLongClick = {
                                libraryAddTarget = BrowseAddTarget.Album(album.albumId, album.title)
                            },
                        )
                    }
                }
            }
        }
        Text(
            when {
                albumTitleFilter != null || performerAlbumFilter != null ->
                    "OK · Clear or append full album · Back clears"
                songTitleFilter != null ->
                    "OK plays · Hold OK for add · Back clears"
                mode == BrowseMode.Folders -> "OK opens · Hold OK for add options"
                mode == BrowseMode.Albums ->
                    "OK · Clear or append full album · Hold Albums for jump"
                artistDrill != null ->
                    "OK plays all / album / track · Hold OK adds · Back"
                mode == BrowseMode.Artists ->
                    "OK opens albums · Hold OK for add · Hold Artists for jump"
                mode == BrowseMode.Random ->
                    "OK picks 100 random tracks · clears playlist"
                else -> "OK plays · Hold OK for add options"
            },
            color = AudioTextMuted.copy(alpha = 0.75f),
            fontSize = 10.sp,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }

        libraryAddTarget?.let { target ->
            PlaylistAddSheet(
                title = target.label,
                onReplace = {
                    when (target) {
                        is BrowseAddTarget.Artist ->
                            viewModel.addArtistToPlaylist(target.artist, replace = true)
                        is BrowseAddTarget.Album ->
                            viewModel.addAlbumToPlaylist(target.albumId, replace = true)
                        is BrowseAddTarget.Track ->
                            viewModel.addTrackToPlaylist(target.track, replace = true)
                        else -> Unit
                    }
                    libraryAddTarget = null
                    onPlayed()
                },
                onAppend = {
                    when (target) {
                        is BrowseAddTarget.Artist ->
                            viewModel.addArtistToPlaylist(target.artist, replace = false)
                        is BrowseAddTarget.Album ->
                            viewModel.addAlbumToPlaylist(target.albumId, replace = false)
                        is BrowseAddTarget.Track ->
                            viewModel.addTrackToPlaylist(target.track, replace = false)
                        else -> Unit
                    }
                    libraryAddTarget = null
                },
                onDismiss = { libraryAddTarget = null },
            )
        }
    }
}

/** Songs whose title matches the player track-name search. */
private fun tracksMatchingTitle(
    tracks: List<TrackEntity>,
    query: String,
): List<TrackEntity> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return emptyList()
    val exact = mutableListOf<TrackEntity>()
    val starts = mutableListOf<TrackEntity>()
    val contains = mutableListOf<TrackEntity>()
    for (t in tracks) {
        val title = t.title.trim()
        when {
            title.equals(wanted, ignoreCase = true) -> exact += t
            title.startsWith(wanted, ignoreCase = true) -> starts += t
            title.contains(wanted, ignoreCase = true) -> contains += t
        }
    }
    return (exact + starts + contains)
        .distinctBy { it.id }
        .sortedWith(
            compareBy(
                { it.artistName.lowercase() },
                { it.albumTitle.lowercase() },
                { it.trackNumber ?: Int.MAX_VALUE },
            ),
        )
}

/** Compact format badge for the player (MP3, FLAC, M4A, …). */
private fun audioFormatBadge(
    nasPath: String?,
    mimeType: String?,
    codec: String?,
): String? {
    val ext = nasPath
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.length in 2..5 }
    val fromExt = when (ext) {
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "m4a", "mp4" -> "M4A"
        "aac" -> "AAC"
        "wav" -> "WAV"
        "aiff", "aif" -> "AIFF"
        "ogg", "oga" -> "OGG"
        "opus" -> "OPUS"
        "wma" -> "WMA"
        "alac" -> "ALAC"
        "dsf", "dff" -> "DSD"
        "ape" -> "APE"
        "wv" -> "WV"
        "mka" -> "MKA"
        else -> ext?.uppercase()
    }
    if (fromExt != null) return fromExt

    val mime = mimeType?.lowercase().orEmpty()
    when {
        "flac" in mime -> return "FLAC"
        "mpeg" in mime || "mp3" in mime -> return "MP3"
        "mp4" in mime || "m4a" in mime || "aac" in mime -> return "M4A"
        "wav" in mime || "wave" in mime -> return "WAV"
        "ogg" in mime -> return "OGG"
        "opus" in mime -> return "OPUS"
        "aiff" in mime -> return "AIFF"
    }

    val c = codec?.trim()?.uppercase().orEmpty()
    return when {
        c.contains("FLAC") -> "FLAC"
        c.contains("MP3") || c.contains("MPEG") || c.contains("LAME") -> "MP3"
        c.contains("AAC") || c.contains("ALAC") -> if (c.contains("ALAC")) "ALAC" else "AAC"
        c.contains("OPUS") -> "OPUS"
        c.contains("VORBIS") -> "OGG"
        c.contains("PCM") || c.contains("WAV") -> "WAV"
        c.isNotBlank() -> c.take(8)
        else -> null
    }
}

private fun estimatedBitrateKbps(fileSize: Long, durationMs: Long): Int? {
    if (fileSize <= 0L || durationMs <= 0L) return null
    val seconds = durationMs / 1000.0
    if (seconds <= 0.0) return null
    return ((fileSize * 8.0) / seconds / 1000.0).toInt().takeIf { it in 32..10_000 }
}

/** Albums that include tracks by this performer (not albumArtist / Various Artists). */
private fun albumsForPerformer(
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    performer: String,
): List<AlbumWithArtist> {
    val wanted = performer.trim()
    if (wanted.isEmpty()) return emptyList()

    fun folderOf(path: String): String =
        path.replace('\\', '/').substringBeforeLast('/').trimEnd('/').lowercase()

    fun trackMatches(t: TrackEntity): Boolean {
        val a = t.artistName.trim()
        if (a.equals(wanted, ignoreCase = true)) return true
        if (a.startsWith(wanted, ignoreCase = true)) return true
        // "Diana Ross & The Supremes" when user clicked "Diana Ross"
        if (a.contains(wanted, ignoreCase = true) && !isVariousArtistsName(a)) return true
        return false
    }

    return tracks
        .asSequence()
        .filter(::trackMatches)
        .groupBy { folderOf(it.nasPath) to it.albumTitle.trim().lowercase() }
        .filterKeys { (folder, title) -> folder.isNotBlank() && title.isNotBlank() }
        .map { (key, group) ->
            val (folder, _) = key
            val sample = group.first()
            val title = sample.albumTitle.trim()
            val existing = albums
                .filter { album ->
                    album.title.equals(title, ignoreCase = true) &&
                        (
                            album.folderPath?.let { folderOf(it) } == folder ||
                                group.any { it.albumId == album.albumId }
                            )
                }
                .maxByOrNull { it.trackCount }
            AlbumWithArtist(
                albumId = existing?.albumId ?: sample.albumId,
                title = existing?.title ?: title,
                artistId = existing?.artistId ?: sample.artistId,
                artistName = wanted,
                year = existing?.year ?: group.mapNotNull { it.year }.maxOrNull(),
                genre = existing?.genre ?: sample.genre,
                coverPath = existing?.coverPath,
                trackCount = group.size,
                folderPath = existing?.folderPath
                    ?: sample.nasPath.replace('\\', '/').substringBeforeLast('/'),
            )
        }
        .distinctBy { folderOf(it.folderPath.orEmpty()) + "\u0000" + it.title.lowercase() }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.year ?: 0 }))
}

private fun isVariousArtistsName(name: String): Boolean {
    val n = name.trim().lowercase()
    return n in setOf("various artists", "various artist", "various", "va", "v.a.", "v.a") ||
        n.startsWith("various artist")
}

/** One row per album folder + title. Never one row per track or per track-artist. */
private fun albumsMatchingName(
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    titleQuery: String?,
    year: Int?,
): List<AlbumWithArtist> {
    val q = titleQuery?.trim()?.takeIf { it.isNotEmpty() }
    if (q == null) {
        val list = if (year == null) albums else albums.filter { it.year == year }
        return list.sortedWith(
            compareBy({ it.artistName.lowercase() }, { it.title.lowercase() }),
        )
    }

    fun folderOf(path: String): String =
        path.replace('\\', '/').substringBeforeLast('/').trimEnd('/')

    fun folderOf(t: TrackEntity): String = folderOf(t.nasPath)

    fun rootKey(folder: String): String =
        com.vizvag.shieldvideo.music.data.albumRootFolder(folder).lowercase()

    // One physical album = album-root folder (CD1/CD2/… collapse to parent).
    val fromTracks = tracks
        .asSequence()
        .filter { it.albumTitle.equals(q, ignoreCase = true) }
        .groupBy { rootKey(folderOf(it)) }
        .filterKeys { it.isNotBlank() }
        .map { (root, group) ->
            val existing = albums
                .filter { album ->
                    album.title.equals(q, ignoreCase = true) &&
                        (
                            album.folderPath?.let { rootKey(it) } == root ||
                                group.any { it.albumId == album.albumId }
                            )
                }
                .maxByOrNull { it.trackCount }
            val artistLabel = group
                .mapNotNull { t ->
                    t.albumArtist?.takeIf { it.isNotBlank() } ?: t.artistName.takeIf { it.isNotBlank() }
                }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: queueArtist(group.first())
            val rootPath = com.vizvag.shieldvideo.music.data.albumRootFolder(
                group.first().nasPath.replace('\\', '/').substringBeforeLast('/'),
            )
            AlbumWithArtist(
                albumId = existing?.albumId ?: group.first().albumId,
                title = q,
                artistId = existing?.artistId ?: group.first().artistId,
                artistName = existing?.artistName ?: artistLabel,
                year = existing?.year ?: group.mapNotNull { it.year }.maxOrNull(),
                genre = existing?.genre ?: group.first().genre,
                coverPath = existing?.coverPath,
                trackCount = group.size,
                folderPath = existing?.folderPath?.let {
                    com.vizvag.shieldvideo.music.data.albumRootFolder(it)
                } ?: rootPath,
            )
        }

    // Albums table rows with this title — collapse disc folders to one row.
    val fromTable = albums
        .filter { it.title.equals(q, ignoreCase = true) }
        .groupBy { album ->
            album.folderPath?.let { rootKey(it) }?.takeIf { it.isNotBlank() }
                ?: album.albumId.lowercase()
        }
        .map { (_, group) ->
            val best = group.maxBy { it.trackCount }
            val totalTracks = group.sumOf { it.trackCount }
            best.copy(
                title = q,
                trackCount = maxOf(best.trackCount, totalTracks),
                folderPath = best.folderPath?.let {
                    com.vizvag.shieldvideo.music.data.albumRootFolder(it)
                },
            )
        }

    var list = (fromTable + fromTracks)
        .groupBy { album ->
            album.folderPath?.let { rootKey(it) }?.takeIf { it.isNotBlank() }
                ?: (album.artistName.lowercase() + "\u0000" + album.title.lowercase())
        }
        .map { (_, group) ->
            val best = group.maxBy { it.trackCount }
            best.copy(trackCount = group.maxOf { it.trackCount })
        }

    if (year != null) {
        list = list.filter { it.year == year }
    }
    return list.sortedWith(
        compareBy({ it.artistName.lowercase() }, { it.title.lowercase() }),
    )
}


private enum class LibraryJumpMode(val label: String) {
    Alpha("A–Z"),
    Year("Year"),
    Artist("Artist"),
}

@Composable
private fun <T> JumpableList(
    items: List<T>,
    itemKey: (T) -> Any,
    jumpModes: List<LibraryJumpMode>,
    defaultJump: LibraryJumpMode,
    jumpVisible: Boolean,
    listState: LazyListState,
    letterOf: (T) -> String,
    yearOf: (T) -> Int?,
    artistLetterOf: (T) -> String,
    modifier: Modifier = Modifier,
    row: @Composable (T, Int) -> Unit,
) {
    var jumpMode by remember(jumpModes, defaultJump) {
        mutableStateOf(jumpModes.firstOrNull { it == defaultJump } ?: jumpModes.first())
    }
    val scope = rememberCoroutineScope()

    val keys = remember(items, jumpMode) {
        when (jumpMode) {
            LibraryJumpMode.Alpha -> presentLetters(items.map(letterOf))
            LibraryJumpMode.Artist -> presentLetters(items.map(artistLetterOf))
            LibraryJumpMode.Year -> items.mapNotNull(yearOf).distinct().sortedDescending()
                .map { it.toString() }
        }
    }

    fun scrollToKey(key: String) {
        val index = when (jumpMode) {
            LibraryJumpMode.Alpha -> items.indexOfFirst { sortLetter(letterOf(it)) == key }
            LibraryJumpMode.Artist -> items.indexOfFirst { sortLetter(artistLetterOf(it)) == key }
            LibraryJumpMode.Year -> items.indexOfFirst { yearOf(it)?.toString() == key }
        }.coerceAtLeast(0)
        scope.launch {
            listState.scrollToItem(index)
        }
    }

    Column(modifier = modifier) {
        if (jumpVisible) {
            if (jumpModes.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(jumpModes) { m ->
                        ModePill(
                            label = "Jump · ${m.label}",
                            selected = jumpMode == m,
                            onClick = { jumpMode = m },
                        )
                    }
                }
            } else {
                Text(
                    "Jump · ${jumpMode.label}",
                    color = AudioTextMuted.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = PallasFontFamily,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
                )
            }
            if (keys.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(keys) { key ->
                        ModePill(
                            label = key,
                            selected = false,
                            onClick = { scrollToKey(key) },
                        )
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            itemsIndexed(items, key = { _, item -> itemKey(item) }) { index, item ->
                row(item, index)
            }
        }
    }
}

@Composable
private fun ArtistAlbumsBrowse(
    artist: ArtistEntity,
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    indexing: Boolean,
    onSync: () -> Unit,
    listFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onAddAlbum: (id: String, title: String) -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onAddTrack: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = remember(artist.id) { LazyListState() }
    val tracksByAlbum = remember(tracks) {
        tracks.groupBy { it.albumTitle?.takeIf { t -> t.isNotBlank() } ?: "Unknown album" }
    }

    LaunchedEffect(artist.id) {
        kotlinx.coroutines.delay(48)
        runCatching { listFocusRequester.requestFocus() }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModePill(label = "← Artists", selected = false, onClick = onBack)
            Text(
                artist.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (albums.isEmpty() && tracks.isEmpty()) {
            EmptyBrowseHint(
                message = if (indexing) "Loading library…" else "No music for this artist",
                waiting = indexing,
                progress = 0f,
                statusMessage = null,
                showSyncAction = !indexing,
                onSync = onSync,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                item(key = "__play_all__") {
                    BrowseRow(
                        title = "Play all tracks by ${artist.name}",
                        subtitle = "${tracks.size.coerceAtLeast(artist.trackCount)} tracks",
                        trailingIcon = Icons.Filled.PlayArrow,
                        focusRequester = listFocusRequester,
                        onClick = onPlayAll,
                        onLongClick = onPlayAll,
                    )
                }

                if (albums.isNotEmpty()) {
                    item(key = "__albums_header__") {
                        Text(
                            "ALBUMS",
                            color = AudioAccent.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            fontFamily = PallasFontFamily,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    items(albums, key = { "album_${it.albumId}" }) { album ->
                        BrowseRow(
                            title = album.title,
                            subtitle = listOfNotNull(
                                album.year?.toString(),
                                "${album.trackCount} tracks",
                            ).joinToString(" · "),
                            trailingIcon = Icons.Filled.Folder,
                            onClick = { onAddAlbum(album.albumId, album.title) },
                            onLongClick = { onAddAlbum(album.albumId, album.title) },
                        )
                    }
                }

                if (tracks.isNotEmpty()) {
                    item(key = "__tracks_header__") {
                        Text(
                            "TRACKS",
                            color = AudioAccent.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            fontFamily = PallasFontFamily,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    tracksByAlbum.forEach { (albumTitle, albumTracks) ->
                        val groupKey = albumTracks.firstOrNull()?.albumId ?: albumTitle
                        item(key = "group_$groupKey") {
                            Text(
                                albumTitle,
                                color = AudioTextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = PallasFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 8.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                        items(albumTracks, key = { it.id }) { track ->
                            val trackLabel = buildString {
                                track.trackNumber?.takeIf { it > 0 }?.let { append("%02d ".format(it)) }
                                append(track.title)
                            }
                            BrowseRow(
                                title = trackLabel,
                                subtitle = null,
                                trailingIcon = Icons.Filled.AudioFile,
                                onClick = { onPlayTrack(track) },
                                onLongClick = { onAddTrack(track) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sortLetter(name: String): String {
    val c = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}

private fun presentLetters(names: List<String>): List<String> {
    val set = names.map(::sortLetter).toSet()
    return buildList {
        if ("#" in set) add("#")
        ('A'..'Z').forEach { ch ->
            val s = ch.toString()
            if (s in set) add(s)
        }
    }
}

@Composable
private fun EmptyBrowseHint(
    message: String,
    waiting: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    statusMessage: String? = null,
    showSyncAction: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            if (waiting) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = AudioAccent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = statusMessage?.takeIf { it.isNotBlank() } ?: message,
                    color = AudioTextMuted,
                    fontSize = 15.sp,
                    fontFamily = PallasFontFamily,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                val barMod = Modifier
                    .fillMaxWidth(0.7f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                if (progress > 0.02f && progress < 0.995f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = barMod,
                        color = AudioAccent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = barMod,
                        color = AudioAccent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
            } else {
                Text(
                    text = message,
                    color = AudioTextMuted,
                    fontSize = 16.sp,
                    fontFamily = PallasFontFamily,
                    textAlign = TextAlign.Center,
                )
                if (showSyncAction) {
                    Spacer(Modifier.height(12.dp))
                    ModePill(
                        label = "Sync library",
                        selected = true,
                        onClick = onSync,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderBrowser(
    path: String,
    musicRoot: String,
    musicPaths: List<String>,
    listFocusRequester: FocusRequester,
    viewModel: MusicViewModel,
    onOpenFolder: (String) -> Unit,
    onPlayed: () -> Unit,
    scrollPositions: MutableMap<String, Pair<Int, Int>>,
    returnFocusPaths: MutableMap<String, String>,
    modifier: Modifier = Modifier,
) {
    // Soft refresh: keep the LazyColumn mounted across loads so D-pad focus is never
    // destroyed (unmounting focusables was dumping focus onto the nav rail).
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var addTarget by remember { mutableStateOf<BrowseAddTarget?>(null) }
    val listState = rememberLazyListState()
    val multiRoot = musicPaths.size > 1
    val atRoot = path.trimEnd('/') == musicRoot.trimEnd('/')
    val atConfiguredRoot = musicPaths.any { it.equals(path.trimEnd('/'), ignoreCase = true) }
    val parentPath = when {
        multiRoot && atConfiguredRoot -> musicRoot
        else -> path.trimEnd('/').substringBeforeLast('/', musicRoot).ifBlank { musicRoot }
    }
    val pathKey = path.trimEnd('/')
    val restorePath = remember(pathKey) {
        mutableStateOf(returnFocusPaths[pathKey]?.trimEnd('/'))
    }
    val childFocusRequester = remember(pathKey, restorePath.value) { FocusRequester() }

    LaunchedEffect(path, musicPaths) {
        loading = true
        error = null
        addTarget = null
        val focusWant = restorePath.value
        if (multiRoot && atRoot) {
            entries = musicPaths.map { root ->
                FileEntry(
                    path = root,
                    name = root.trimStart('/').ifBlank { root },
                    isdir = true,
                )
            }
            loading = false
        } else {
            val result = runCatching { viewModel.listFolder(path) }
            result.onSuccess { data ->
                entries = data.files
                    .filter { entry ->
                        when {
                            entry.isdir && NasPaths.isIgnoredDirectoryName(entry.name) -> false
                            entry.isdir -> true
                            else -> MetadataResolver.isAudioFile(entry.name)
                        }
                    }
                    .sortedWith(
                        compareByDescending<FileEntry> { it.isdir }.thenBy { it.name.lowercase() },
                    )
                loading = false
            }.onFailure {
                error = it.message ?: "Failed to list folder"
                loading = false
                entries = emptyList()
            }
        }
        val focusIdx = focusWant?.let { want ->
            entries.indexOfFirst { it.path.trimEnd('/') == want }
        }?.takeIf { it >= 0 }
        val leafExtra = if (
            !(multiRoot && atRoot) &&
            entries.none { it.isdir } &&
            entries.any { !it.isdir && MetadataResolver.isAudioFile(it.name) }
        ) {
            1
        } else {
            0
        }
        val headerOffset = (if (atRoot) 1 else 2) + leafExtra
        when {
            focusIdx != null -> listState.scrollToItem(focusIdx + headerOffset)
            else -> {
                val saved = scrollPositions[path] ?: scrollPositions[pathKey]
                if (saved != null) listState.scrollToItem(saved.first, saved.second)
                else listState.scrollToItem(0)
            }
        }
        // Only reclaim focus when returning from a child folder (Back). Mode pills own
        // listFocusRequester while browsing — do not yank focus after every load.
        if (focusIdx != null) {
            kotlinx.coroutines.delay(48)
            runCatching { childFocusRequester.requestFocus() }
            returnFocusPaths.remove(pathKey)
            restorePath.value = null
        }
    }

    DisposableEffect(path) {
        onDispose {
            scrollPositions[pathKey] =
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            scrollPositions[path] = scrollPositions[pathKey]!!
        }
    }

    val childFolders = remember(entries) { entries.filter { it.isdir } }
    val audioTracks = remember(entries) {
        entries.filter { !it.isdir && MetadataResolver.isAudioFile(it.name) }
    }
    // Virtual multi-root hub is never a leaf; otherwise no subfolders + has audio = leaf.
    val isLeafFolder = !(multiRoot && atRoot) &&
        childFolders.isEmpty() &&
        audioTracks.isNotEmpty()

    BackHandler(enabled = addTarget != null) {
        addTarget = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            error != null && entries.isEmpty() -> Box(
                modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, color = AudioTextMuted, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                item {
                    Text(
                        when {
                            loading && entries.isEmpty() -> "Loading…"
                            atRoot && multiRoot -> "Music folders"
                            atRoot -> "Library root"
                            multiRoot && atConfiguredRoot ->
                                path.trimStart('/').ifBlank { path }
                            else -> {
                                val root = NasSettings.rootForPath(path, musicPaths)
                                path.removePrefix(root).trimStart('/').ifBlank {
                                    path.removePrefix(musicRoot).trimStart('/')
                                }
                            }
                        },
                        color = AudioAccent.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (isLeafFolder) {
                    item(key = "__play_all__") {
                        BrowseRow(
                            title = "Play all tracks",
                            subtitle = "${audioTracks.size} tracks",
                            trailingIcon = Icons.Filled.PlayArrow,
                            focusRequester = if (restorePath.value == null) listFocusRequester else null,
                            onClick = {
                                viewModel.playFolder(path)
                                onPlayed()
                            },
                            onLongClick = {
                                addTarget = BrowseAddTarget.Folder(path, path.substringAfterLast('/'))
                            },
                        )
                    }
                }
                if (!atRoot) {
                    item(key = "__parent__") {
                        BrowseRow(
                            title = "..",
                            trailingIcon = Icons.Filled.DriveFolderUpload,
                            focusRequester = null,
                            onClick = {
                                returnFocusPaths[parentPath.trimEnd('/')] = pathKey
                                onOpenFolder(parentPath)
                            },
                            onLongClick = {
                                returnFocusPaths[parentPath.trimEnd('/')] = pathKey
                                onOpenFolder(parentPath)
                            },
                        )
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    val isAudio = !entry.isdir && MetadataResolver.isAudioFile(entry.name)
                    val wantFocus = restorePath.value != null &&
                        entry.path.trimEnd('/') == restorePath.value
                    BrowseRow(
                        title = entry.name,
                        trailingIcon = when {
                            entry.isdir -> Icons.Filled.Folder
                            isAudio -> Icons.Filled.AudioFile
                            else -> null
                        },
                        focusRequester = if (wantFocus) childFocusRequester else null,
                        onClick = {
                            when {
                                entry.isdir -> onOpenFolder(entry.path)
                                isAudio -> {
                                    viewModel.playFileEntry(entry)
                                    onPlayed()
                                }
                            }
                        },
                        onLongClick = {
                            when {
                                entry.isdir -> addTarget = BrowseAddTarget.Folder(entry.path, entry.name)
                                isAudio -> addTarget = BrowseAddTarget.File(entry)
                            }
                        },
                    )
                }
            }
        }

        if (loading && entries.isNotEmpty()) {
            Text(
                "Updating…",
                color = AudioTextMuted.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }

        addTarget?.let { target ->
            PlaylistAddSheet(
                title = target.label,
                onReplace = {
                    when (target) {
                        is BrowseAddTarget.Folder ->
                            viewModel.addFolderToPlaylist(target.path, replace = true)
                        is BrowseAddTarget.File ->
                            viewModel.addFileEntryToPlaylist(target.entry, replace = true)
                        is BrowseAddTarget.Artist ->
                            viewModel.addArtistToPlaylist(target.artist, replace = true)
                        is BrowseAddTarget.Album ->
                            viewModel.addAlbumToPlaylist(target.albumId, replace = true)
                        is BrowseAddTarget.Track ->
                            viewModel.addTrackToPlaylist(target.track, replace = true)
                    }
                    addTarget = null
                    if (target is BrowseAddTarget.Folder || target is BrowseAddTarget.File) {
                        onPlayed()
                    }
                },
                onAppend = {
                    when (target) {
                        is BrowseAddTarget.Folder ->
                            viewModel.addFolderToPlaylist(target.path, replace = false)
                        is BrowseAddTarget.File ->
                            viewModel.addFileEntryToPlaylist(target.entry, replace = false)
                        is BrowseAddTarget.Artist ->
                            viewModel.addArtistToPlaylist(target.artist, replace = false)
                        is BrowseAddTarget.Album ->
                            viewModel.addAlbumToPlaylist(target.albumId, replace = false)
                        is BrowseAddTarget.Track ->
                            viewModel.addTrackToPlaylist(target.track, replace = false)
                    }
                    addTarget = null
                },
                onDismiss = { addTarget = null },
            )
        }
    }
}


private sealed class BrowseAddTarget {
    abstract val label: String

    data class Folder(val path: String, val name: String) : BrowseAddTarget() {
        override val label: String get() = name
    }

    data class File(val entry: FileEntry) : BrowseAddTarget() {
        override val label: String get() = entry.name
    }

    data class Artist(val artist: ArtistEntity) : BrowseAddTarget() {
        override val label: String get() = artist.name
    }

    data class Album(val albumId: String, val title: String) : BrowseAddTarget() {
        override val label: String get() = title
    }

    data class Track(val track: TrackEntity) : BrowseAddTarget() {
        override val label: String get() = track.title
    }
}

@Composable
private fun PlaylistAddSheet(
    title: String,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(title) {
        kotlinx.coroutines.delay(40)
        runCatching { firstFocus.requestFocus() }
    }
    MusicModalOverlay(onDismiss = onDismiss, scrimAlpha = 0.72f, windowed = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF16161C))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(18.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ADD TO PLAYLIST",
                color = AudioAccent.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PallasFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            ModePill(
                label = "Clear playlist & add",
                selected = true,
                focusRequester = firstFocus,
                onClick = onReplace,
            )
            Spacer(Modifier.height(8.dp))
            ModePill(
                label = "Append to playlist",
                selected = false,
                onClick = onAppend,
            )
            Spacer(Modifier.height(8.dp))
            ModePill(
                label = "Cancel",
                selected = false,
                onClick = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(
    title: String,
    subtitle: String? = null,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpen: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) FocusRing else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    onOpen != null &&
                        event.key == Key.DirectionRight &&
                        event.type == KeyEventType.KeyUp -> {
                        onOpen()
                        true
                    }
                    event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        onLongClick()
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressHandled = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressHandled) {
                            longPressHandled = false
                            onLongClick()
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = AudioTextMuted,
                    fontSize = 11.sp,
                    fontFamily = PallasFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = if (focused) FocusRing else AudioTextMuted.copy(alpha = 0.75f),
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetaLink(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    maxLines: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.3f).sp,
        fontWeight = fontWeight,
        fontFamily = PallasFontFamily,
        maxLines = maxLines,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    !enabled -> Color.Transparent
                    focused -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .border(
                width = if (focused && enabled) 2.dp else 0.dp,
                color = if (focused && enabled) FocusRing else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        onClick()
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> true
                    else -> false
                }
            }
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interaction,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressHandled by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }

    Text(
        text = label,
        color = when {
            selected || focused -> Color(0xFF1A1206)
            else -> Color.White.copy(alpha = 0.88f)
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = PallasFontFamily,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected && !focused -> AudioAccent
                    focused -> Color.White
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FocusRing else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    onLongClick != null && isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressHandled = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (onLongClick != null && longPressHandled) {
                            longPressHandled = false
                            onLongClick()
                        } else {
                            onClick()
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> true
                    else -> false
                }
            }
            .focusable(true, interaction)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = interaction,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun PanelLink(
    label: String,
    emphasized: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = when {
            focused -> Color(0xFF1A1206)
            emphasized -> AudioAccent
            else -> Color.White.copy(alpha = 0.82f)
        },
        fontSize = 12.sp,
        fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        fontFamily = PallasFontFamily,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) FocusRing else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun PanelDot() {
    Text("·", color = AudioTextMuted.copy(alpha = 0.5f), fontSize = 12.sp)
}

@Composable
private fun PanelIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) FocusRing else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        content()
    }
}

private data class NowPlayingDisplay(
    val title: String,
    val artist: String,
    val albumTitle: String?,
    val trackNumber: Int?,
    val year: Int?,
)

/**
 * Title (white) + artist (gold) on separate rows.
 * Album title alone; track number and year sit on their own row in the player.
 */
private fun nowPlayingDisplay(
    rawTitle: String,
    rawArtist: String,
    rawAlbum: String?,
    rawTrackNo: Int?,
    rawYear: Int? = null,
    nasPath: String? = null,
): NowPlayingDisplay {
    var title = MetadataResolver.fixTagText(rawTitle).trim()
    var artist = MetadataResolver.fixTagText(rawArtist).trim()
    var trackNo = rawTrackNo?.takeIf { it > 0 }

    // Filename fill ONLY when ID3 title/artist are blank — never rewrite real tags.
    if (title.isBlank() || MetadataResolver.isPlaceholderArtist(artist)) {
        val filled = MetadataResolver.fillMissingFromCompilationFileName(
            title = title.takeIf { it.isNotBlank() },
            artist = artist.takeUnless { MetadataResolver.isPlaceholderArtist(it) },
            nasPath = nasPath,
            trackNumber = trackNo,
        )
        if (title.isBlank()) title = filled.first
        if (MetadataResolver.isPlaceholderArtist(artist)) artist = filled.second
        if (trackNo == null) trackNo = filled.third
    }

    val album = rawAlbum?.let { MetadataResolver.fixTagText(it) }?.trim()?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.matches(Regex("""\d{1,3}""")) }
        ?.takeUnless { it.equals(title, ignoreCase = true) }

    return NowPlayingDisplay(
        title = title.ifBlank { "Nothing playing" },
        artist = if (MetadataResolver.isPlaceholderArtist(artist)) "Unknown artist" else artist,
        albumTitle = album,
        trackNumber = trackNo,
        year = rawYear?.takeIf { it in 1000..2100 },
    )
}

private fun queueTitle(track: TrackEntity): String {
    // Prefer the ID3/index title as stored — do not rebuild from the file stem.
    return MetadataResolver.fixTagText(track.title).trim().ifBlank { track.title }
}

private fun queueArtist(track: TrackEntity): String =
    MetadataResolver.resolveDisplayArtist(
        artist = track.artistName,
        albumArtist = track.albumArtist,
        nasPath = track.nasPath,
        albumTitle = track.albumTitle,
    ) ?: "Unknown artist"

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
