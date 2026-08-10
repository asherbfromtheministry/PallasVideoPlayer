package com.vizvag.shieldvideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.hue.HueBridgeClient
import com.vizvag.shieldvideo.data.hue.HueLight
import com.vizvag.shieldvideo.data.index.VideoIndexController
import com.vizvag.shieldvideo.data.index.VideoIndexUiStatus
import com.vizvag.shieldvideo.music.data.MusicIndexController
import com.vizvag.shieldvideo.music.data.MusicIndexUiStatus
import com.vizvag.shieldvideo.music.data.toUiStatus
import com.vizvag.shieldvideo.data.iptv.IptvDefaults
import com.vizvag.shieldvideo.data.iptv.IptvParentalStore
import com.vizvag.shieldvideo.data.iptv.IptvPlaylistConfig
import com.vizvag.shieldvideo.data.radio.CustomRadioStationConfig
import com.vizvag.shieldvideo.data.radio.RadioDefaults
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.settings.SettingsBackupManager
import com.vizvag.shieldvideo.data.trakt.TraktAuthRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeTvAuthRepository
import com.vizvag.shieldvideo.playback.InstalledVideoPlayer
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.ui.notice.AppNoticeBus
import com.vizvag.shieldvideo.ui.notice.AppNoticeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SettingsUiState(
    val draft: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val testMessage: String? = null,
    val saved: Boolean = false,
    val isDirty: Boolean = false,
    val passwordVisible: Boolean = false,
    val secretVisible: Boolean = false,
    val traktLinking: Boolean = false,
    val traktUserCode: String? = null,
    val traktVerifyUrl: String? = null,
    val traktLinkMessage: String? = null,
    val folderPickerMode: FolderPickerMode? = null,
    val folderPickerForDefault: Boolean = false,
    val installedPlayers: List<InstalledVideoPlayer> = emptyList(),
    val indexStatus: VideoIndexUiStatus = VideoIndexUiStatus(),
    val musicIndexStatus: MusicIndexUiStatus = MusicIndexUiStatus(),
    val parentalPinSet: Boolean = false,
    val lockedGroupsText: String = "",
    val editingPlaylistId: String? = null,
    val editingRadioStationId: String? = null,
    val backupBusy: Boolean = false,
    val backupMessage: String? = null,
    val youtubeAuthBusy: Boolean = false,
    val youtubeAuthMessage: String? = null,
    val youtubeLinking: Boolean = false,
    val youtubeUserCode: String? = null,
    val youtubeVerifyUrl: String? = null,
    val podcastBusy: Boolean = false,
    val podcastMessage: String? = null,
    val podcastSubscriptionCount: Int = 0,
    val podcastLastImportMs: Long = 0L,
    val hueBusy: Boolean = false,
    val hueMessage: String? = null,
    val hueLights: List<HueLight> = emptyList(),
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktAuthRepository: TraktAuthRepository,
    private val mediaPlayerLauncher: MediaPlayerLauncher,
    private val videoIndex: VideoIndexController,
    private val musicIndex: MusicIndexController,
    private val iptvParental: IptvParentalStore,
    private val settingsBackup: SettingsBackupManager,
    private val podcastRepository: com.vizvag.shieldvideo.data.podcast.PodcastRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var linkJob: Job? = null
    private var youtubeLinkJob: Job? = null
    private var baseline: AppSettings = AppSettings()
    private val hueClient = HueBridgeClient()
    private val youtubeTvAuth = YoutubeTvAuthRepository()

    init {
        val loaded = settingsRepository.load()
        baseline = loaded
        _state.value = SettingsUiState(
            draft = loaded,
            indexStatus = videoIndex.status.value,
            parentalPinSet = iptvParental.hasPin(),
            lockedGroupsText = iptvParental.lockedGroups().joinToString(", "),
            podcastSubscriptionCount = podcastRepository.subscriptionCount(),
            podcastLastImportMs = podcastRepository.lastImportAtMs(),
        )
        refreshInstalledPlayers()
        viewModelScope.launch {
            videoIndex.status.collect { status ->
                _state.update { it.copy(indexStatus = status) }
            }
        }
        viewModelScope.launch {
            musicIndex.observeIndexState().collect { entity ->
                _state.update { it.copy(musicIndexStatus = entity.toUiStatus()) }
            }
        }
    }

    fun addIptvPlaylist() {
        update { draft ->
            val next = IptvPlaylistConfig(
                id = UUID.randomUUID().toString(),
                name = "Playlist ${draft.iptvPlaylists.size + 1}",
                m3uUrl = "",
                epgUrl = IptvDefaults.EPG_URL
            )
            draft.copy(
                iptvPlaylists = draft.iptvPlaylists + next,
                activeIptvPlaylistId = next.id
            )
        }
        _state.update { it.copy(editingPlaylistId = it.draft.activeIptvPlaylistId) }
    }

    fun removeIptvPlaylist(id: String) {
        update { draft ->
            val remaining = draft.iptvPlaylists.filterNot { it.id == id }
                .ifEmpty { IptvDefaults.defaultPlaylists() }
            draft.copy(
                iptvPlaylists = remaining,
                activeIptvPlaylistId = if (draft.activeIptvPlaylistId == id) {
                    remaining.first().id
                } else draft.activeIptvPlaylistId
            )
        }
    }

    fun updateIptvPlaylist(id: String, transform: (IptvPlaylistConfig) -> IptvPlaylistConfig) {
        update { draft ->
            draft.copy(
                iptvPlaylists = draft.iptvPlaylists.map {
                    if (it.id == id) transform(it) else it
                }
            )
        }
    }

    fun setActiveIptvPlaylist(id: String) {
        update { it.copy(activeIptvPlaylistId = id) }
        _state.update { it.copy(editingPlaylistId = id) }
    }

    fun setEditingPlaylist(id: String?) {
        _state.update { it.copy(editingPlaylistId = id) }
    }

    fun addCustomRadioStation() {
        update { draft ->
            val next = CustomRadioStationConfig(
                id = UUID.randomUUID().toString(),
                name = "Station ${draft.customRadioStations.size + 1}",
                streamUrl = ""
            )
            draft.copy(customRadioStations = draft.customRadioStations + next)
        }
        _state.update {
            it.copy(editingRadioStationId = it.draft.customRadioStations.lastOrNull()?.id)
        }
    }

    fun removeCustomRadioStation(id: String) {
        update { draft ->
            draft.copy(customRadioStations = draft.customRadioStations.filterNot { it.id == id })
        }
        _state.update { state ->
            state.copy(
                editingRadioStationId = state.editingRadioStationId?.takeUnless { it == id }
            )
        }
    }

    fun updateCustomRadioStation(
        id: String,
        transform: (CustomRadioStationConfig) -> CustomRadioStationConfig
    ) {
        update { draft ->
            draft.copy(
                customRadioStations = draft.customRadioStations.map {
                    if (it.id == id) transform(it) else it
                }
            )
        }
    }

    fun setEditingRadioStation(id: String?) {
        _state.update { it.copy(editingRadioStationId = id) }
    }

    fun addDefaultRadioStations() {
        update { draft ->
            val existingIds = draft.customRadioStations.map { it.id }.toSet()
            val toAdd = RadioDefaults.stations().filter { it.id !in existingIds }
            draft.copy(customRadioStations = draft.customRadioStations + toAdd)
        }
    }

    fun setParentalPin(pin: String) {
        if (pin.length < 4) {
            pushNotice("PIN must be at least 4 digits", kind = AppNoticeKind.Error)
            return
        }
        iptvParental.setPin(pin)
        _state.update { it.copy(parentalPinSet = true) }
        pushNotice("Parental PIN saved", kind = AppNoticeKind.Success)
    }

    fun clearParentalPin() {
        iptvParental.clearPin()
        iptvParental.setLockedGroups(emptySet())
        _state.update {
            it.copy(parentalPinSet = false, lockedGroupsText = "")
        }
        pushNotice("Parental PIN cleared", kind = AppNoticeKind.Success)
    }

    fun setLockedGroupsText(text: String) {
        _state.update { it.copy(lockedGroupsText = text) }
        val groups = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        iptvParental.setLockedGroups(groups)
    }

    fun rebuildVideoIndex() {
        videoIndex.rebuildNow(viewModelScope)
    }

    fun rebuildMusicIndex() {
        musicIndex.rebuildNow()
    }

    fun refreshInstalledPlayers() {
        viewModelScope.launch {
            val players = withContext(Dispatchers.IO) {
                mediaPlayerLauncher.listInstalledPlayers()
            }
            _state.update { state ->
                val selected = state.draft.playerPackage
                val stillInstalled = players.any { it.packageName.equals(selected, true) }
                val nextPackage = when {
                    stillInstalled -> selected
                    players.any { it.isVlc } -> MediaPlayerLauncher.VLC_PACKAGE
                    players.isNotEmpty() -> players.first().packageName
                    else -> MediaPlayerLauncher.VLC_PACKAGE
                }
                val newDraft = if (nextPackage == selected) {
                    state.draft
                } else {
                    state.draft.copy(playerPackage = nextPackage)
                }
                // Auto-fix invalid player without marking dirty if nothing else changed.
                if (newDraft != state.draft && state.draft == baseline) {
                    baseline = newDraft
                }
                state.copy(
                    installedPlayers = players,
                    draft = newDraft,
                    isDirty = newDraft != baseline
                )
            }
        }
    }

    fun setPlayerPackage(packageName: String) {
        update { it.copy(playerPackage = packageName) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        _state.update { state ->
            val draft = transform(state.draft)
            state.copy(draft = draft, saved = false, isDirty = draft != baseline, testMessage = null)
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        update { draft ->
            val nextPort = when {
                draft.port == draft.connectionMode.defaultPort -> mode.defaultPort
                else -> draft.port
            }
            draft.copy(connectionMode = mode, port = nextPort)
        }
    }

    fun openVideoFolderPicker() {
        _state.update {
            it.copy(folderPickerMode = FolderPickerMode.VIDEO_FOLDERS, folderPickerForDefault = false)
        }
    }

    fun openMusicFolderPicker() {
        _state.update {
            it.copy(folderPickerMode = FolderPickerMode.MUSIC_FOLDERS, folderPickerForDefault = false)
        }
    }

    fun openDefaultFolderPicker() {
        _state.update {
            it.copy(folderPickerMode = FolderPickerMode.DEFAULT_FOLDER, folderPickerForDefault = true)
        }
    }

    fun openBackupFolderPicker() {
        _state.update {
            it.copy(folderPickerMode = FolderPickerMode.BACKUP_FOLDER, folderPickerForDefault = false)
        }
    }

    fun openIptvRecordingFolderPicker() {
        _state.update {
            it.copy(
                folderPickerMode = FolderPickerMode.IPTV_RECORDING_FOLDER,
                folderPickerForDefault = false
            )
        }
    }

    fun openPodcastOpmlPicker() {
        _state.update {
            it.copy(
                folderPickerMode = FolderPickerMode.PODCAST_OPML_FILE,
                folderPickerForDefault = false,
            )
        }
    }

    fun importPickedOpml(pick: OpmlPick) {
        dismissFolderPicker()
        if (pick is OpmlPick.Nas) {
            update { it.copy(podcastOpmlNasPath = pick.path) }
        }
        viewModelScope.launch {
            _state.update { it.copy(podcastBusy = true, podcastMessage = null) }
            pushNotice("Importing OPML…", title = "Podcasts", kind = AppNoticeKind.Progress)
            val result = withContext(Dispatchers.IO) {
                when (pick) {
                    is OpmlPick.Nas -> podcastRepository.importOpmlFromNasPath(pick.path)
                    is OpmlPick.Local -> podcastRepository.importOpmlFromLocalFile(pick.absolutePath)
                }
            }
            result.fold(
                onSuccess = { count ->
                    runCatching { ShieldVideoApp.instance.publishPodcastEpisodesToHa() }
                    _state.update {
                        it.copy(
                            podcastBusy = false,
                            podcastMessage = null,
                            podcastSubscriptionCount = podcastRepository.subscriptionCount(),
                            podcastLastImportMs = podcastRepository.lastImportAtMs(),
                        )
                    }
                    pushNotice(
                        "Imported $count shows",
                        title = "Podcasts",
                        kind = AppNoticeKind.Success,
                    )
                },
                onFailure = { e ->
                    _state.update { it.copy(podcastBusy = false, podcastMessage = null) }
                    pushNotice(
                        e.message ?: "OPML import failed",
                        title = "Podcasts",
                        kind = AppNoticeKind.Error,
                    )
                },
            )
        }
    }

    fun dismissFolderPicker() {
        _state.update { it.copy(folderPickerMode = null, folderPickerForDefault = false) }
    }

    fun applyFolderPicker(paths: List<String>) {
        val mode = _state.value.folderPickerMode
        val forDefault = _state.value.folderPickerForDefault
        if (mode == FolderPickerMode.PODCAST_OPML_FILE) {
            // File pick handled by OpmlFilePickerDialog → importPickedOpml.
            dismissFolderPicker()
            return
        }
        val normalized = paths
            .map { path -> if (path.startsWith("/")) path else "/$path" }
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && it != "/" }
            .distinct()
        when {
            forDefault || mode == FolderPickerMode.DEFAULT_FOLDER -> {
                val path = normalized.firstOrNull() ?: return
                update { draft ->
                    val shares = (draft.shares + path).distinctBy { it.lowercase() }
                    draft.copy(shares = shares, defaultShare = path)
                }
            }
            mode == FolderPickerMode.BACKUP_FOLDER -> {
                val path = normalized.firstOrNull() ?: return
                update { it.copy(backupFolderPath = path) }
            }
            mode == FolderPickerMode.IPTV_RECORDING_FOLDER -> {
                val path = normalized.firstOrNull() ?: return
                update { it.copy(iptvRecordingNasFolder = path) }
            }
            mode == FolderPickerMode.VIDEO_FOLDERS -> {
                if (normalized.isEmpty()) return
                update { draft ->
                    val default = when {
                        normalized.any { it.equals(draft.defaultShare, true) } -> draft.defaultShare
                        else -> normalized.first()
                    }
                    draft.copy(shares = normalized, defaultShare = default)
                }
            }
            mode == FolderPickerMode.MUSIC_FOLDERS -> {
                if (normalized.isEmpty()) return
                update { it.copy(musicPaths = AppSettings.normalizeMusicPaths(normalized)) }
            }
        }
        dismissFolderPicker()
    }

    fun exportSettings() {
        if (_state.value.backupBusy) return
        viewModelScope.launch {
            val draft = _state.value.draft
            _state.update { it.copy(backupBusy = true, backupMessage = null) }
            pushNotice("Exporting…", title = "Backup", kind = AppNoticeKind.Progress)
            val result = settingsBackup.exportToNas(draft)
            if (result.isSuccess) {
                baseline = draft
            }
            val msg = result.fold(
                onSuccess = { path -> "Settings exported to $path" },
                onFailure = { error -> error.message ?: "Settings export failed" }
            )
            _state.update {
                it.copy(
                    backupBusy = false,
                    saved = result.isSuccess,
                    isDirty = if (result.isSuccess) false else it.isDirty,
                    backupMessage = null,
                )
            }
            pushNotice(
                msg,
                title = "Backup",
                kind = if (result.isSuccess) AppNoticeKind.Success else AppNoticeKind.Error,
            )
        }
    }

    fun importSettings() {
        if (_state.value.backupBusy) return
        viewModelScope.launch {
            val connectionSettings = _state.value.draft
            _state.update { it.copy(backupBusy = true, backupMessage = null) }
            pushNotice("Importing…", title = "Backup", kind = AppNoticeKind.Progress)
            val result = settingsBackup.importFromNas(connectionSettings)
            result.onSuccess { imported ->
                baseline = imported
                videoIndex.rebuildNow(viewModelScope)
                _state.update {
                    it.copy(
                        draft = imported,
                        parentalPinSet = iptvParental.hasPin(),
                        lockedGroupsText = iptvParental.lockedGroups().joinToString(", "),
                        saved = true,
                        isDirty = false
                    )
                }
            }
            val msg = result.fold(
                onSuccess = {
                    "Settings imported. Reopen Live TV to load the imported channels."
                },
                onFailure = { error -> error.message ?: "Settings import failed" }
            )
            _state.update {
                it.copy(
                    backupBusy = false,
                    backupMessage = null,
                )
            }
            pushNotice(
                msg,
                title = "Backup",
                kind = if (result.isSuccess) AppNoticeKind.Success else AppNoticeKind.Error,
            )
        }
    }

    fun togglePasswordVisible() {
        _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun toggleSecretVisible() {
        _state.update { it.copy(secretVisible = !it.secretVisible) }
    }

    fun save() {
        val shares = _state.value.draft.shares
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("download", "video", "docs") }
        val draft = _state.value.draft.copy(
            shares = shares,
            defaultShare = _state.value.draft.defaultShare.ifBlank { shares.first() }
                .let { selected -> if (shares.any { it.equals(selected, true) }) selected else shares.first() },
            musicPaths = AppSettings.normalizeMusicPaths(_state.value.draft.musicPaths),
            traktSlug = _state.value.draft.traktSlug.ifBlank {
                _state.value.draft.traktUsername.substringBefore('@')
            }
        )
        settingsRepository.save(draft)
        videoIndex.rebuildNow(viewModelScope)
        runCatching { ShieldVideoApp.instance.publishRadioStationsToHa() }
        runCatching { ShieldVideoApp.instance.publishPodcastEpisodesToHa() }
        baseline = draft
        _state.update { it.copy(draft = draft, saved = true, isDirty = false, testMessage = null) }
        pushNotice("Settings saved", kind = AppNoticeKind.Success)
    }

    fun discardChanges() {
        _state.update {
            it.copy(
                draft = baseline,
                saved = false,
                isDirty = false,
                testMessage = null
            )
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val draft = _state.value.draft
            _state.update { it.copy(testing = true, testMessage = null) }
            pushNotice("Testing NAS connection…", title = "NAS", kind = AppNoticeKind.Progress)
            val result = nasRepository.testConnection(draft)
            _state.update { it.copy(testing = false, testMessage = null) }
            result.fold(
                onSuccess = {
                    pushNotice(
                        "Connection OK (${draft.connectionMode.label})",
                        title = "NAS",
                        kind = AppNoticeKind.Success,
                    )
                },
                onFailure = { error ->
                    pushNotice(
                        error.message ?: "Connection failed",
                        title = "NAS",
                        kind = AppNoticeKind.Error,
                    )
                }
            )
        }
    }

    fun pairHueBridge() {
        viewModelScope.launch {
            val draft = _state.value.draft
            _state.update { it.copy(hueBusy = true, hueMessage = null) }
            pushNotice("Pairing… press the bridge button first", title = "Hue", kind = AppNoticeKind.Progress)
            val result = withContext(Dispatchers.IO) {
                hueClient.pair(draft.hueBridgeIp)
            }
            result.fold(
                onSuccess = { username ->
                    update { it.copy(hueUsername = username) }
                    _state.update { it.copy(hueBusy = false, hueMessage = null) }
                    pushNotice(
                        "Paired — Save settings, refresh lights, then pick which ones to sync",
                        title = "Hue",
                        kind = AppNoticeKind.Success,
                    )
                    refreshHueLights()
                },
                onFailure = { error ->
                    _state.update { it.copy(hueBusy = false, hueMessage = null) }
                    pushNotice(
                        error.message ?: "Pairing failed",
                        title = "Hue",
                        kind = AppNoticeKind.Error,
                    )
                },
            )
        }
    }

    fun refreshHueLights() {
        viewModelScope.launch {
            val draft = _state.value.draft
            if (draft.hueUsername.isBlank()) {
                pushNotice("Pair the bridge first", title = "Hue", kind = AppNoticeKind.Error)
                return@launch
            }
            _state.update { it.copy(hueBusy = true, hueMessage = null) }
            pushNotice("Loading lights…", title = "Hue", kind = AppNoticeKind.Progress)
            val result = withContext(Dispatchers.IO) {
                hueClient.listLights(draft.hueBridgeIp, draft.hueUsername)
            }
            result.fold(
                onSuccess = { lights ->
                    _state.update {
                        it.copy(
                            hueBusy = false,
                            hueLights = lights,
                            hueMessage = null,
                        )
                    }
                    pushNotice(
                        if (lights.isEmpty()) "No lights found on this bridge"
                        else "Found ${lights.size} light(s) — select which to sync",
                        title = "Hue",
                        kind = if (lights.isEmpty()) AppNoticeKind.Info else AppNoticeKind.Success,
                    )
                },
                onFailure = { error ->
                    _state.update { it.copy(hueBusy = false, hueMessage = null) }
                    pushNotice(
                        error.message ?: "Could not list lights",
                        title = "Hue",
                        kind = AppNoticeKind.Error,
                    )
                },
            )
        }
    }

    fun toggleHueLight(lightId: String) {
        update { draft ->
            val id = lightId.trim()
            if (id.isBlank()) return@update draft
            val selected = draft.hueLightIds.toMutableList()
            if (selected.contains(id)) selected.remove(id) else selected.add(id)
            draft.copy(hueLightIds = selected)
        }
    }

    fun linkTrakt() {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            val draft = _state.value.draft
            if (draft.traktClientId.isBlank() || draft.traktClientSecret.isBlank()) {
                _state.update {
                    it.copy(traktLinkMessage = "Enter Trakt Client ID and Client Secret first")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    traktLinking = true,
                    traktLinkMessage = "Requesting code…",
                    traktUserCode = null,
                    traktVerifyUrl = null
                )
            }
            val codeResult = traktAuthRepository.requestDeviceCode(draft.traktClientId)
            val device = codeResult.getOrElse { error ->
                _state.update {
                    it.copy(traktLinking = false, traktLinkMessage = error.message ?: "Failed to start Trakt link")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    traktUserCode = device.userCode,
                    traktVerifyUrl = device.verificationUrl,
                    traktLinkMessage = "On any device open ${device.verificationUrl} and enter ${device.userCode}"
                )
            }
            val tokens = traktAuthRepository.pollForToken(
                clientId = draft.traktClientId,
                clientSecret = draft.traktClientSecret,
                deviceCode = device.deviceCode,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds
            ).getOrElse { error ->
                _state.update {
                    it.copy(traktLinking = false, traktLinkMessage = error.message ?: "Trakt link failed")
                }
                return@launch
            }
            val linked = draft.copy(
                traktAccessToken = tokens.accessToken,
                traktRefreshToken = tokens.refreshToken
            )
            settingsRepository.save(linked)
            baseline = linked
            _state.update {
                it.copy(
                    draft = linked,
                    traktLinking = false,
                    traktLinkMessage = "Trakt linked — watched & resume sync enabled",
                    traktUserCode = null,
                    traktVerifyUrl = null,
                    saved = true,
                    isDirty = false
                )
            }
        }
    }

    fun unlinkTrakt() {
        linkJob?.cancel()
        settingsRepository.clearTraktTokens()
        _state.update {
            val next = it.draft.copy(traktAccessToken = "", traktRefreshToken = "")
            baseline = next
            it.copy(
                draft = next,
                traktLinking = false,
                traktUserCode = null,
                traktVerifyUrl = null,
                traktLinkMessage = "Trakt unlinked",
                isDirty = false
            )
        }
    }

    fun linkYoutubeTv() {
        youtubeLinkJob?.cancel()
        youtubeLinkJob = viewModelScope.launch {
            val draft = _state.value.draft
            val deviceId = draft.youtubeTvDeviceId.ifBlank {
                YoutubeTvAuthRepository.newDeviceId()
            }
            _state.update {
                it.copy(
                    youtubeLinking = true,
                    youtubeAuthBusy = true,
                    youtubeAuthMessage = "Requesting YouTube code…",
                    youtubeUserCode = null,
                    youtubeVerifyUrl = null,
                )
            }
            val device = youtubeTvAuth.requestDeviceCode(deviceId).getOrElse { error ->
                _state.update {
                    it.copy(
                        youtubeLinking = false,
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = error.message ?: "Failed to start YouTube link",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    youtubeUserCode = device.userCode,
                    youtubeVerifyUrl = device.verificationUrl,
                    youtubeAuthMessage =
                        "On your phone open ${device.verificationUrl} and enter ${device.userCode}",
                )
            }
            val tokens = youtubeTvAuth.pollForToken(
                deviceCode = device.deviceCode,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds,
            ).getOrElse { error ->
                _state.update {
                    it.copy(
                        youtubeLinking = false,
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = error.message ?: "YouTube link failed",
                        youtubeUserCode = null,
                        youtubeVerifyUrl = null,
                    )
                }
                return@launch
            }
            val linked = draft.copy(
                youtubeTvDeviceId = deviceId,
                youtubeRefreshToken = tokens.refreshToken,
                youtubeAccessToken = tokens.accessToken,
                youtubeAccessTokenExpiresAtMs =
                    System.currentTimeMillis() + tokens.expiresInSeconds * 1000L - 60_000L,
                youtubeAccountName = "YouTube",
            )
            settingsRepository.save(linked)
            baseline = linked
            _state.update {
                it.copy(
                    draft = linked,
                    youtubeLinking = false,
                    youtubeAuthBusy = false,
                    youtubeAuthMessage = "YouTube linked — open YouTube and press Refresh",
                    youtubeUserCode = null,
                    youtubeVerifyUrl = null,
                    saved = true,
                    isDirty = false,
                )
            }
        }
    }

    fun unlinkYoutubeTv() {
        youtubeLinkJob?.cancel()
        settingsRepository.clearYoutubeTvTokens()
        _state.update {
            val next = it.draft.copy(
                youtubeRefreshToken = "",
                youtubeAccessToken = "",
                youtubeAccessTokenExpiresAtMs = 0L,
                youtubeAccountName = "",
            )
            baseline = next
            it.copy(
                draft = next,
                youtubeLinking = false,
                youtubeAuthBusy = false,
                youtubeUserCode = null,
                youtubeVerifyUrl = null,
                youtubeAuthMessage = "YouTube unlinked",
                isDirty = false,
            )
        }
    }

    fun setYoutubeAuthMessage(message: String) {
        _state.update { it.copy(youtubeAuthMessage = message) }
    }

    fun consumeYoutubeAuthMessage() {
        _state.update { it.copy(youtubeAuthMessage = null) }
    }

    fun refreshPodcastStatus() {
        _state.update {
            it.copy(
                podcastSubscriptionCount = podcastRepository.subscriptionCount(),
                podcastLastImportMs = podcastRepository.lastImportAtMs(),
            )
        }
    }

    fun clearPodcastSubscriptions() {
        podcastRepository.clearSubscriptions()
        _state.update {
            it.copy(
                podcastSubscriptionCount = 0,
                podcastLastImportMs = 0L,
                podcastMessage = null,
            )
        }
        pushNotice("Subscriptions cleared", title = "Podcasts", kind = AppNoticeKind.Success)
    }

    private fun pushNotice(
        message: String?,
        title: String = "Settings",
        kind: AppNoticeKind? = null,
    ) {
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return
        AppNoticeBus.show(msg, kind ?: AppNoticeBus.inferKind(msg), title)
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktAuthRepository: TraktAuthRepository,
    private val mediaPlayerLauncher: MediaPlayerLauncher,
    private val videoIndex: VideoIndexController,
    private val musicIndex: MusicIndexController,
    private val iptvParental: IptvParentalStore,
    private val settingsBackup: SettingsBackupManager,
    private val podcastRepository: com.vizvag.shieldvideo.data.podcast.PodcastRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(
            settingsRepository,
            nasRepository,
            traktAuthRepository,
            mediaPlayerLauncher,
            videoIndex,
            musicIndex,
            iptvParental,
            settingsBackup,
            podcastRepository,
        ) as T
    }
}
