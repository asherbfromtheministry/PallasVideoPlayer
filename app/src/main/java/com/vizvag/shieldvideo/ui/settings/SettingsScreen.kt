package com.vizvag.shieldvideo.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.vizvag.shieldvideo.ui.components.LocalForcedLandscapeRotated
import com.vizvag.shieldvideo.ui.components.touchFriendlyVerticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.index.VideoIndexController
import com.vizvag.shieldvideo.data.iptv.EpgAiProvider
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ClockCorner
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.settings.IptvGuideSize
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage
import com.vizvag.shieldvideo.playback.NotificationAccessHelper
import com.vizvag.shieldvideo.ui.components.AmbientBackdrop
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private enum class SettingsTab(val label: String) {
    NAS("NAS"),
    Library("Library"),
    Playback("Playback"),
    Display("Display"),
    LiveTv("Live TV"),
    YouTube("YouTube"),
    Radio("Radio"),
    Podcasts("Podcasts"),
    Integrations("Integrations"),
    Backup("Backup"),
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    nasRepository: NasRepository,
    onBack: () -> Unit,
    notificationAccessEnabled: Boolean = false
) {
    val state by viewModel.state.collectAsState()
    val draft = state.draft
    val context = LocalContext.current
    val localRecordingFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.update { it.copy(iptvRecordingLocalTreeUri = uri.toString()) }
        }
    }
    val youtubeSubsCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onSuccess { text ->
            if (text != null) viewModel.importSubscriptionsCsv(text)
            else viewModel.setYoutubeAuthMessage("Could not read that file")
        }.onFailure { e ->
            viewModel.setYoutubeAuthMessage(e.message ?: "Could not read CSV")
        }
    }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.NAS) }
    val tabFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LocalScreenChrome.current.accent,
        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = LocalScreenChrome.current.accent,
        unfocusedLabelColor = TextMuted,
        cursorColor = LocalScreenChrome.current.accent,
        disabledBorderColor = Color.White.copy(alpha = 0.2f),
        disabledTextColor = Color.White,
        disabledLabelColor = TextMuted
    )

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledPlayers()
        delay(80)
        runCatching { tabFocusRequester.requestFocus() }
    }

    LaunchedEffect(selectedTab) {
        // Keep focus on the active tab after OK (requester moves with selection).
        // Give composition a beat so tab-specific content FocusRequesters attach first.
        delay(40)
        runCatching { tabFocusRequester.requestFocus() }
    }

    fun requestLeave() {
        if (state.isDirty) {
            showLeaveConfirm = true
        } else {
            onBack()
        }
    }

    fun focusTabs() {
        runCatching { tabFocusRequester.requestFocus() }
    }

    fun focusContent() {
        runCatching { contentFocusRequester.requestFocus() }
            .onFailure { focusManager.moveFocus(FocusDirection.Right) }
    }

    BackHandler { requestLeave() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        AmbientBackdrop(intensity = 0.35f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            focusTabs()
                            true
                        } else {
                            false
                        }
                    }
            ) {
                TvFocusButton(
                    onClick = { requestLeave() },
                    compact = true
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Settings",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp
                    )
                    Text(
                        text = "NAS · Library · Playback · Display · Live TV · YouTube · Radio · more",
                        color = TextMuted,
                        fontSize = 11.sp,
                        letterSpacing = 0.2.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (state.isDirty) {
                    Text(
                        text = "Unsaved",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
                TvFocusButton(onClick = viewModel::save, compact = true, selected = state.isDirty) {
                    Text(text = "Save", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SettingsSectionRail(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    tabFocusRequester = tabFocusRequester,
                    onMoveFocusToContent = ::focusContent,
                    onMoveFocusToHeader = {
                        focusManager.moveFocus(FocusDirection.Up)
                    },
                    modifier = Modifier
                        .width(156.dp)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF121218))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val contentScrollState = rememberScrollState()
                    val contentFocusScope = rememberCoroutineScope()
                    val rotated = LocalForcedLandscapeRotated.current
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .touchFriendlyVerticalScroll(contentScrollState, rotated)
                    ) {
                        // Entry target for D-pad Right from the tab rail. Immediately hops to the
                        // first real control so focus is never stuck on an invisible 1dp box.
                        // YouTube attaches contentFocusRequester to its first field instead.
                        Box(
                            modifier = Modifier
                                .then(
                                    if (selectedTab != SettingsTab.YouTube) {
                                        Modifier.focusRequester(contentFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        contentFocusScope.launch {
                                            delay(1)
                                            if (!focusManager.moveFocus(FocusDirection.Down)) {
                                                focusTabs()
                                            }
                                        }
                                    }
                                }
                                .focusable(enabled = selectedTab != SettingsTab.YouTube)
                                .fillMaxWidth()
                                .height(1.dp)
                                .onPreviewKeyEvent { event ->
                                    when {
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                            focusManager.moveFocus(FocusDirection.Down)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                            focusTabs()
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                                            focusTabs()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        )
                        Text(
                            text = selectedTab.label.uppercase(),
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        when (selectedTab) {
                            SettingsTab.NAS -> NasSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel
                            )
                            SettingsTab.Library -> LibrarySettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel
                            )
                            SettingsTab.Playback -> PlaybackSettingsTab(
                                draft = draft,
                                state = state,
                                notificationAccessEnabled = notificationAccessEnabled,
                                onOpenNotificationAccess = { NotificationAccessHelper.openSettings(context) },
                                viewModel = viewModel
                            )
                            SettingsTab.Display -> DisplaySettingsTab(
                                draft = draft,
                                viewModel = viewModel,
                            )
                            SettingsTab.LiveTv -> LiveTvSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                onChooseLocalRecordingFolder = {
                                    localRecordingFolderLauncher.launch(
                                        draft.iptvRecordingLocalTreeUri
                                            .takeIf { it.isNotBlank() }
                                            ?.let(android.net.Uri::parse)
                                    )
                                },
                                viewModel = viewModel
                            )
                            SettingsTab.YouTube -> YouTubeSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel,
                                contentFocusRequester = contentFocusRequester,
                                onMoveFocusToTabs = ::focusTabs,
                                onPickSubscriptionsCsv = {
                                    youtubeSubsCsvLauncher.launch(
                                        arrayOf("text/*", "text/csv", "application/csv", "*/*")
                                    )
                                },
                                onImportFromDownloads = {
                                    val text = readSubscriptionsCsvFromDevice(context)
                                    if (text != null) {
                                        viewModel.importSubscriptionsCsv(text)
                                    } else {
                                        viewModel.setYoutubeAuthMessage(
                                            "No subscriptions.csv in Download/ — push it to the TV first"
                                        )
                                    }
                                },
                            )
                            SettingsTab.Radio -> RadioSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel,
                                scrollState = contentScrollState
                            )
                            SettingsTab.Podcasts -> PodcastSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel,
                            )
                            SettingsTab.Integrations -> IntegrationsSettingsTab(
                                draft = draft,
                                state = state,
                                fieldColors = fieldColors,
                                viewModel = viewModel
                            )
                            SettingsTab.Backup -> BackupSettingsTab(
                                draft = draft,
                                state = state,
                                onImport = { showImportConfirm = true },
                                viewModel = viewModel
                            )
                        }

                        if (state.testMessage != null && selectedTab == SettingsTab.NAS) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = state.testMessage.orEmpty(), color = Accent, fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    val pickerMode = state.folderPickerMode
    if (pickerMode == FolderPickerMode.PODCAST_OPML_FILE) {
        OpmlFilePickerDialog(
            settings = draft,
            nasRepository = nasRepository,
            initialNasPath = draft.podcastOpmlNasPath,
            onDismiss = viewModel::dismissFolderPicker,
            onPick = viewModel::importPickedOpml,
        )
    } else if (pickerMode != null) {
        NasFolderPickerDialog(
            mode = pickerMode,
            settings = draft,
            nasRepository = nasRepository,
            initialSelection = when {
                state.folderPickerForDefault || pickerMode == FolderPickerMode.DEFAULT_FOLDER ->
                    listOf(draft.defaultShare)
                pickerMode == FolderPickerMode.BACKUP_FOLDER -> listOf(draft.backupFolderPath)
                pickerMode == FolderPickerMode.IPTV_RECORDING_FOLDER ->
                    listOf(draft.iptvRecordingNasFolder)
                pickerMode == FolderPickerMode.MUSIC_FOLDERS -> draft.musicPaths
                else -> draft.shares
            },
            title = when {
                state.folderPickerForDefault || pickerMode == FolderPickerMode.DEFAULT_FOLDER ->
                    "Select default video folder"
                pickerMode == FolderPickerMode.BACKUP_FOLDER -> "Select settings backup folder"
                pickerMode == FolderPickerMode.IPTV_RECORDING_FOLDER -> "Select IPTV recording folder"
                pickerMode == FolderPickerMode.MUSIC_FOLDERS -> "Select music folders"
                else -> "Select video folders"
            },
            onDismiss = viewModel::dismissFolderPicker,
            onConfirm = viewModel::applyFolderPicker
        )
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("Import settings?", color = Color.White) },
            text = {
                Text(
                    "This replaces this device's settings, IPTV playlists, favorites, channel order, names, EPG assignments, and parental settings.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        viewModel.importSettings()
                    }
                ) {
                    Text("Import", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardSurface
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = {
                Text(text = "Unsaved changes", color = Color.White)
            },
            text = {
                Text(
                    text = "You have unsaved settings. Save them, discard them, or stay here.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.save()
                        showLeaveConfirm = false
                        onBack()
                    }
                ) {
                    Text("Save", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showLeaveConfirm = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                    TextButton(
                        onClick = {
                            viewModel.discardChanges()
                            showLeaveConfirm = false
                            onBack()
                        }
                    ) {
                        Text("Discard", color = Color(0xFFFF8A80))
                    }
                }
            },
            containerColor = Color(0xFF2E342A)
        )
    }
}

@Composable
private fun SettingsSectionRail(
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    tabFocusRequester: FocusRequester,
    onMoveFocusToContent: () -> Unit,
    onMoveFocusToHeader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val railScrollState = rememberScrollState()
    val rotated = LocalForcedLandscapeRotated.current
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E0E12))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .touchFriendlyVerticalScroll(railScrollState, rotated)
            .padding(vertical = 6.dp, horizontal = 6.dp)
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        onMoveFocusToContent()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                        // Let normal focus move within the rail; header only from first item.
                        false
                    }
                    else -> false
                }
            }
    ) {
        SettingsTab.entries.forEach { tab ->
            SettingsRailItem(
                label = tab.label,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                onMoveFocusToHeader = onMoveFocusToHeader,
                isFirst = tab == SettingsTab.entries.first(),
                modifier = if (tab == selected) {
                    Modifier.focusRequester(tabFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun SettingsRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onMoveFocusToHeader: () -> Unit,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textColor = when {
        focused || selected -> Color.White
        else -> TextMuted
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = selected, idleSurface = Color.Transparent)
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        onClick()
                        true
                    }
                    isFirst &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp -> {
                        onMoveFocusToHeader()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    when {
                        selected || focused -> Accent
                        else -> Color.Transparent
                    }
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = textColor,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun NasSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel
) {
    SectionHint("How this Shield reaches your Synology.")

    Text("Connection mode", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionMode.entries.forEach { mode ->
            TvFocusButton(
                onClick = { viewModel.setConnectionMode(mode) },
                selected = draft.connectionMode == mode
            ) {
                Text(
                    text = mode.label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = when (draft.connectionMode) {
            ConnectionMode.SMB3 -> "Browse & play over SMB3 (port ${draft.port}). Best for local LAN / Shield."
            ConnectionMode.HTTP -> "Browse & play via DSM File Station HTTP (port ${draft.port}). Needs LAN reachability — disable Tailscale/VPN if Test connection fails."
        },
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    TvSettingsField("NAS host", draft.host, fieldColors) { value ->
        viewModel.update { it.copy(host = value) }
    }
    TvSettingsField(
        label = when (draft.connectionMode) {
            ConnectionMode.SMB3 -> "SMB port"
            ConnectionMode.HTTP -> "DSM HTTP port"
        },
        value = draft.port.toString(),
        colors = fieldColors
    ) { value ->
        viewModel.update {
            it.copy(port = value.toIntOrNull() ?: it.connectionMode.defaultPort)
        }
    }
    TvSettingsField("Username", draft.username, fieldColors) { value ->
        viewModel.update { it.copy(username = value) }
    }
    TvSettingsField(
        label = "Password",
        value = draft.password,
        colors = fieldColors,
        isPassword = true,
        passwordVisible = state.passwordVisible,
        onTogglePasswordVisible = viewModel::togglePasswordVisible,
        onChange = { value -> viewModel.update { it.copy(password = value) } }
    )

    Spacer(modifier = Modifier.height(4.dp))
    TvFocusButton(onClick = { if (!state.testing) viewModel.testConnection() }) {
        Text(
            text = if (state.testing) "Testing…" else "Test connection",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LibrarySettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel
) {
    SectionHint("Folders shown in the browser and used for search indexing.")

    Text("Video folders", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = draft.shares.joinToString(", ") { NasPaths.labelFor(it) }.ifBlank { "None selected" },
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(onClick = { viewModel.openVideoFolderPicker() }) {
            Text(text = "Browse video folders", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TvFocusButton(onClick = { viewModel.openDefaultFolderPicker() }) {
            Text(text = "Default folder", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Default: ${NasPaths.labelFor(draft.defaultShare).ifBlank { "—" }} — home landing focuses this folder",
        color = TextMuted,
        fontSize = 11.sp
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text("Music folders", color = LocalScreenChrome.current.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    SectionHint("NAS folders scanned for the Music library (default /music). Uses NAS credentials from the NAS tab.")
    Text(
        text = draft.musicPaths.joinToString(", ") { NasPaths.labelFor(it) }.ifBlank { "None selected" },
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(onClick = { viewModel.openMusicFolderPicker() }) {
        Text(text = "Browse music folders", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(
            onClick = { viewModel.update { it.copy(musicUseHttps = !it.musicUseHttps) } },
            selected = draft.musicUseHttps,
            compact = true,
        ) {
            Text("HTTPS for music", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TvFocusButton(
            onClick = { viewModel.update { it.copy(musicTrustSelfSigned = !it.musicTrustSelfSigned) } },
            selected = draft.musicTrustSelfSigned,
            compact = true,
        ) {
            Text("Trust self-signed cert", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text("Video index", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    val index = state.indexStatus
    val ageText = when {
        index.builtAtMs <= 0L -> "never built"
        else -> {
            val hours = ((System.currentTimeMillis() - index.builtAtMs) / 3_600_000L).coerceAtLeast(0)
            when {
                hours < 1 -> "updated less than 1h ago"
                hours < 24 -> "updated ${hours}h ago"
                else -> "updated ${hours / 24}d ago"
            }
        }
    }
    Text(
        text = when {
            index.building -> "Building… ${index.progressCount} items"
            index.error != null -> index.error.orEmpty()
            else -> {
                val src = when (index.source) {
                    VideoIndexController.SOURCE_VIDEO_STATION -> "Video Station"
                    VideoIndexController.SOURCE_WALK -> "folder scan"
                    else -> null
                }
                buildString {
                    append("${index.entryCount} items · $ageText")
                    if (src != null) append(" · $src")
                }
            }
        },
        color = if (index.error != null) Color(0xFFFF8A80) else Color.White,
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(onClick = viewModel::rebuildVideoIndex, compact = true) {
        Text(
            text = if (index.building) "Indexing…" else "Rebuild index now",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
    Text(
        text = "Pulls Synology Video Station’s media index when available; otherwise scans the video folders above. Auto-refreshes at least every 24 hours.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text("Music index", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    val musicIndex = state.musicIndexStatus
    val musicAgeText = when {
        musicIndex.builtAtMs <= 0L -> "never built"
        else -> {
            val hours = ((System.currentTimeMillis() - musicIndex.builtAtMs) / 3_600_000L).coerceAtLeast(0)
            when {
                hours < 1 -> "updated less than 1h ago"
                hours < 24 -> "updated ${hours}h ago"
                else -> "updated ${hours / 24}d ago"
            }
        }
    }
    Text(
        text = when {
            musicIndex.building -> {
                val pct = (musicIndex.progress * 100).toInt().coerceIn(0, 100)
                musicIndex.message?.takeIf { it.isNotBlank() }
                    ?: "Building… $pct%"
            }
            musicIndex.error != null -> musicIndex.error.orEmpty()
            else -> "${musicIndex.trackCount} tracks · $musicAgeText"
        },
        color = if (musicIndex.error != null) Color(0xFFFF8A80) else Color.White,
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(
        onClick = viewModel::rebuildMusicIndex,
        compact = true,
        enabled = !musicIndex.building,
    ) {
        Text(
            text = if (musicIndex.building) "Indexing…" else "Rebuild music index now",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
    Text(
        text = "Pulls Synology Audio Station’s media index when available; otherwise scans the music folders above via File Station. Start from here or the Music screen sync icon.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun PlaybackSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    notificationAccessEnabled: Boolean,
    onOpenNotificationAccess: () -> Unit,
    viewModel: SettingsViewModel
) {
    SectionHint("External player and resume tracking after VLC.")

    Text("Playback player", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = state.installedPlayers
            .firstOrNull { it.packageName.equals(draft.playerPackage, true) }
            ?.label
            ?: draft.playerPackage.ifBlank { "VLC" },
        color = Color.White,
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (state.installedPlayers.isEmpty()) {
        Text(
            text = "No video players found — install VLC or another player",
            color = TextMuted,
            fontSize = 11.sp
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.installedPlayers, key = { it.packageName }) { player ->
                val selected = player.packageName.equals(draft.playerPackage, true)
                TvFocusButton(
                    onClick = { viewModel.setPlayerPackage(player.packageName) },
                    selected = selected,
                    compact = true
                ) {
                    Text(
                        text = player.label + if (player.isVlc) " (default)" else "",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(onClick = viewModel::refreshInstalledPlayers, compact = true) {
        Text(text = "Refresh player list", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = "Opens files in the selected app via the seekable local proxy. VLC is preferred when installed.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text("On-screen clock", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Subtle time and date. Pick which corner it sits in.",
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockCorner.entries.forEach { corner ->
            val selected = draft.clockCorner == corner
            TvFocusButton(
                onClick = { viewModel.update { it.copy(clockCorner = corner) } },
                selected = selected,
                compact = true
            ) {
                Text(
                    text = corner.label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text("Resume tracking", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (notificationAccessEnabled) {
            "Notification access: on — resume bars update after VLC playback"
        } else {
            "Notification access: off — enable so PallasVideoPlayer can read VLC playback position"
        },
        color = if (notificationAccessEnabled) LocalScreenChrome.current.accent else TextMuted,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(onClick = onOpenNotificationAccess) {
        Text(
            text = "Open notification access",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DisplaySettingsTab(
    draft: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHint("Visual load for Music / Radio on this device.")

    Text("Visual effects", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (draft.liteVisuals) {
            "Blur, ambient motion, and looping EQ animation are off. Best for Chromecast HD / low-power boxes."
        } else {
            "Blur, ambient motion, and looping EQ animation are on. Best for Shield / stronger boxes."
        },
        color = TextMuted,
        fontSize = 11.sp,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TvFocusButton(
        onClick = { viewModel.update { it.copy(liteVisuals = !it.liteVisuals) } },
        selected = draft.liteVisuals,
    ) {
        Text(
            text = if (draft.liteVisuals) "Reduced visuals" else "Full visuals",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Text(
        text = "Tap to toggle · Save in the header to apply.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp),
    )

    Spacer(modifier = Modifier.height(22.dp))
    Text("Home & side nav", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Choose which players appear on the home screen and side nav.",
        color = TextMuted,
        fontSize = 11.sp,
    )
    Spacer(modifier = Modifier.height(10.dp))
    HomeTileToggle(
        label = "Radio",
        shown = draft.homeShowRadio,
        onToggle = { viewModel.update { it.copy(homeShowRadio = !it.homeShowRadio) } },
    )
    Spacer(modifier = Modifier.height(8.dp))
    HomeTileToggle(
        label = "Music",
        shown = draft.homeShowMusic,
        onToggle = { viewModel.update { it.copy(homeShowMusic = !it.homeShowMusic) } },
    )
    Spacer(modifier = Modifier.height(8.dp))
    HomeTileToggle(
        label = "Library",
        shown = draft.homeShowLibrary,
        onToggle = { viewModel.update { it.copy(homeShowLibrary = !it.homeShowLibrary) } },
    )
    Spacer(modifier = Modifier.height(8.dp))
    HomeTileToggle(
        label = "YouTube",
        shown = draft.homeShowYouTube,
        onToggle = { viewModel.update { it.copy(homeShowYouTube = !it.homeShowYouTube) } },
    )
    Spacer(modifier = Modifier.height(8.dp))
    HomeTileToggle(
        label = "Live TV",
        shown = draft.homeShowLiveTv,
        onToggle = { viewModel.update { it.copy(homeShowLiveTv = !it.homeShowLiveTv) } },
    )
    Spacer(modifier = Modifier.height(8.dp))
    HomeTileToggle(
        label = "Podcasts",
        shown = draft.homeShowPodcasts,
        onToggle = { viewModel.update { it.copy(homeShowPodcasts = !it.homeShowPodcasts) } },
    )
    Text(
        text = "Save in the header to apply on the home screen.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun HomeTileToggle(
    label: String,
    shown: Boolean,
    onToggle: () -> Unit,
) {
    TvFocusButton(
        onClick = onToggle,
        selected = shown,
    ) {
        Text(
            text = if (shown) "$label: shown" else "$label: hidden",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LiveTvSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    onChooseLocalRecordingFolder: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    SectionHint("M3U playlists, EPG, and parental locks for Live TV.")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(onClick = viewModel::addIptvPlaylist, compact = true) {
            Text("Add playlist", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TvFocusButton(
            onClick = {
                viewModel.update { it.copy(iptvShowEpgInList = !it.iptvShowEpgInList) }
            },
            selected = draft.iptvShowEpgInList,
            compact = true
        ) {
            Text("Show EPG in preview", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TvFocusButton(
            onClick = {
                viewModel.update { it.copy(iptvCompactRows = !it.iptvCompactRows) }
            },
            selected = draft.iptvCompactRows,
            compact = true
        ) {
            Text("Compact rows", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(
        text = "AI EPG matching uses Gemini or OpenAI to map names like “UK: FHD BBC ONE” → the right XMLTV channel. Hold OK on a group → AI match EPG… (All channels = whole playlist).",
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text("AI EPG matching", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EpgAiProvider.entries.forEach { provider ->
            TvFocusButton(
                onClick = { viewModel.update { it.copy(iptvEpgAiProvider = provider) } },
                selected = draft.iptvEpgAiProvider == provider,
                compact = true
            ) {
                Text(
                    if (provider == EpgAiProvider.GEMINI) "Gemini" else "OpenAI",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    TvSettingsField(
        label = if (draft.iptvEpgAiProvider == EpgAiProvider.GEMINI) {
            "Gemini API key (aistudio.google.com)"
        } else {
            "OpenAI API key"
        },
        value = draft.iptvEpgAiApiKey,
        colors = fieldColors,
        isPassword = true,
        onChange = { value -> viewModel.update { it.copy(iptvEpgAiApiKey = value) } }
    )
    if (draft.iptvEpgAiProvider == EpgAiProvider.OPENAI) {
        Spacer(modifier = Modifier.height(6.dp))
        TvSettingsField(
            label = "OpenAI base URL",
            value = draft.iptvEpgAiOpenAiBaseUrl,
            colors = fieldColors,
            onChange = { value -> viewModel.update { it.copy(iptvEpgAiOpenAiBaseUrl = value) } }
        )
    }
    Text(
        text = if (draft.iptvEpgAiApiKey.isBlank()) {
            "No key set — AI match will ask for one. Free Gemini key: Google AI Studio."
        } else {
            "Key saved with Settings → Save. Then Live TV → hold group → AI match EPG…"
        },
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 4.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))
    Text("Recording storage", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IptvRecordingStorage.entries.forEach { storage ->
            TvFocusButton(
                onClick = { viewModel.update { it.copy(iptvRecordingStorage = storage) } },
                selected = draft.iptvRecordingStorage == storage,
                compact = true
            ) {
                Text(
                    if (storage == IptvRecordingStorage.LOCAL) "Local" else "NAS",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    if (draft.iptvRecordingStorage == IptvRecordingStorage.LOCAL) {
        TvFocusButton(onClick = onChooseLocalRecordingFolder, compact = true) {
            Text("Choose local folder", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Text(
            draft.iptvRecordingLocalTreeUri.ifBlank {
                "Default: this device's app storage / IPTV Recordings"
            },
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        TvFocusButton(onClick = viewModel::openIptvRecordingFolderPicker, compact = true) {
            Text("Choose NAS folder", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Text(
            draft.iptvRecordingNasFolder.ifBlank { "No NAS recording folder selected" },
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Text(
        "Recordings are saved as MP4 with the date, time, channel, and programme in the filename.",
        color = TextMuted,
        fontSize = 12.sp
    )
    if (!exactAlarmsAllowed) {
        TvFocusButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            },
            compact = true
        ) {
            Text(
                "Allow precise programme start times",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            "Without Alarms & reminders access, Android may start a scheduled recording late.",
            color = Color(0xFFFFB74D),
            fontSize = 12.sp
        )
    }

    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "Preview guide size",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IptvGuideSize.entries.forEach { size ->
            TvFocusButton(
                onClick = { viewModel.update { it.copy(iptvGuideSize = size) } },
                selected = draft.iptvGuideSize == size,
                compact = true
            ) {
                Text(size.label, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    draft.iptvPlaylists.forEach { playlist ->
        val selected = playlist.id == draft.activeIptvPlaylistId
        val editing = playlist.id == state.editingPlaylistId || selected
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .border(
                    1.dp,
                    if (selected) LocalScreenChrome.current.accent else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(10.dp)
                )
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvFocusButton(
                    onClick = { viewModel.setActiveIptvPlaylist(playlist.id) },
                    selected = selected,
                    compact = true
                ) {
                    Text(
                        text = if (selected) "Active" else "Use",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = playlist.name.ifBlank { "Playlist" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (draft.iptvPlaylists.size > 1) {
                    TvFocusButton(
                        onClick = { viewModel.removeIptvPlaylist(playlist.id) },
                        compact = true
                    ) {
                        Text("Remove", color = Color.White)
                    }
                }
            }
            if (editing) {
                Spacer(modifier = Modifier.height(8.dp))
                TvSettingsField("Name", playlist.name, fieldColors) { value ->
                    viewModel.updateIptvPlaylist(playlist.id) { it.copy(name = value) }
                }
                TvSettingsField("M3U / stream URL", playlist.m3uUrl, fieldColors) { value ->
                    viewModel.updateIptvPlaylist(playlist.id) { it.copy(m3uUrl = value) }
                }
                TvSettingsField("EPG (XMLTV) URL", playlist.epgUrl, fieldColors) { value ->
                    viewModel.updateIptvPlaylist(playlist.id) { it.copy(epgUrl = value) }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text("Parental controls", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (state.parentalPinSet) "PIN set — lock groups below" else "No PIN — set one to lock adult groups",
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(6.dp))
    var parentalPinDraft by remember { mutableStateOf("") }
    TvSettingsField("New PIN (4+ digits)", parentalPinDraft, fieldColors) { parentalPinDraft = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(
            onClick = {
                viewModel.setParentalPin(parentalPinDraft)
                parentalPinDraft = ""
            },
            compact = true
        ) {
            Text("Save PIN", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (state.parentalPinSet) {
            TvFocusButton(onClick = viewModel::clearParentalPin, compact = true) {
                Text("Clear PIN", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    TvSettingsField(
        "Locked groups (comma-separated)",
        state.lockedGroupsText,
        fieldColors
    ) { viewModel.setLockedGroupsText(it) }
}

@Composable
private fun YouTubeSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel,
    contentFocusRequester: FocusRequester,
    onMoveFocusToTabs: () -> Unit,
    onPickSubscriptionsCsv: () -> Unit,
    onImportFromDownloads: () -> Unit,
) {
    SectionHint(
        "Piped login (not Google), then Import Takeout subscriptions.csv. " +
            "Default API: ${com.vizvag.shieldvideo.data.youtube.YoutubeDefaults.DEFAULT_PIPED_API_URL}"
    )
    TvSettingsField(
        label = "Piped API base URL",
        value = draft.youtubePipedApiUrl,
        colors = fieldColors,
        modifier = Modifier
            .focusRequester(contentFocusRequester)
            .tvBringIntoView()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onMoveFocusToTabs()
                    true
                } else {
                    false
                }
            },
        onChange = { value -> viewModel.update { it.copy(youtubePipedApiUrl = value) } },
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = if (draft.isYoutubeLoggedIn) {
            "Piped account · signed in"
        } else {
            "Piped account · signed out"
        },
        color = LocalScreenChrome.current.accent,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(8.dp))
    TvSettingsField(
        label = "Username",
        value = draft.youtubePipedUsername,
        colors = fieldColors,
        modifier = Modifier.tvBringIntoView(),
        onChange = { value -> viewModel.update { it.copy(youtubePipedUsername = value) } },
    )
    TvSettingsField(
        label = "Password",
        value = state.youtubePassword,
        colors = fieldColors,
        isPassword = true,
        passwordVisible = state.youtubePasswordVisible,
        onTogglePasswordVisible = viewModel::toggleYoutubePasswordVisible,
        modifier = Modifier.tvBringIntoView(),
        onChange = { value -> viewModel.setYoutubePassword(value) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (draft.isYoutubeLoggedIn) {
            TvFocusButton(
                onClick = viewModel::logoutPiped,
                compact = true,
                modifier = Modifier.tvBringIntoView(),
            ) {
                Text("Log out", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            TvFocusButton(
                onClick = onImportFromDownloads,
                compact = true,
                modifier = Modifier.tvBringIntoView(),
            ) {
                Text(
                    text = if (state.youtubeAuthBusy) "…" else "Import from Downloads",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TvFocusButton(
                onClick = onPickSubscriptionsCsv,
                compact = true,
                modifier = Modifier.tvBringIntoView(),
            ) {
                Text(
                    text = "Pick CSV",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            TvFocusButton(
                onClick = viewModel::loginPiped,
                compact = true,
                modifier = Modifier.tvBringIntoView(),
            ) {
                Text(
                    text = if (state.youtubeAuthBusy) "…" else "Log in",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TvFocusButton(
                onClick = viewModel::registerPiped,
                compact = true,
                modifier = Modifier.tvBringIntoView(),
            ) {
                Text("Register", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (draft.isYoutubeLoggedIn) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Import from Downloads = /Download/subscriptions.csv (Google Takeout).",
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
    val authMessage = state.youtubeAuthMessage
    if (authMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = authMessage,
            color = if (draft.isYoutubeLoggedIn && !authMessage.contains("fail", ignoreCase = true)) {
                Accent
            } else {
                Color(0xFFFF8A80)
            },
            fontSize = 12.sp,
        )
    }
}

private fun readSubscriptionsCsvFromDevice(context: android.content.Context): String? {
    val candidates = listOf(
        java.io.File("/sdcard/Download/subscriptions.csv"),
        java.io.File("/storage/emulated/0/Download/subscriptions.csv"),
        java.io.File(context.getExternalFilesDir(null), "subscriptions.csv"),
        java.io.File(context.filesDir, "subscriptions.csv"),
    )
    for (file in candidates) {
        val text = runCatching {
            if (file.isFile && file.canRead()) file.readText() else null
        }.getOrNull()
        if (!text.isNullOrBlank()) return text
    }
    return null
}

@Composable
private fun PodcastSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel,
) {
    LaunchedEffect(Unit) { viewModel.refreshPodcastStatus() }

    SectionHint(
        "Import a Podcast Addict (or standard) OPML. Browse the NAS or this device and OK on the file."
    )

    TvFocusButton(
        onClick = viewModel::openPodcastOpmlPicker,
        compact = true,
        enabled = !state.podcastBusy,
        modifier = Modifier.tvBringIntoView(),
    ) {
        Text(
            if (state.podcastBusy) "Importing…" else "Import OPML…",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }

    if (draft.podcastOpmlNasPath.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("Last NAS file", color = TextMuted, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = draft.podcastOpmlNasPath,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    TvFocusButton(
        onClick = viewModel::clearPodcastSubscriptions,
        compact = true,
        enabled = !state.podcastBusy && state.podcastSubscriptionCount > 0,
    ) {
        Text("Clear subscriptions", color = Color.White, fontWeight = FontWeight.SemiBold)
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = buildString {
            append("${state.podcastSubscriptionCount} subscriptions")
            if (state.podcastLastImportMs > 0L) {
                val df = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.UK)
                append(" · last import ")
                append(df.format(java.util.Date(state.podcastLastImportMs)))
            }
        },
        color = TextMuted,
        fontSize = 12.sp,
    )
    if (!state.podcastMessage.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.podcastMessage.orEmpty(),
            color = Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "Save in the header to keep the last NAS file path in backup.",
        color = TextMuted,
        fontSize = 11.sp,
    )
}

@Composable
private fun RadioSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel,
    scrollState: ScrollState
) {
    LaunchedEffect(state.editingRadioStationId) {
        if (state.editingRadioStationId != null) {
            delay(80)
            scrollState.animateScrollTo(scrollState.maxValue.coerceAtLeast(scrollState.value))
        }
    }

    SectionHint(
        "Stations appear as a compact list. Edit opens name, stream URL, and optional BBC metadata."
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(
            onClick = viewModel::addCustomRadioStation,
            compact = true,
            modifier = Modifier.tvBringIntoView()
        ) {
            Text("Add station", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        TvFocusButton(
            onClick = viewModel::addDefaultRadioStations,
            compact = true,
            modifier = Modifier.tvBringIntoView()
        ) {
            Text("Add BBC defaults", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Text(
        "Radio stations (${draft.customRadioStations.size})",
        color = LocalScreenChrome.current.accent,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (draft.customRadioStations.isEmpty()) {
        Text(
            "No stations configured. Add your own stream URL or tap Add BBC defaults.",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.tvBringIntoView()
        )
    } else {
        draft.customRadioStations.forEachIndexed { index, station ->
            val editing = station.id == state.editingRadioStationId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.32f))
                    .border(
                        1.dp,
                        if (editing) LocalScreenChrome.current.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .tvBringIntoView()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = station.name.ifBlank { "Station ${index + 1}" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!editing) {
                            val subtitle = station.tagline.ifBlank {
                                station.streamUrl.takeIf { it.isNotBlank() }?.let { url ->
                                    url.removePrefix("https://").removePrefix("http://")
                                        .substringBefore('/')
                                        .ifBlank { "No stream URL" }
                                } ?: "No stream URL"
                            }
                            Text(
                                text = subtitle,
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    TvFocusButton(
                        onClick = {
                            viewModel.setEditingRadioStation(
                                if (editing) null else station.id
                            )
                        },
                        compact = true,
                        selected = editing,
                        modifier = Modifier.tvBringIntoView()
                    ) {
                        Text(
                            if (editing) "Done" else "Edit",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TvFocusButton(
                        onClick = { viewModel.removeCustomRadioStation(station.id) },
                        compact = true,
                        modifier = Modifier.tvBringIntoView()
                    ) {
                        Text("Delete", color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                    }
                }

                if (editing) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TvSettingsField(
                        label = "Name",
                        value = station.name,
                        colors = fieldColors,
                        modifier = Modifier.tvBringIntoView(),
                        maxDisplayLines = 1
                    ) { value ->
                        viewModel.updateCustomRadioStation(station.id) { it.copy(name = value) }
                    }
                    TvSettingsField(
                        label = "Tagline",
                        value = station.tagline,
                        colors = fieldColors,
                        modifier = Modifier.tvBringIntoView(),
                        maxDisplayLines = 2
                    ) { value ->
                        viewModel.updateCustomRadioStation(station.id) { it.copy(tagline = value) }
                    }
                    TvSettingsField(
                        label = "Stream URL",
                        value = station.streamUrl,
                        colors = fieldColors,
                        modifier = Modifier.tvBringIntoView(),
                        maxDisplayLines = 3
                    ) { value ->
                        viewModel.updateCustomRadioStation(station.id) { it.copy(streamUrl = value) }
                    }
                    TvSettingsField(
                        label = "BBC metadata id (optional)",
                        value = station.bbcServiceId,
                        colors = fieldColors,
                        modifier = Modifier.tvBringIntoView(),
                        maxDisplayLines = 1
                    ) { value ->
                        viewModel.updateCustomRadioStation(station.id) {
                            it.copy(bbcServiceId = value.trim())
                        }
                    }
                    Text(
                        "Optional BBC Sounds id (e.g. bbc_radio_two) for track/programme info.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.tvBringIntoView(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusEvent { focusState ->
            if (focusState.isFocused) {
                scope.launch {
                    requester.bringIntoView()
                }
            }
        }
}

@Composable
private fun IntegrationsSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    fieldColors: TextFieldColors,
    viewModel: SettingsViewModel
) {
    SectionHint("Home Assistant handoff, Philips Hue Music/Radio sync, LAN remote control, and Trakt / TMDB metadata.")

    Text("Home Assistant handoff", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Device id identifies this TV in HA and for LAN remote discovery. Use a quick pick or type any id (e.g. kitchen).",
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("lounge", "bedroom", "kitchen").forEach { id ->
            val selected = draft.deviceId.equals(id, true)
            TvFocusButton(
                onClick = { viewModel.update { it.copy(deviceId = id) } },
                selected = selected,
                compact = true
            ) {
                Text(
                    text = id.replaceFirstChar { it.uppercase() },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TvSettingsField("Device id", draft.deviceId, fieldColors) { value ->
        viewModel.update { it.copy(deviceId = value) }
    }
    TvSettingsField("HA webhook URL", draft.haWebhookUrl, fieldColors) { value ->
        viewModel.update { it.copy(haWebhookUrl = value) }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(
        onClick = {
            viewModel.update { it.copy(sleepTimerHaStandby = !it.sleepTimerHaStandby) }
        },
        selected = draft.sleepTimerHaStandby,
        compact = true
    ) {
        Text("Sleep timer → HA standby", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = "When the sleep timer ends, POSTs to …/pallas_sleep with {\"device\":\"<device id>\",\"action\":\"standby\"} so HA can turn this TV off.",
        color = TextMuted,
        fontSize = 11.sp
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text("Philips Hue (Music / Radio)", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Flash selected Hue lights with Music or Radio audio energy. Press the bridge link button, then Pair. Lightbulb toggles in Music and Radio top bars.",
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(
        onClick = {
            viewModel.update { it.copy(hueEnabled = !it.hueEnabled) }
        },
        selected = draft.hueEnabled,
        compact = true
    ) {
        Text("Music / Radio Hue sync", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.height(8.dp))
    TvSettingsField("Hue bridge IP", draft.hueBridgeIp, fieldColors) { value ->
        viewModel.update { it.copy(hueBridgeIp = value) }
    }
    Text(
        text = if (draft.hueUsername.isNotBlank()) {
            "Paired (token saved)"
        } else {
            "Not paired"
        },
        color = if (draft.hueUsername.isNotBlank()) LocalScreenChrome.current.accent else TextMuted,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvFocusButton(
            onClick = { if (!state.hueBusy) viewModel.pairHueBridge() },
            compact = true
        ) {
            Text(
                text = if (state.hueBusy) "Working…" else "Pair bridge",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        TvFocusButton(
            onClick = { if (!state.hueBusy) viewModel.refreshHueLights() },
            compact = true
        ) {
            Text("Refresh lights", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    state.hueMessage?.let { msg ->
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = msg, color = TextMuted, fontSize = 12.sp)
    }
    if (state.hueLights.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Lights", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        state.hueLights.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { light ->
                    val selected = draft.hueLightIds.contains(light.id)
                    TvFocusButton(
                        onClick = { viewModel.toggleHueLight(light.id) },
                        selected = selected,
                        compact = true
                    ) {
                        Text(
                            text = light.name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (draft.hueLightIds.isNotEmpty()) {
            Text(
                text = "${draft.hueLightIds.size} selected — Save, then play Music",
                color = LocalScreenChrome.current.accent,
                fontSize = 12.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text("LAN remote control", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Phones/tablets on the same Wi‑Fi scan for this TV automatically. Set a Device id (lounge / bedroom) so the room name is clear. No backup or token needed.",
        color = TextMuted,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(
        onClick = {
            viewModel.update { it.copy(allowRemoteControl = !it.allowRemoteControl) }
        },
        selected = draft.allowRemoteControl,
        compact = true
    ) {
        Text("Allow remote control", color = Color.White, fontWeight = FontWeight.SemiBold)
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text("Trakt / TMDB", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))

    TvSettingsField("Trakt username / email", draft.traktUsername, fieldColors) { value ->
        viewModel.update { it.copy(traktUsername = value) }
    }
    TvSettingsField("Trakt profile slug", draft.traktSlug, fieldColors) { value ->
        viewModel.update { it.copy(traktSlug = value) }
    }
    TvSettingsField("Trakt client ID", draft.traktClientId, fieldColors) { value ->
        viewModel.update { it.copy(traktClientId = value) }
    }
    TvSettingsField(
        label = "Trakt client secret (for Link Trakt)",
        value = draft.traktClientSecret,
        colors = fieldColors,
        isPassword = true,
        passwordVisible = state.secretVisible,
        onTogglePasswordVisible = viewModel::toggleSecretVisible,
        onChange = { value -> viewModel.update { it.copy(traktClientSecret = value) } }
    )
    TvSettingsField("TMDB API key", draft.tmdbApiKey, fieldColors) { value ->
        viewModel.update { it.copy(tmdbApiKey = value) }
    }
    TvSettingsField("TMDB read token", draft.tmdbReadToken, fieldColors) { value ->
        viewModel.update { it.copy(tmdbReadToken = value) }
    }

    Text(
        text = if (draft.isTraktLinked) {
            "Trakt: linked"
        } else {
            "Trakt: not linked (watched still works if profile is public)"
        },
        color = if (draft.isTraktLinked) LocalScreenChrome.current.accent else TextMuted,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvFocusButton(onClick = { if (!state.traktLinking) viewModel.linkTrakt() }) {
            Text(
                text = if (state.traktLinking) "Waiting…" else "Link Trakt",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (draft.isTraktLinked) {
            TvFocusButton(onClick = viewModel::unlinkTrakt) {
                Text(text = "Unlink Trakt", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (state.traktUserCode != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Code: ${state.traktUserCode}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
    if (state.traktLinkMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = state.traktLinkMessage.orEmpty(), color = TextMuted, fontSize = 14.sp)
    }
}

@Composable
private fun BackupSettingsTab(
    draft: AppSettings,
    state: SettingsUiState,
    onImport: () -> Unit,
    viewModel: SettingsViewModel
) {
    SectionHint("Save settings and channel personalization to one portable file on your NAS.")

    Text("NAS backup folder", color = TextMuted, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = draft.backupFolderPath.ifBlank { "Not selected" },
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(8.dp))
    TvFocusButton(
        onClick = viewModel::openBackupFolderPicker,
        enabled = !state.backupBusy
    ) {
        Text("Choose NAS folder", color = Color.White, fontWeight = FontWeight.SemiBold)
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        "The backup includes NAS credentials, API keys/tokens, IPTV playlists, YouTube Piped API URL, custom radio stations, favorites, channel order and names, EPG assignments, measured badges, and parental settings.",
        color = TextMuted,
        fontSize = 11.sp
    )
    Text(
        "The file is readable JSON. Store it only in a private NAS folder.",
        color = Color(0xFFFFC46B),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
    Text(
        "If normal browsing uses HTTP, backup automatically uses SMB3 on port 445 without changing your saved connection mode.",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvFocusButton(
            onClick = viewModel::exportSettings,
            enabled = !state.backupBusy && draft.backupFolderPath.isNotBlank()
        ) {
            Text(
                if (state.backupBusy) "Working…" else "Export settings",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        TvFocusButton(
            onClick = onImport,
            enabled = !state.backupBusy && draft.backupFolderPath.isNotBlank()
        ) {
            Text("Import settings", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(
        "File: PallasVideoPlayer-settings.json",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
    state.backupMessage?.let { message ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(message, color = LocalScreenChrome.current.accent, fontSize = 11.sp)
    }
}

/**
 * TV-safe field: the row itself is not a TextField (IME focus fights mid-scroll on Shield).
 * OK / click opens a dialog editor that can reliably take keyboard input.
 */
@Composable
private fun TvSettingsField(
    label: String,
    value: String,
    colors: TextFieldColors,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxDisplayLines: Int = 1,
    onChange: (String) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    val displayValue = when {
        value.isEmpty() -> "Not set — press OK to edit"
        isPassword && !passwordVisible -> "•".repeat(value.length.coerceIn(6, 24))
        else -> value
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(
                width = if (rowFocused) 2.dp else 1.dp,
                color = if (rowFocused) LocalScreenChrome.current.accent else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { rowFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    showEditor = true
                    true
                } else {
                    false
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { showEditor = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextMuted, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = displayValue,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = maxDisplayLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPassword && onTogglePasswordVisible != null && value.isNotEmpty()) {
            Icon(
                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (passwordVisible) "Hide" else "Show",
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onTogglePasswordVisible
                    )
            )
        }
    }

    if (showEditor) {
        TvFieldEditorDialog(
            label = label,
            initialValue = value,
            isPassword = isPassword,
            colors = colors,
            onDismiss = { showEditor = false },
            onConfirm = { edited ->
                onChange(edited)
                showEditor = false
            }
        )
    }
}

@Composable
private fun TvFieldEditorDialog(
    label: String,
    initialValue: String,
    isPassword: Boolean,
    colors: TextFieldColors,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    var reveal by remember { mutableStateOf(!isPassword) }
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
                .background(Color(0xFF2E342A))
                .border(1.dp, LocalScreenChrome.current.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(20.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                visualTransformation = if (isPassword && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (isPassword) {
                    {
                        Icon(
                            imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide" else "Show",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { reveal = !reveal }
                        )
                    }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboard?.hide()
                        onConfirm(draft)
                    }
                ),
                colors = colors
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvFocusButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                TvFocusButton(onClick = {
                    keyboard?.hide()
                    onConfirm(draft)
                }) {
                    Text(text = "Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TvFocusButton(
    onClick: () -> Unit,
    compact: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .glassInteract(focused = focused, selected = selected)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .focusProperties { canFocus = enabled }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    feedback.click()
                    onClick()
                }
            )
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
