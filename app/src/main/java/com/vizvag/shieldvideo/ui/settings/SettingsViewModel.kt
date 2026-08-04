package com.vizvag.shieldvideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import com.vizvag.shieldvideo.playback.InstalledVideoPlayer
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.ui.background.BackgroundImageController
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
    /** Transient Piped password for login/register (not persisted). */
    val youtubePassword: String = "",
    val youtubePasswordVisible: Boolean = false,
    val youtubeAuthBusy: Boolean = false,
    val youtubeAuthMessage: String? = null,
    val hueBusy: Boolean = false,
    val hueMessage: String? = null,
    val hueLights: List<HueLight> = emptyList(),
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktAuthRepository: TraktAuthRepository,
    private val backgroundImages: BackgroundImageController,
    private val mediaPlayerLauncher: MediaPlayerLauncher,
    private val videoIndex: VideoIndexController,
    private val musicIndex: MusicIndexController,
    private val iptvParental: IptvParentalStore,
    private val settingsBackup: SettingsBackupManager,
    private val youtubeRepository: com.vizvag.shieldvideo.data.youtube.YoutubeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var linkJob: Job? = null
    private var baseline: AppSettings = AppSettings()
    private val hueClient = HueBridgeClient()

    init {
        val loaded = settingsRepository.load()
        baseline = loaded
        _state.value = SettingsUiState(
            draft = loaded,
            indexStatus = videoIndex.status.value,
            parentalPinSet = iptvParental.hasPin(),
            lockedGroupsText = iptvParental.lockedGroups().joinToString(", ")
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
            _state.update { it.copy(testMessage = "PIN must be at least 4 digits") }
            return
        }
        iptvParental.setPin(pin)
        _state.update { it.copy(parentalPinSet = true, testMessage = "Parental PIN saved") }
    }

    fun clearParentalPin() {
        iptvParental.clearPin()
        iptvParental.setLockedGroups(emptySet())
        _state.update {
            it.copy(parentalPinSet = false, lockedGroupsText = "", testMessage = "Parental PIN cleared")
        }
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
            it.copy(folderPickerMode = FolderPickerMode.BACKGROUND_FOLDER, folderPickerForDefault = true)
        }
    }

    fun openBackgroundFolderPicker() {
        _state.update {
            it.copy(folderPickerMode = FolderPickerMode.BACKGROUND_FOLDER, folderPickerForDefault = false)
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

    fun dismissFolderPicker() {
        _state.update { it.copy(folderPickerMode = null, folderPickerForDefault = false) }
    }

    fun applyFolderPicker(paths: List<String>) {
        val normalized = paths
            .map { path -> if (path.startsWith("/")) path else "/$path" }
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && it != "/" }
            .distinct()
        val mode = _state.value.folderPickerMode
        val forDefault = _state.value.folderPickerForDefault
        when {
            forDefault -> {
                val path = normalized.firstOrNull() ?: return
                update { draft ->
                    val shares = (draft.shares + path).distinctBy { it.lowercase() }
                    draft.copy(shares = shares, defaultShare = path)
                }
            }
            mode == FolderPickerMode.BACKGROUND_FOLDER -> {
                val path = normalized.firstOrNull() ?: return
                update { it.copy(backgroundFolderPath = path) }
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
            _state.update { it.copy(backupBusy = true, backupMessage = "Exporting…") }
            val result = settingsBackup.exportToNas(draft)
            if (result.isSuccess) {
                baseline = draft
            }
            _state.update {
                it.copy(
                    backupBusy = false,
                    saved = result.isSuccess,
                    isDirty = if (result.isSuccess) false else it.isDirty,
                    backupMessage = result.fold(
                        onSuccess = { path -> "Settings exported to $path" },
                        onFailure = { error -> error.message ?: "Settings export failed" }
                    )
                )
            }
        }
    }

    fun importSettings() {
        if (_state.value.backupBusy) return
        viewModelScope.launch {
            val connectionSettings = _state.value.draft
            _state.update { it.copy(backupBusy = true, backupMessage = "Importing…") }
            val result = settingsBackup.importFromNas(connectionSettings)
            result.onSuccess { imported ->
                baseline = imported
                backgroundImages.reloadNow(viewModelScope)
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
            _state.update {
                it.copy(
                    backupBusy = false,
                    backupMessage = result.fold(
                        onSuccess = {
                            "Settings imported. Reopen Live TV to load the imported channels."
                        },
                        onFailure = { error -> error.message ?: "Settings import failed" }
                    )
                )
            }
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
        backgroundImages.reloadNow(viewModelScope)
        videoIndex.rebuildNow(viewModelScope)
        baseline = draft
        _state.update { it.copy(draft = draft, saved = true, isDirty = false, testMessage = "Saved") }
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
            val result = nasRepository.testConnection(draft)
            _state.update {
                it.copy(
                    testing = false,
                    testMessage = result.fold(
                        onSuccess = {
                            "Connection OK (${draft.connectionMode.label})"
                        },
                        onFailure = { error -> error.message ?: "Connection failed" }
                    )
                )
            }
        }
    }

    fun pairHueBridge() {
        viewModelScope.launch {
            val draft = _state.value.draft
            _state.update { it.copy(hueBusy = true, hueMessage = "Pairing… press the bridge button first") }
            val result = withContext(Dispatchers.IO) {
                hueClient.pair(draft.hueBridgeIp)
            }
            result.fold(
                onSuccess = { username ->
                    update { it.copy(hueUsername = username) }
                    _state.update {
                        it.copy(
                            hueBusy = false,
                            hueMessage = "Paired — Save settings, refresh lights, then pick which ones to sync",
                        )
                    }
                    refreshHueLights()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            hueBusy = false,
                            hueMessage = error.message ?: "Pairing failed",
                        )
                    }
                },
            )
        }
    }

    fun refreshHueLights() {
        viewModelScope.launch {
            val draft = _state.value.draft
            if (draft.hueUsername.isBlank()) {
                _state.update { it.copy(hueMessage = "Pair the bridge first") }
                return@launch
            }
            _state.update { it.copy(hueBusy = true, hueMessage = "Loading lights…") }
            val result = withContext(Dispatchers.IO) {
                hueClient.listLights(draft.hueBridgeIp, draft.hueUsername)
            }
            result.fold(
                onSuccess = { lights ->
                    _state.update {
                        it.copy(
                            hueBusy = false,
                            hueLights = lights,
                            hueMessage = if (lights.isEmpty()) {
                                "No lights found on this bridge"
                            } else {
                                "${lights.size} light(s) — select which to sync with Music"
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            hueBusy = false,
                            hueMessage = error.message ?: "Could not list lights",
                        )
                    }
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

    fun setYoutubePassword(value: String) {
        _state.update { it.copy(youtubePassword = value) }
    }

    fun setYoutubeAuthMessage(message: String) {
        _state.update { it.copy(youtubeAuthMessage = message) }
    }

    fun toggleYoutubePasswordVisible() {
        _state.update { it.copy(youtubePasswordVisible = !it.youtubePasswordVisible) }
    }

    fun importSubscriptionsCsv(csvText: String) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            val draft = _state.value.draft
            if (!draft.isYoutubeLoggedIn) {
                _state.update {
                    it.copy(youtubeAuthMessage = "Log in to Piped first, then import the CSV")
                }
                return@launch
            }
            val ids = com.vizvag.shieldvideo.data.youtube.YoutubeRepository
                .parseTakeoutSubscriptionsCsv(csvText)
            if (ids.isEmpty()) {
                _state.update {
                    it.copy(youtubeAuthMessage = "No channels found in that CSV")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    youtubeAuthBusy = true,
                    youtubeAuthMessage = "Importing ${ids.size} channels…",
                )
            }
            runCatching {
                youtubeRepository.importSubscriptions(
                    authToken = draft.youtubePipedAuthToken,
                    channelIds = ids,
                    override = true,
                )
                val subscribed = runCatching {
                    youtubeRepository.subscriptions(draft.youtubePipedAuthToken).size
                }.getOrDefault(ids.size)
                subscribed
            }.onSuccess { count ->
                _state.update {
                    it.copy(
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = if (count > 0) {
                            "Imported — $count channels on Piped. Open YouTube and press Refresh."
                        } else {
                            "Import sent, but Piped still shows 0 channels. Try another Piped API URL."
                        },
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = e.message ?: "Import failed",
                    )
                }
            }
        }
    }

    fun loginPiped() {
        pipedAuth(register = false)
    }

    fun registerPiped() {
        pipedAuth(register = true)
    }

    fun logoutPiped() {
        linkJob?.cancel()
        val next = _state.value.draft.copy(youtubePipedAuthToken = "")
        settingsRepository.save(next)
        baseline = next
        _state.update {
            it.copy(
                draft = next,
                youtubePassword = "",
                youtubeAuthMessage = "Logged out of Piped",
                isDirty = false,
                saved = true,
            )
        }
    }

    private fun pipedAuth(register: Boolean) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            val draft = _state.value.draft
            val password = _state.value.youtubePassword
            if (draft.youtubePipedUsername.isBlank() || password.isBlank()) {
                _state.update {
                    it.copy(youtubeAuthMessage = "Enter Piped username and password")
                }
                return@launch
            }
            // Persist API URL first so repository uses the draft instance.
            settingsRepository.save(
                draft.copy(youtubePipedAuthToken = draft.youtubePipedAuthToken)
            )
            _state.update {
                it.copy(
                    youtubeAuthBusy = true,
                    youtubeAuthMessage = if (register) "Creating Piped account…" else "Logging in…",
                )
            }
            val result = runCatching {
                if (register) {
                    youtubeRepository.register(draft.youtubePipedUsername, password)
                } else {
                    youtubeRepository.login(draft.youtubePipedUsername, password)
                }
            }
            result.onSuccess { auth ->
                val linked = draft.copy(
                    youtubePipedUsername = auth.username,
                    youtubePipedAuthToken = auth.token,
                    youtubePipedApiUrl = com.vizvag.shieldvideo.data.youtube.YoutubeDefaults
                        .normalizeApiUrl(draft.youtubePipedApiUrl),
                )
                settingsRepository.save(linked)
                baseline = linked
                _state.update {
                    it.copy(
                        draft = linked,
                        youtubePassword = "",
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = if (register) {
                            "Account created — tap Import from Downloads (or Import CSV)"
                        } else {
                            "Logged in as ${auth.username} — import subscriptions.csv next"
                        },
                        saved = true,
                        isDirty = false,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        youtubeAuthBusy = false,
                        youtubeAuthMessage = e.message ?: "Piped auth failed",
                    )
                }
            }
        }
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
    private val traktAuthRepository: TraktAuthRepository,
    private val backgroundImages: BackgroundImageController,
    private val mediaPlayerLauncher: MediaPlayerLauncher,
    private val videoIndex: VideoIndexController,
    private val musicIndex: MusicIndexController,
    private val iptvParental: IptvParentalStore,
    private val settingsBackup: SettingsBackupManager,
    private val youtubeRepository: com.vizvag.shieldvideo.data.youtube.YoutubeRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(
            settingsRepository,
            nasRepository,
            traktAuthRepository,
            backgroundImages,
            mediaPlayerLauncher,
            videoIndex,
            musicIndex,
            iptvParental,
            settingsBackup,
            youtubeRepository,
        ) as T
    }
}
