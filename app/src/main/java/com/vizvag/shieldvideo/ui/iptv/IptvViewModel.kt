package com.vizvag.shieldvideo.ui.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.data.iptv.CatchupUrlBuilder
import com.vizvag.shieldvideo.data.iptv.ChannelQuality
import com.vizvag.shieldvideo.data.iptv.EpgAiMatcher
import com.vizvag.shieldvideo.data.iptv.EpgChannelEntry
import com.vizvag.shieldvideo.data.iptv.EpgChannelMatcher
import com.vizvag.shieldvideo.data.iptv.IptvChannel
import com.vizvag.shieldvideo.data.iptv.IptvDefaults
import com.vizvag.shieldvideo.data.iptv.IptvChannelCustomStore
import com.vizvag.shieldvideo.data.iptv.GroupChannelOrder
import com.vizvag.shieldvideo.data.iptv.IptvFavoritesStore
import com.vizvag.shieldvideo.data.iptv.IptvNowNext
import com.vizvag.shieldvideo.data.iptv.IptvParentalStore
import com.vizvag.shieldvideo.data.iptv.IptvProgramme
import com.vizvag.shieldvideo.data.iptv.IptvRecording
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStatus
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore
import com.vizvag.shieldvideo.data.iptv.IptvRepository
import com.vizvag.shieldvideo.data.iptv.IptvSearchHistoryStore
import com.vizvag.shieldvideo.data.iptv.IptvWatchHistoryStore
import com.vizvag.shieldvideo.data.iptv.XmltvParser
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.playback.IptvRecordingScheduler
import com.vizvag.shieldvideo.playback.IptvRecordingService
import com.vizvag.shieldvideo.playback.LocalMediaProxyService
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryEntry
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.PlayerLaunchResult
import com.vizvag.shieldvideo.playback.ResumeMonitor
import com.vizvag.shieldvideo.playback.remote.RemotePlayBridge
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.playback.remote.RemoteTargetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvChannelRow(
    val channel: IptvChannel,
    val favorite: Boolean,
    val nowNext: IptvNowNext,
    val locked: Boolean,
    /** Measured stream badges from the last watch, else name-derived hints. */
    val badges: List<String> = emptyList(),
    val badgesConfirmed: Boolean = false
)

data class EpgMatchLogLine(
    val channelName: String,
    val epgName: String?,
    val matched: Boolean,
)

data class IptvUiState(
    val settings: AppSettings = AppSettings(),
    /** Full-screen blocker — keep false; use [refreshing] for background updates. */
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val groups: List<String> = emptyList(),
    /** Raw provider group key -> user-facing name. */
    val groupDisplayNames: Map<String, String> = emptyMap(),
    /** Raw provider group key -> persisted channel ordering mode. */
    val groupOrderModes: Map<String, GroupChannelOrder> = emptyMap(),
    /** Groups the user hid — shown greyed at the bottom of the wheel. */
    val hiddenGroups: Set<String> = emptySet(),
    /** Channel counts per group key (Favorites / All / provider groups). */
    val groupChannelCounts: Map<String, Int> = emptyMap(),
    val selectedGroup: String = IptvDefaults.FAVORITES_GROUP,
    /** Group key that [rows] was built for — UI waits on this before opening the channel wheel. */
    val rowsForGroup: String = "",
    val rows: List<IptvChannelRow> = emptyList(),
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchChannelResults: List<IptvChannelRow> = emptyList(),
    val searchProgrammeResults: List<Pair<IptvChannel, IptvProgramme>> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val detailChannel: IptvChannel? = null,
    val detailProgrammes: List<IptvProgramme> = emptyList(),
    val pinPrompt: Boolean = false,
    val pendingGroup: String? = null,
    val unlockedSession: Boolean = false,
    val recordings: List<IptvRecording> = emptyList(),
    val showRecordings: Boolean = false,
    val showVlcMissing: Boolean = false,
    val epgChannelCount: Int = 0,
    /** Bumped whenever programme data changes (EPG load / manual assignment) so cached strips refresh. */
    val epgVersion: Int = 0,
    /** Channel currently playing in the preview frame (null = idle). */
    val previewChannel: IptvChannel? = null,
    /** True when preview is expanded to in-app fullscreen. */
    val fullscreen: Boolean = false,
    /** Most recently watched channels for the active playlist, newest first. */
    val watchHistory: List<IptvChannel> = emptyList(),
    /** Most recently launched NAS videos, shared with the main browser. */
    val nasWatchHistory: List<NasWatchHistoryEntry> = emptyList(),
    /** Long-press → Assign EPG dialog. */
    val assignEpgChannel: IptvChannel? = null,
    val assignEpgQuery: String = "",
    val assignEpgResults: List<EpgChannelEntry> = emptyList(),
    /** Active mapping or M3U tvg-id for the channel being assigned. */
    val assignEpgCurrentId: String? = null,
    /** True while bulk name→EPG matching is running. */
    val epgMatching: Boolean = false,
    /** Channels processed so far during AI/local EPG matching. */
    val epgMatchDone: Int = 0,
    val epgMatchTotal: Int = 0,
    /** Live per-channel match log for the on-screen progress panel. */
    val epgMatchLog: List<EpgMatchLogLine> = emptyList(),
    /** Shown under the progress header when matching finishes. */
    val epgMatchSummary: String? = null,
)

class IptvViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val iptvRepository: IptvRepository,
    private val favorites: IptvFavoritesStore,
    private val channelCustom: IptvChannelCustomStore,
    private val parental: IptvParentalStore,
    private val recordings: IptvRecordingStore,
    private val watchHistory: IptvWatchHistoryStore,
    private val searchHistory: IptvSearchHistoryStore,
    private val nasWatchHistory: NasWatchHistoryStore,
    private val resumeStore: LocalResumeStore,
    private val resumeMonitor: ResumeMonitor,
    private val progressSync: NasProgressSync,
    private val nasRepository: NasRepository,
    private val playerLauncher: MediaPlayerLauncher
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(IptvUiState())
    val state: StateFlow<IptvUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var epgMatchJob: Job? = null
    private var rebuildJob: Job? = null

    /** Previously watched channel (any group) for the fullscreen "last channel" zap. */
    private var lastZapChannel: IptvChannel? = null
    /** Ordered channel sequence captured from the group when fullscreen opens. */
    private var fullscreenChannels: List<IptvChannel> = emptyList()

    init {
        // Paint immediately from whatever is already in memory / tiny groups file.
        val settings = settingsRepository.load()
        val activePlaylistId = settings.activeIptvPlaylist().id
        val catalog = iptvRepository.catalog.value
        val peekGroups = when {
            catalog.groups.isNotEmpty() &&
                (catalog.playlistId.isEmpty() ||
                    catalog.playlistId == activePlaylistId) -> catalog.groups
            else -> iptvRepository.peekGroupsFromDisk(activePlaylistId)
        }
        if (catalog.channels.isNotEmpty() && catalog.playlistId == activePlaylistId) {
            _state.update {
                it.copy(
                    settings = settings,
                    loading = false,
                    refreshing = true,
                    epgChannelCount = catalog.epgChannelCount,
                    recordings = recordings.list()
                )
            }
            rebuildRows(settings, catalog.groups, selectedGroup = _state.value.selectedGroup)
            // Start last-watched stream immediately — do not wait for EPG / reload.
            restoreWatchHistory(settings)
        } else if (peekGroups.isNotEmpty()) {
            val groups = buildList {
                add(IptvDefaults.FAVORITES_GROUP)
                add(IptvDefaults.ALL_GROUP)
                addAll(peekGroups)
            }
            _state.update {
                it.copy(
                    settings = settings,
                    loading = false,
                    refreshing = true,
                    groups = groups,
                    selectedGroup = IptvDefaults.FAVORITES_GROUP,
                    rows = emptyList(),
                    recordings = recordings.list()
                )
            }
        } else {
            _state.update {
                it.copy(
                    settings = settings,
                    loading = false,
                    refreshing = true,
                    recordings = recordings.list()
                )
            }
        }
        reload(force = false)
    }

    fun reload(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val settings = settingsRepository.load()
            _state.update {
                it.copy(
                    settings = settings,
                    loading = false,
                    refreshing = true,
                    error = null,
                    recordings = recordings.list()
                )
            }
            val playlist = settings.activeIptvPlaylist()
            syncExtraWantedEpgIds(playlist.id)

            // 1) Channels from memory / snapshot / disk — never blocks on EPG.
            val channelsCatalog = iptvRepository.ensureChannelsLoaded(playlist, forceRefresh = force)
            // Heal EPG assignments keyed only by URL-hash channel ids (old builds / imported
            // backups) onto stable identity keys, then re-sync the wanted XMLTV ids.
            channelCustom.migrateEpgAssignments(playlist.id, channelsCatalog.channels)
            syncExtraWantedEpgIds(playlist.id)
            rebuildRowsAndWait(settings, channelsCatalog.groups, selectedGroup = _state.value.selectedGroup)
            restoreWatchHistory(settings)
            _state.update {
                it.copy(
                    loading = false,
                    error = channelsCatalog.error,
                    epgChannelCount = channelsCatalog.epgChannelCount,
                    message = when {
                        force -> "Refreshing playlist…"
                        channelsCatalog.channelsFromDisk ->
                            "Loaded ${channelsCatalog.channels.size} channels from cache"
                        else -> it.message
                    }
                )
            }

            // 2) EPG + stale upgrades in the background — must not gate the stream.
            launch {
                try {
                    val epgCatalog = iptvRepository.ensureEpgLoaded(playlist, forceRefresh = force)
                    iptvRepository.ensureProgrammesForIds(playlist, channelCustom.allEpgIds(playlist.id))
                    rebuildRows(settings, epgCatalog.groups, selectedGroup = _state.value.selectedGroup)
                    val epgNote = when {
                        epgCatalog.epgChannelCount <= 0 -> "EPG none"
                        epgCatalog.epgFromDisk -> "EPG disk ${epgCatalog.epgChannelCount} ch"
                        else -> "EPG net ${epgCatalog.epgChannelCount} ch"
                    }
                    _state.update {
                        it.copy(
                            error = epgCatalog.error ?: it.error,
                            epgChannelCount = epgCatalog.epgChannelCount,
                            epgVersion = it.epgVersion + 1,
                            message = when {
                                force -> "Refreshed · ${epgCatalog.channels.size} channels · $epgNote"
                                epgCatalog.epgFromDisk || epgCatalog.epgChannelCount > 0 ->
                                    "Cache ready · ${epgCatalog.channels.size} ch · $epgNote"
                                else -> it.message
                            }
                        )
                    }

                    if (!force) {
                        if (channelsCatalog.channelsStale) {
                            val refreshed = iptvRepository.refreshChannelsIfStale(playlist)
                            if (refreshed.loadedAtMs != channelsCatalog.loadedAtMs) {
                                rebuildRows(
                                    settings,
                                    refreshed.groups,
                                    selectedGroup = _state.value.selectedGroup
                                )
                            }
                        }
                        val epgRefreshed = iptvRepository.refreshEpgIfStale(playlist)
                        if (epgRefreshed.epgLoadedAtMs != epgCatalog.epgLoadedAtMs) {
                            rebuildRows(
                                settings,
                                epgRefreshed.groups,
                                selectedGroup = _state.value.selectedGroup
                            )
                            _state.update {
                                it.copy(
                                    epgChannelCount = epgRefreshed.epgChannelCount,
                                    epgVersion = it.epgVersion + 1
                                )
                            }
                        }
                    }
                } finally {
                    _state.update { it.copy(refreshing = false) }
                }
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        val settings = settingsRepository.load()
        val updated = settings.copy(activeIptvPlaylistId = playlistId)
        settingsRepository.save(updated)
        _state.update {
            it.copy(
                settings = updated,
                selectedGroup = IptvDefaults.FAVORITES_GROUP,
                previewChannel = null,
                watchHistory = emptyList()
            )
        }
        reload(force = true)
    }

    fun selectGroup(group: String) {
        if (parental.isGroupLocked(group) && !_state.value.unlockedSession) {
            _state.update { it.copy(pinPrompt = true, pendingGroup = group) }
            return
        }
        applyGroup(group)
    }

    fun submitPin(pin: String) {
        if (!parental.verifyPin(pin)) {
            _state.update { it.copy(message = "Incorrect PIN") }
            return
        }
        val group = _state.value.pendingGroup
        _state.update { it.copy(pinPrompt = false, pendingGroup = null, unlockedSession = true) }
        if (group != null) applyGroup(group)
    }

    fun dismissPin() {
        _state.update { it.copy(pinPrompt = false, pendingGroup = null) }
    }

    fun toggleFavorite(channel: IptvChannel) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        favorites.toggle(playlistId, channel.id)
        rebuildRows(_state.value.settings, _state.value.groups.filter {
            it != IptvDefaults.FAVORITES_GROUP && it != IptvDefaults.ALL_GROUP
        }, _state.value.selectedGroup)
    }

    fun renameChannel(channelId: String, newName: String) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        val originalName = iptvRepository.catalog.value.channels
            .firstOrNull { it.id == channelId }?.name
            ?: newName
        channelCustom.setDisplayName(playlistId, channelId, newName)
        val display = channelCustom.displayName(playlistId, channelId, originalName)
        val catalogGroups = iptvRepository.catalog.value.groups
        rebuildRows(_state.value.settings, catalogGroups, _state.value.selectedGroup)
        _state.update { st ->
            val preview = st.previewChannel
            st.copy(
                previewChannel = if (preview?.id == channelId) {
                    preview.copy(name = display)
                } else {
                    preview
                },
                message = if (newName.isBlank()) "Channel name reset" else "Channel renamed"
            )
        }
    }

    fun moveChannel(channelId: String, delta: Int) {
        val rows = _state.value.rows.toMutableList()
        val index = rows.indexOfFirst { it.channel.id == channelId }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, rows.lastIndex)
        if (target == index) return
        val item = rows.removeAt(index)
        rows.add(target, item)
        // In-memory only while dragging — persist on [commitChannelOrder] so key-repeat
        // cannot ANR the main thread with prefs I/O + full EPG row rebuilds.
        _state.update { it.copy(rows = rows) }
    }

    fun moveChannelToEdge(channelId: String, toTop: Boolean) {
        val rows = _state.value.rows.toMutableList()
        val index = rows.indexOfFirst { it.channel.id == channelId }
        if (index < 0) return
        val target = if (toTop) 0 else rows.lastIndex
        if (target == index) return
        val item = rows.removeAt(index)
        rows.add(target, item)
        _state.update { it.copy(rows = rows) }
    }

    /** One-shot from the options sheet — move and persist immediately. */
    fun moveChannelToEdgeAndCommit(channelId: String, toTop: Boolean) {
        moveChannelToEdge(channelId, toTop)
        commitChannelOrder()
        _state.update {
            it.copy(message = if (toTop) "Moved to top" else "Moved to bottom")
        }
    }

    /** Persist the in-memory channel order after Move channel… is dropped. */
    fun commitChannelOrder() {
        val group = _state.value.selectedGroup
        val ids = _state.value.rows.map { it.channel.id }
        if (ids.isEmpty()) return
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupOrderMode(playlistId, group, GroupChannelOrder.CUSTOM)
        channelCustom.setOrder(playlistId, group, ids)
        _state.update {
            it.copy(groupOrderModes = it.groupOrderModes + (group to GroupChannelOrder.CUSTOM))
        }
    }

    fun setGroupOrderMode(groupKey: String, mode: GroupChannelOrder) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupOrderMode(playlistId, groupKey, mode)
        rebuildRows(_state.value.settings, iptvRepository.catalog.value.groups, groupKey)
        _state.update { it.copy(message = "${mode.label} channel order") }
    }

    fun renameGroup(groupKey: String, displayName: String) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupDisplayName(playlistId, groupKey, displayName)
        rebuildRows(_state.value.settings, iptvRepository.catalog.value.groups, groupKey)
        _state.update {
            val shown = it.groupDisplayNames[groupKey] ?: groupKey
            it.copy(message = "Group renamed to $shown")
        }
    }

    /** Reorder the group wheel itself; hidden groups are re-sunk to the bottom on rebuild. */
    fun moveGroup(groupKey: String, delta: Int) {
        val keys = _state.value.groups.toMutableList()
        val index = keys.indexOf(groupKey)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, keys.lastIndex)
        if (target == index) return
        keys.removeAt(index)
        keys.add(target, groupKey)
        // In-memory only while dragging — persist on [commitGroupOrder].
        _state.update { it.copy(groups = keys) }
    }

    fun moveGroupToEdge(groupKey: String, toTop: Boolean) {
        val keys = _state.value.groups.toMutableList()
        val index = keys.indexOf(groupKey)
        if (index < 0) return
        val target = if (toTop) 0 else keys.lastIndex
        if (target == index) return
        keys.removeAt(index)
        keys.add(target, groupKey)
        _state.update { it.copy(groups = keys) }
    }

    fun commitGroupOrder() {
        val keys = _state.value.groups
        if (keys.isEmpty()) return
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupOrder(playlistId, keys)
    }

    fun toggleGroupHidden(groupKey: String) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        val hide = groupKey !in channelCustom.hiddenGroups(playlistId)
        channelCustom.setGroupHidden(playlistId, groupKey, hide)
        rebuildRows(_state.value.settings, iptvRepository.catalog.value.groups, _state.value.selectedGroup)
        _state.update {
            val shown = it.groupDisplayNames[groupKey] ?: groupKey
            it.copy(
                message = if (hide) {
                    "$shown hidden — find it greyed at the bottom of the group list"
                } else {
                    "$shown is visible again"
                }
            )
        }
    }

    /** Wheel settle / zap — always preview, never enter fullscreen. */
    fun zapTo(channel: IptvChannel) {
        if (parental.isGroupLocked(channel.group) && !_state.value.unlockedSession) {
            _state.update {
                it.copy(pinPrompt = true, pendingGroup = channel.group, message = "Enter PIN to watch")
            }
            return
        }
        if (_state.value.previewChannel?.id == channel.id) return
        lastZapChannel = _state.value.previewChannel
        recordWatched(channel)
        _state.update {
            it.copy(previewChannel = channel, fullscreen = false, message = null)
        }
        // Controlling a room: wheel selection must drive the TV, not a silent local preview.
        if (RemoteTargetStore.isControllingRemote()) {
            playChannelOnRemote(channel)
        }
    }

    /** Preview a search hit and land the browse UI on that channel's group. */
    fun zapFromSearch(channel: IptvChannel) {
        if (parental.isGroupLocked(channel.group) && !_state.value.unlockedSession) {
            _state.update {
                it.copy(pinPrompt = true, pendingGroup = channel.group, message = "Enter PIN to watch")
            }
            return
        }
        applyGroup(channel.group)
        if (_state.value.previewChannel?.id != channel.id) {
            lastZapChannel = _state.value.previewChannel
            recordWatched(channel)
        }
        _state.update {
            it.copy(previewChannel = channel, fullscreen = false, message = null)
        }
        if (RemoteTargetStore.isControllingRemote()) {
            playChannelOnRemote(channel)
        }
    }

    /**
     * OK on a channel: start (or keep) playback and enter fullscreen watching.
     * Back from fullscreen returns to the channel list / EPG.
     * When controlling a room, playback runs on that TV only (tablet stays browse UI).
     */
    fun selectChannel(channel: IptvChannel) {
        if (parental.isGroupLocked(channel.group) && !_state.value.unlockedSession) {
            _state.update {
                it.copy(pinPrompt = true, pendingGroup = channel.group, message = "Enter PIN to watch")
            }
            return
        }
        if (RemoteTargetStore.isControllingRemote()) {
            markRemoteChannelSelected(channel)
            playChannelOnRemote(channel)
            return
        }
        viewModelScope.launch {
            runCatching {
                RemotePlayBridge.playLiveTv(channel.id) {
                    selectChannelLocal(channel)
                }
            }.onFailure { err ->
                _state.update { it.copy(message = err.message ?: "Remote play failed") }
            }
        }
    }

    private fun playChannelOnRemote(channel: IptvChannel) {
        viewModelScope.launch {
            runCatching {
                RemotePlayBridge.playLiveTv(channel.id) {
                    // Unreachable while a remote target is set.
                    selectChannelLocal(channel)
                }
            }.onFailure { err ->
                _state.update { it.copy(message = err.message ?: "Remote play failed") }
            }
        }
    }

    private fun markRemoteChannelSelected(channel: IptvChannel) {
        val current = _state.value.previewChannel
        if (current?.id != channel.id) {
            lastZapChannel = current
            recordWatched(channel)
        }
        if (_state.value.rows.none { it.channel.id == channel.id }) {
            applyGroup(channel.group)
        }
        captureFullscreenChannels()
        _state.update {
            it.copy(
                previewChannel = channel,
                fullscreen = false,
                message = RemotePlayBridge.controllingLabel(),
            )
        }
    }

    private fun selectChannelLocal(channel: IptvChannel) {
        val current = _state.value.previewChannel
        if (current?.id != channel.id) {
            lastZapChannel = current
            recordWatched(channel)
        }
        if (_state.value.rows.none { it.channel.id == channel.id }) {
            applyGroup(channel.group)
        }
        captureFullscreenChannels()
        _state.update {
            it.copy(
                previewChannel = channel,
                fullscreen = true,
                message = null
            )
        }
    }

    /** Fullscreen D-pad zap: previous (-1) / next (+1) channel in the current group, wraps around. */
    fun fullscreenStep(delta: Int) {
        if (fullscreenChannels.isEmpty()) captureFullscreenChannels()
        val channels = fullscreenChannels
        if (channels.isEmpty()) return
        val currentId = _state.value.previewChannel?.id
        val index = channels.indexOfFirst { it.id == currentId }
        val target = if (index < 0) {
            channels.first()
        } else {
            channels[((index + delta) % channels.size + channels.size) % channels.size]
        }
        switchWhileFullscreen(target)
    }

    /** Fullscreen D-pad zap: back to the previously watched channel (any group). */
    fun fullscreenLastChannel() {
        val target = lastZapChannel ?: run {
            _state.update { it.copy(message = "No previous channel yet") }
            return
        }
        switchWhileFullscreen(target)
    }

    private fun switchWhileFullscreen(channel: IptvChannel) {
        val current = _state.value.previewChannel
        if (current?.id == channel.id) return
        if (parental.isGroupLocked(channel.group) && !_state.value.unlockedSession) {
            _state.update {
                it.copy(pinPrompt = true, pendingGroup = channel.group, message = "Enter PIN to watch")
            }
            return
        }
        lastZapChannel = current
        recordWatched(channel)
        _state.update { it.copy(previewChannel = channel, message = null) }
        if (RemoteTargetStore.isControllingRemote()) {
            playChannelOnRemote(channel)
        }
    }

    fun playHistoryChannel(channel: IptvChannel) {
        if (RemoteTargetStore.isControllingRemote()) {
            selectChannel(channel)
            return
        }
        switchWhileFullscreen(channel)
    }

    fun refreshNasWatchHistory() {
        _state.update { it.copy(nasWatchHistory = nasWatchHistory.entries()) }
    }

    fun playHistoryVideo(entry: NasWatchHistoryEntry) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val merged = progressSync.readAndMerge(settings, entry.share, entry.path)
            val startPositionMs = when {
                merged?.watched == true -> null
                merged?.isMeaningful == true -> merged.positionMs
                else -> resumeStore.get(entry.path)?.positionMs
            }
            runCatching {
                RemotePlayBridge.playNasVideo(
                    share = entry.share,
                    path = entry.path,
                    title = entry.title,
                    positionMs = startPositionMs,
                    host = settings.host,
                ) {
                    playHistoryVideoLocal(entry, settings, startPositionMs)
                }
            }.onFailure { err ->
                _state.update { it.copy(message = err.message ?: "Remote play failed") }
            }
        }
    }

    private suspend fun playHistoryVideoLocal(
        entry: NasWatchHistoryEntry,
        settings: AppSettings,
        startPositionMs: Long?,
    ) {
        val result = runCatching {
            val mediaUri = LocalMediaProxyService.startAndAwait(
                context = ShieldVideoApp.instance,
                share = entry.share,
                path = entry.path,
                host = settings.host,
                title = entry.title,
            )
            val playerPackage = settings.playerPackage.ifBlank {
                MediaPlayerLauncher.VLC_PACKAGE
            }
            playerLauncher.play(
                playbackUri = mediaUri,
                relativePath = entry.path,
                title = entry.title,
                playerPackage = playerPackage,
                startPositionMs = startPositionMs,
            ) {
                nasWatchHistory.record(entry.share, entry.path, entry.title)
                val handoff = nasRepository.handoffUri(
                    settings,
                    entry.share,
                    entry.path,
                ).toString()
                resumeMonitor.start(
                    path = entry.path,
                    playerPackage = playerPackage,
                    playbackUri = handoff,
                    title = entry.title,
                    share = entry.share,
                    host = settings.host,
                )
            }
        }.getOrElse { error ->
            LocalMediaProxyService.stop(ShieldVideoApp.instance)
            PlayerLaunchResult.Failed(error.message ?: "Playback failed")
        }
        when (result) {
            PlayerLaunchResult.Success -> {
                _state.update { it.copy(nasWatchHistory = nasWatchHistory.entries()) }
            }
            PlayerLaunchResult.NotInstalled -> {
                LocalMediaProxyService.stop(ShieldVideoApp.instance)
                _state.update {
                    it.copy(message = "Selected video player is not installed")
                }
            }
            is PlayerLaunchResult.Failed -> {
                LocalMediaProxyService.stop(ShieldVideoApp.instance)
                _state.update { it.copy(message = result.message) }
            }
        }
    }

    fun openFullscreen() {
        val current = _state.value.previewChannel ?: return
        // Remote session: never open tablet fullscreen — play on the TV instead.
        if (RemoteTargetStore.isControllingRemote()) {
            selectChannel(current)
            return
        }
        // E.g. the auto-restored last channel may not be in the group currently shown;
        // switch to its group so zapping steps through the right channel list.
        if (_state.value.rows.none { it.channel.id == current.id }) {
            applyGroup(current.group)
        }
        captureFullscreenChannels()
        _state.update { it.copy(fullscreen = true) }
    }

    fun closeFullscreen() {
        fullscreenChannels = emptyList()
        // Land the browse UI on the channel actually playing: zapping (or the history
        // picker) may have moved to a channel outside the group the guide last showed.
        val current = _state.value.previewChannel
        if (current != null && _state.value.rows.none { it.channel.id == current.id }) {
            applyGroup(current.group)
        }
        _state.update { it.copy(fullscreen = false) }
    }

    fun showRemoteOnlyMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun captureFullscreenChannels() {
        // Rows are already filtered to the active group and sorted by the user's saved order.
        fullscreenChannels = _state.value.rows.map { it.channel }
    }

    private fun restoreWatchHistory(settings: AppSettings) {
        val playlistId = settings.activeIptvPlaylist().id
        val channelsById = iptvRepository.catalog.value.channels.associateBy { it.id }
        val resolved = watchHistory.channelIds(playlistId).mapNotNull(channelsById::get).map { channel ->
            val displayName = channelCustom.displayName(playlistId, channel.id, channel.name)
            if (displayName == channel.name) channel else channel.copy(name = displayName)
        }
        val lastPlayable = resolved.firstOrNull {
            !parental.isGroupLocked(it.group) || _state.value.unlockedSession
        }
        _state.update {
            it.copy(
                watchHistory = resolved,
                nasWatchHistory = nasWatchHistory.entries(),
                previewChannel = it.previewChannel ?: lastPlayable
            )
        }
    }

    private fun recordWatched(channel: IptvChannel) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        watchHistory.record(playlistId, channel.id)
        channelCustom.incrementWatchCount(playlistId, channel.id)
        val updated = buildList {
            add(channel)
            addAll(_state.value.watchHistory.filterNot { it.id == channel.id })
        }.take(10)
        _state.update { it.copy(watchHistory = updated) }
    }

    /** Launch external player (VLC) for the current preview / given channel. */
    fun playExternal(channel: IptvChannel? = null) {
        val target = channel ?: _state.value.previewChannel ?: return
        if (parental.isGroupLocked(target.group) && !_state.value.unlockedSession) {
            _state.update { it.copy(pinPrompt = true, pendingGroup = target.group, message = "Enter PIN to watch") }
            return
        }
        // Remote session: never launch VLC on the tablet — play on the TV.
        if (RemoteTargetStore.isControllingRemote()) {
            selectChannel(target)
            return
        }
        val settings = _state.value.settings
        val result = playerLauncher.playStream(
            streamUrl = target.streamUrl,
            title = target.name,
            playerPackage = settings.playerPackage
        )
        when (result) {
            PlayerLaunchResult.Success -> _state.update {
                it.copy(fullscreen = false, message = "Opened in player: ${target.name}")
            }
            PlayerLaunchResult.NotInstalled -> _state.update { it.copy(showVlcMissing = true) }
            is PlayerLaunchResult.Failed -> _state.update { it.copy(message = result.message) }
        }
    }

    /** @deprecated Prefer [selectChannel] for browse; kept for call sites that mean preview/fullscreen. */
    fun play(channel: IptvChannel) = selectChannel(channel)

    fun playCatchup(channel: IptvChannel, programme: IptvProgramme) {
        if (RemoteTargetStore.isControllingRemote()) {
            _state.update {
                it.copy(message = "Catch-up is not available while controlling a room")
            }
            return
        }
        val url = CatchupUrlBuilder.build(channel, programme)
        if (url.isNullOrBlank()) {
            _state.update { it.copy(message = "Catch-up not available for this channel") }
            return
        }
        val result = playerLauncher.playStream(
            streamUrl = url,
            title = "${channel.name} · ${programme.title}",
            playerPackage = _state.value.settings.playerPackage
        )
        when (result) {
            PlayerLaunchResult.Success -> Unit
            PlayerLaunchResult.NotInstalled -> _state.update { it.copy(showVlcMissing = true) }
            is PlayerLaunchResult.Failed -> _state.update { it.copy(message = result.message) }
        }
    }

    fun programmesFor(channel: IptvChannel): List<IptvProgramme> {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        val override = channelCustom.epgId(playlistId, channel)
        if (!override.isNullOrBlank()) {
            return iptvRepository.programmesForTvgId(override)
        }
        return iptvRepository.programmesFor(channel)
    }

    fun openGuide(channel: IptvChannel) {
        _state.update {
            it.copy(
                detailChannel = channel,
                detailProgrammes = programmesFor(channel)
            )
        }
    }

    fun closeGuide() {
        _state.update { it.copy(detailChannel = null, detailProgrammes = emptyList()) }
    }

    fun openAssignEpg(channel: IptvChannel) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        val mapped = channelCustom.epgId(playlistId, channel)
        val current = mapped ?: channel.tvgId?.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(
                assignEpgChannel = channel,
                assignEpgQuery = "",
                assignEpgResults = rankedEpgSuggestions(channel),
                assignEpgCurrentId = current
            )
        }
    }

    fun setAssignEpgQuery(query: String) {
        val channel = _state.value.assignEpgChannel
        _state.update {
            it.copy(
                assignEpgQuery = query,
                assignEpgResults = if (query.isBlank() && channel != null) {
                    rankedEpgSuggestions(channel)
                } else {
                    iptvRepository.searchEpgChannels(query)
                }
            )
        }
    }

    fun closeAssignEpg() {
        _state.update {
            it.copy(
                assignEpgChannel = null,
                assignEpgQuery = "",
                assignEpgResults = emptyList(),
                assignEpgCurrentId = null
            )
        }
    }

    fun assignEpgToChannel(channel: IptvChannel, epgChannelId: String?) {
        val settings = _state.value.settings
        val playlist = settings.activeIptvPlaylist()
        channelCustom.setEpgId(playlist.id, channel, epgChannelId)
        syncExtraWantedEpgIds(playlist.id)
        val trimmed = epgChannelId?.trim().orEmpty()
        // Close the dialog right away — parsing programmes for the new id can take
        // seconds (full XMLTV scan) and must not hold the UI hostage.
        _state.update {
            it.copy(
                assignEpgChannel = null,
                assignEpgQuery = "",
                assignEpgResults = emptyList(),
                assignEpgCurrentId = null,
                message = if (trimmed.isEmpty()) {
                    "EPG mapping cleared"
                } else {
                    "EPG assigned — loading programmes…"
                }
            )
        }
        viewModelScope.launch {
            if (trimmed.isNotEmpty()) {
                iptvRepository.ensureProgrammesForIds(playlist, setOf(trimmed))
            }
            rebuildRows(settings, iptvRepository.catalog.value.groups, _state.value.selectedGroup)
            _state.update { it.copy(epgVersion = it.epgVersion + 1) }
        }
    }

    /**
     * Match M3U channel names to XMLTV ids.
     * **Only runs when the user picks AI match EPG…** — never on app open / refresh.
     */
    fun autoMatchEpg(groupKey: String? = null, userInitiated: Boolean = true) {
        // Hard stop: nothing automatic. AI and local bulk match are manual only.
        if (!userInitiated) return
        val playlist = _state.value.settings.activeIptvPlaylist()
        if (_state.value.epgMatching) return
        epgMatchJob?.cancel()
        epgMatchJob = viewModelScope.launch {
            val settings = settingsRepository.load()
            _state.update {
                it.copy(
                    settings = settings,
                    epgMatching = true,
                    epgMatchDone = 0,
                    epgMatchTotal = 0,
                    epgMatchLog = emptyList(),
                    epgMatchSummary = null,
                    message = "AI matching channels to EPG…"
                )
            }
            val result = withContext(Dispatchers.IO) {
                val epgChannels = iptvRepository.epgChannels()
                if (epgChannels.isEmpty()) {
                    return@withContext AutoMatchResult(0, 0, "EPG channel list empty — refresh Live TV first")
                }
                val catalog = iptvRepository.catalog.value
                val targets = channelsForMatchScope(catalog.channels, groupKey)
                    .filter { !channelHasWorkingEpg(playlist.id, it) }
                if (targets.isEmpty()) {
                    return@withContext AutoMatchResult(0, 0, "All channels already have EPG")
                }

                val apiKey = settings.iptvEpgAiApiKey.trim()
                if (apiKey.isEmpty()) {
                    return@withContext AutoMatchResult(
                        0,
                        targets.size,
                        "Add an AI API key in Settings → Live TV (Gemini free key or OpenAI), Save, then run AI match EPG again"
                    )
                }

                _state.update {
                    it.copy(
                        epgMatchTotal = targets.size,
                        epgMatchDone = 0,
                        epgMatchSummary = "Indexing EPG (${epgChannels.size} channels)…",
                    )
                }

                var lastUiMs = 0L
                var pendingDone = 0
                var pendingSummary = ""
                var pendingLog = ArrayList<EpgMatchLogLine>()

                fun flushProgress(force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastUiMs < 120 && pendingLog.size < 8) return
                    lastUiMs = now
                    val batch = pendingLog
                    pendingLog = ArrayList()
                    val doneSnap = pendingDone
                    val summarySnap = pendingSummary
                    _state.update {
                        it.copy(
                            epgMatchLog = if (batch.isEmpty()) it.epgMatchLog else it.epgMatchLog + batch,
                            epgMatchDone = doneSnap.coerceIn(0, it.epgMatchTotal),
                            epgMatchSummary = summarySnap.ifBlank { it.epgMatchSummary },
                            message = summarySnap.ifBlank { it.message },
                        )
                    }
                }

                fun appendLog(channel: IptvChannel, epg: EpgChannelEntry?) {
                    pendingLog += EpgMatchLogLine(
                        channelName = channel.name,
                        epgName = epg?.name?.takeIf { it.isNotBlank() } ?: epg?.id,
                        matched = epg != null,
                    )
                    flushProgress(force = false)
                }

                fun setProgress(done: Int, summary: String) {
                    pendingDone = done
                    pendingSummary = summary
                    flushProgress(force = false)
                }

                // 1) Fast local pass first so the panel moves immediately.
                setProgress(0, "Local high-confidence pass…")
                val localHits = EpgChannelMatcher.autoMatchAll(targets, epgChannels, { false }) { done, total, ch, match ->
                    if (match != null) appendLog(ch, match.epg)
                    setProgress(done, "Local match $done / $total")
                }
                flushProgress(force = true)
                localHits.forEach { (ch, match) ->
                    channelCustom.setEpgId(playlist.id, ch, match.epg.id)
                }

                val remaining = targets.filter { ch ->
                    localHits.keys.none { it.id == ch.id }
                }

                var usedAi = false
                var aiError: String? = null
                val aiAssignments: Map<IptvChannel, String> = if (remaining.isEmpty()) {
                    emptyMap()
                } else {
                    setProgress(
                        localHits.size,
                        "Asking Gemini for ${remaining.size} unmatched…",
                    )
                    var aiDone = 0
                    val ai = EpgAiMatcher().match(
                        channels = remaining,
                        epgChannels = epgChannels,
                        apiKey = apiKey,
                        provider = settings.iptvEpgAiProvider,
                        openAiBaseUrl = settings.iptvEpgAiOpenAiBaseUrl,
                        onProgress = { done, total ->
                            setProgress(
                                localHits.size + done,
                                "Gemini · $done / $total remaining",
                            )
                        },
                        onChannelResult = { ch, epg ->
                            appendLog(ch, epg)
                            aiDone++
                            setProgress(
                                localHits.size + aiDone,
                                "Gemini · $aiDone / ${remaining.size} remaining",
                            )
                        },
                    )
                    if (ai.error != null && ai.assignments.isEmpty()) {
                        aiError = ai.error
                        android.util.Log.e("IptvEpgMatch", "AI failed: $aiError")
                        remaining.forEach { ch -> appendLog(ch, null) }
                        flushProgress(force = true)
                        emptyMap()
                    } else {
                        usedAi = true
                        flushProgress(force = true)
                        val byId = remaining.associateBy { it.id }
                        ai.assignments.mapNotNull { (channelId, epgId) ->
                            byId[channelId]?.let { it to epgId }
                        }.toMap()
                    }
                }

                aiAssignments.forEach { (ch, epgId) ->
                    channelCustom.setEpgId(playlist.id, ch, epgId)
                }
                val assignments = buildMap {
                    localHits.forEach { (ch, match) -> put(ch, match.epg.id) }
                    putAll(aiAssignments)
                }

                syncExtraWantedEpgIds(playlist.id)
                if (assignments.isNotEmpty()) {
                    iptvRepository.ensureProgrammesForIds(playlist, assignments.values.toSet())
                }
                val unmatched = targets.count { !channelHasWorkingEpg(playlist.id, it) }
                flushProgress(force = true)
                AutoMatchResult(
                    matched = assignments.size,
                    stillUnmatched = unmatched,
                    message = when {
                        assignments.isEmpty() && unmatched == 0 ->
                            "All channels already have EPG"
                        assignments.isEmpty() && aiError != null ->
                            aiError
                        assignments.isEmpty() ->
                            "No confident matches — try Assign EPG on a channel"
                        usedAi ->
                            "Matched ${assignments.size} (${localHits.size} local · ${aiAssignments.size} AI)" +
                                if (unmatched > 0) " · $unmatched still need Assign EPG" else ""
                        aiError != null ->
                            "Local matched ${localHits.size}" +
                                (if (unmatched > 0) " · $unmatched still need Assign EPG" else "") +
                                " · AI: ${aiError.take(80)}"
                        else ->
                            "Local matched ${assignments.size}" +
                                if (unmatched > 0) " · $unmatched still need Assign EPG" else ""
                    }
                )
            }
            // Heavy list rebuild off the UI thread — doing this on Main caused Bedroom ANR.
            rebuildRowsAndWait(
                _state.value.settings,
                iptvRepository.catalog.value.groups,
                _state.value.selectedGroup
            )
            // If the user cancelled, dismissEpgMatchProgress already cleared the panel —
            // do not recreate it with a completion summary.
            if (!isActive || epgMatchJob == null) return@launch
            _state.update {
                it.copy(
                    epgMatching = false,
                    epgVersion = it.epgVersion + 1,
                    epgMatchSummary = result.message,
                    message = if (it.epgMatchLog.isNotEmpty()) null else result.message,
                )
            }
        }
    }

    fun dismissEpgMatchProgress() {
        epgMatchJob?.cancel()
        epgMatchJob = null
        _state.update {
            it.copy(
                epgMatching = false,
                epgMatchLog = emptyList(),
                epgMatchSummary = null,
                epgMatchDone = 0,
                epgMatchTotal = 0,
            )
        }
    }

    /** Cancel in-flight AI match (Back / Cancel button) and close the overlay. */
    fun cancelEpgMatch() = dismissEpgMatchProgress()

    private data class AutoMatchResult(
        val matched: Int,
        val stillUnmatched: Int,
        val message: String
    )

    private fun channelsForMatchScope(
        all: List<IptvChannel>,
        groupKey: String?
    ): List<IptvChannel> {
        if (groupKey.isNullOrBlank()) return all
        return when (groupKey) {
            IptvDefaults.ALL_GROUP -> all
            IptvDefaults.FAVORITES_GROUP -> {
                val playlistId = _state.value.settings.activeIptvPlaylist().id
                val favIds = favorites.getFavorites(playlistId)
                all.filter { it.id in favIds }
            }
            else -> all.filter { it.group == groupKey }
        }
    }

    private fun channelHasWorkingEpg(playlistId: String, channel: IptvChannel): Boolean {
        val override = channelCustom.epgId(playlistId, channel)
        if (!override.isNullOrBlank()) {
            return iptvRepository.programmesForTvgId(override).isNotEmpty() ||
                iptvRepository.epgChannels().any { it.id.equals(override, ignoreCase = true) }
        }
        val tvg = channel.tvgId?.trim().orEmpty()
        if (tvg.isEmpty()) return false
        return iptvRepository.programmesForTvgId(tvg).isNotEmpty() ||
            iptvRepository.epgChannels().any { it.id.equals(tvg, ignoreCase = true) }
    }

    private fun rankedEpgSuggestions(channel: IptvChannel): List<EpgChannelEntry> {
        val ranked = EpgChannelMatcher.rank(channel, iptvRepository.epgChannels(), limit = 80)
        if (ranked.isNotEmpty()) return ranked.map { it.epg }
        return iptvRepository.searchEpgChannels("")
    }

    fun openSearch() {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchOpen = true,
                searchQuery = "",
                searchChannelResults = emptyList(),
                searchProgrammeResults = emptyList(),
                searchHistory = searchHistory.queries(playlistId)
            )
        }
    }
    fun closeSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchOpen = false,
                searchQuery = "",
                searchChannelResults = emptyList(),
                searchProgrammeResults = emptyList()
            )
        }
    }

    fun setSearchQuery(query: String) {
        // Keep the text field snappy; heavy channel/EPG scan runs off the main thread.
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(searchChannelResults = emptyList(), searchProgrammeResults = emptyList())
            }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            delay(180)
            if (!isActive) return@launch
            val settings = _state.value.settings
            val playlistId = settings.activeIptvPlaylist().id
            val favs = favorites.getFavorites(playlistId)
            val unlocked = _state.value.unlockedSession
            val channels = iptvRepository.catalog.value.channels
            val channelHits = iptvRepository.searchChannels(query, channels, limit = 60).map { ch ->
                val display = channelCustom.displayName(playlistId, ch.id, ch.name)
                val confirmedBadges = channelCustom.streamBadges(playlistId, ch.id)
                IptvChannelRow(
                    channel = if (display == ch.name) ch else ch.copy(name = display),
                    favorite = ch.id in favs,
                    nowNext = XmltvParser.nowNext(programmesForResolved(ch, playlistId)),
                    locked = parental.isGroupLocked(ch.group) && !unlocked,
                    badges = confirmedBadges.ifEmpty { ChannelQuality.labelsFor(ch.name) },
                    badgesConfirmed = confirmedBadges.isNotEmpty()
                )
            }
            if (!isActive) return@launch
            val programmeHits = iptvRepository.searchProgrammes(query, limit = 40)
            if (!isActive) return@launch
            _state.update {
                // Drop stale results if the user kept typing.
                if (it.searchQuery != query) return@update it
                it.copy(
                    searchChannelResults = channelHits,
                    searchProgrammeResults = programmeHits
                )
            }
        }
    }

    fun recordSearch() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank()) return
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        searchHistory.record(playlistId, query)
        _state.update { it.copy(searchHistory = searchHistory.queries(playlistId)) }
    }

    fun removeSearchHistory(query: String) {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        searchHistory.remove(playlistId, query)
        _state.update { it.copy(searchHistory = searchHistory.queries(playlistId)) }
    }

    fun scheduleRecording(channel: IptvChannel, programme: IptvProgramme) {
        val now = System.currentTimeMillis()
        val catchupUrl = CatchupUrlBuilder.build(channel, programme)
        val recordingPastProgramme = programme.stopMs <= now && !catchupUrl.isNullOrBlank()
        val streamUrl = when {
            recordingPastProgramme -> catchupUrl
            else -> channel.streamUrl
        }
        val duration = (programme.stopMs - programme.startMs).coerceAtLeast(60_000L)
        val recording = IptvRecording(
            channelId = channel.id,
            channelName = channel.name,
            title = programme.title,
            startMs = programme.startMs.coerceAtLeast(now),
            stopMs = if (recordingPastProgramme) now + duration else programme.stopMs,
            streamUrl = streamUrl,
            status = IptvRecordingStatus.SCHEDULED
        )
        recordings.upsert(recording)
        IptvRecordingScheduler.schedule(getApplication(), recording)
        _state.update {
            it.copy(
                recordings = recordings.list(),
                message = "Recording scheduled: ${programme.title}"
            )
        }
    }

    fun recordLive(channel: IptvChannel, minutes: Int = 60) {
        val now = System.currentTimeMillis()
        val recording = IptvRecording(
            channelId = channel.id,
            channelName = channel.name,
            title = "${channel.name} (live)",
            startMs = now,
            stopMs = now + minutes * 60_000L,
            streamUrl = channel.streamUrl,
            status = IptvRecordingStatus.SCHEDULED
        )
        recordings.upsert(recording)
        IptvRecordingScheduler.schedule(getApplication(), recording)
        _state.update {
            it.copy(recordings = recordings.list(), message = "Recording ${channel.name} for $minutes min")
        }
    }

    fun toggleRecordings() {
        _state.update {
            it.copy(showRecordings = !it.showRecordings, recordings = recordings.list())
        }
    }

    fun refreshRecordings() {
        _state.update { it.copy(recordings = recordings.list()) }
    }

    /** Cancel a scheduled/active recording that overlaps [programme] on [channel]. */
    fun cancelProgrammeRecording(channel: IptvChannel, programme: IptvProgramme) {
        val overlapping = recordings.list().filter { r ->
            r.channelId == channel.id &&
                (r.status == IptvRecordingStatus.SCHEDULED ||
                    r.status == IptvRecordingStatus.RECORDING) &&
                r.startMs < programme.stopMs &&
                r.stopMs > programme.startMs
        }
        if (overlapping.isEmpty()) return
        val now = System.currentTimeMillis()
        overlapping.forEach { r ->
            if (r.status == IptvRecordingStatus.RECORDING) {
                // Clear ● REC immediately; service remuxes/saves in the background.
                recordings.upsert(
                    r.copy(
                        status = IptvRecordingStatus.SAVING,
                        stopMs = minOf(r.stopMs, now),
                        error = "Saving…",
                    )
                )
                IptvRecordingService.finish(getApplication(), r.id)
            } else {
                IptvRecordingScheduler.cancel(getApplication(), r.id)
                recordings.remove(r.id)
            }
        }
        _state.update {
            it.copy(
                recordings = recordings.list(),
                message = "Recording stopped: ${programme.title}",
                epgVersion = it.epgVersion + 1,
            )
        }
        pollRecordingUpdates()
    }

    /** Manual stop: keeps everything captured so far and saves it to the recording folder. */
    fun stopRecording(id: String) {
        val current = recordings.list().firstOrNull { it.id == id }
        if (current != null &&
            (current.status == IptvRecordingStatus.RECORDING ||
                current.status == IptvRecordingStatus.SCHEDULED)
        ) {
            val now = System.currentTimeMillis()
            recordings.upsert(
                current.copy(
                    status = IptvRecordingStatus.SAVING,
                    stopMs = minOf(current.stopMs, now),
                    error = "Saving…",
                )
            )
        }
        IptvRecordingService.finish(getApplication(), id)
        _state.update {
            it.copy(
                recordings = recordings.list(),
                message = "Stopping recording — packaging MP4",
                epgVersion = it.epgVersion + 1,
            )
        }
        pollRecordingUpdates()
    }

    /** Refresh UI while a recording finishes remux/save. */
    private fun pollRecordingUpdates() {
        viewModelScope.launch {
            repeat(40) {
                delay(500)
                val list = recordings.list()
                _state.update { it.copy(recordings = list) }
                if (list.none { it.status == IptvRecordingStatus.SAVING || it.status == IptvRecordingStatus.RECORDING }) {
                    _state.update { it.copy(epgVersion = it.epgVersion + 1) }
                    return@launch
                }
            }
            refreshRecordings()
        }
    }

    fun removeRecording(id: String) {
        IptvRecordingScheduler.cancel(getApplication(), id)
        recordings.remove(id)
        _state.update { it.copy(recordings = recordings.list()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun dismissVlcMissing() = _state.update { it.copy(showVlcMissing = false) }

    /** Re-read Settings (e.g. EPG-in-preview toggle) without re-fetching playlists. */
    fun refreshSettings() {
        val settings = settingsRepository.load()
        rebuildRows(settings, iptvRepository.catalog.value.groups, _state.value.selectedGroup)
    }

    fun canCatchup(channel: IptvChannel, programme: IptvProgramme): Boolean =
        CatchupUrlBuilder.canCatchup(channel, programme) ||
            (channel.catchupDays > 0 && CatchupUrlBuilder.build(channel, programme) != null)

    private fun applyGroup(group: String) {
        val settings = _state.value.settings
        val catalogGroups = iptvRepository.catalog.value.groups
        val canonical = resolveGroupKey(group, catalogGroups)
        // Publish selection on Main immediately so the guide can open; row build is off-Main.
        _state.update { it.copy(selectedGroup = canonical) }
        rebuildRows(settings, catalogGroups, canonical)
    }

    /** Map provider / channel.group strings onto the exact key used in the group list. */
    private fun resolveGroupKey(raw: String, catalogGroups: List<String>): String {
        val trimmed = raw.trim()
        if (trimmed.equals(IptvDefaults.FAVORITES_GROUP, ignoreCase = true)) {
            return IptvDefaults.FAVORITES_GROUP
        }
        if (trimmed.equals(IptvDefaults.ALL_GROUP, ignoreCase = true)) {
            return IptvDefaults.ALL_GROUP
        }
        return catalogGroups.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    /**
     * Schedule a channel-list rebuild off the main thread.
     * Opening large groups (e.g. United Kingdom) on Main caused Bedroom ANRs (~8s KeyEvent).
     */
    private fun rebuildRows(settings: AppSettings, catalogGroups: List<String>, selectedGroup: String) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch(Dispatchers.Default) {
            rebuildRowsNow(settings, catalogGroups, selectedGroup)
        }
    }

    /** Await a rebuild (reload / EPG match) so callers can continue with fresh rows. */
    private suspend fun rebuildRowsAndWait(
        settings: AppSettings,
        catalogGroups: List<String>,
        selectedGroup: String
    ) {
        rebuildJob?.cancel()
        withContext(Dispatchers.Default) {
            rebuildRowsNow(settings, catalogGroups, selectedGroup)
        }
    }

    private fun rebuildRowsNow(settings: AppSettings, catalogGroups: List<String>, selectedGroup: String) {
        val playlistId = settings.activeIptvPlaylist().id
        val favs = favorites.getFavorites(playlistId)
        val all = iptvRepository.catalog.value.channels
        val baseGroups = buildList {
            add(IptvDefaults.FAVORITES_GROUP)
            add(IptvDefaults.ALL_GROUP)
            addAll(catalogGroups)
        }
        // Apply the user's wheel order, then sink hidden groups to the bottom so they
        // stay reachable for "Show group" without cluttering everyday scrolling.
        val savedGroupOrder = channelCustom.groupOrder(playlistId)
        val orderedGroups = if (savedGroupOrder.isEmpty()) {
            baseGroups
        } else {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<String>(baseGroups.size)
            savedGroupOrder.forEach { key ->
                val match = baseGroups.firstOrNull { it.equals(key, ignoreCase = true) } ?: return@forEach
                if (seen.add(match)) out.add(match)
            }
            baseGroups.forEach { if (seen.add(it)) out.add(it) }
            out
        }
        val hiddenGroups = channelCustom.hiddenGroups(playlistId)
        val groups = orderedGroups.filterNot { it in hiddenGroups } +
            orderedGroups.filter { it in hiddenGroups }
        val filtered = when (selectedGroup) {
            IptvDefaults.FAVORITES_GROUP -> all.filter { it.id in favs }
            IptvDefaults.ALL_GROUP -> all
            else -> all.filter { it.group.equals(selectedGroup, ignoreCase = true) }
        }
        val orderMode = channelCustom.groupOrderMode(playlistId, selectedGroup)
        val ordered = when (orderMode) {
            GroupChannelOrder.CUSTOM ->
                channelCustom.ordered(playlistId, selectedGroup, filtered)
            GroupChannelOrder.ALPHABETICAL ->
                filtered.sortedBy {
                    channelCustom.displayName(playlistId, it.id, it.name).lowercase()
                }
            GroupChannelOrder.MOST_WATCHED ->
                filtered.sortedWith(
                    compareByDescending<IptvChannel> {
                        channelCustom.watchCount(playlistId, it.id)
                    }.thenBy {
                        channelCustom.displayName(playlistId, it.id, it.name).lowercase()
                    }
                )
        }
        val groupDisplayNames = groups.associateWith {
            channelCustom.groupDisplayName(playlistId, it)
        }
        val groupOrderModes = groups.associateWith {
            channelCustom.groupOrderMode(playlistId, it)
        }
        val groupChannelCounts = HashMap<String, Int>(catalogGroups.size + 4)
        groupChannelCounts[IptvDefaults.FAVORITES_GROUP] = all.count { it.id in favs }
        groupChannelCounts[IptvDefaults.ALL_GROUP] = all.size
        for (ch in all) {
            val g = ch.group
            groupChannelCounts[g] = (groupChannelCounts[g] ?: 0) + 1
        }
        // Ensure every catalog key has an entry even when empty.
        catalogGroups.forEach { g ->
            if (g !in groupChannelCounts) {
                val hit = groupChannelCounts.entries.firstOrNull { it.key.equals(g, true) }
                groupChannelCounts[g] = hit?.value ?: 0
            }
        }
        // Do NOT compute nowNext for every row here — ChannelWheel loads EPG for visible
        // rows only. Full-list nowNext on UK (1000+) channels ANR-killed Bedroom Shields.
        val emptyNowNext = IptvNowNext(null, null)
        val unlocked = _state.value.unlockedSession
        val rows = ordered.map { ch ->
            val display = channelCustom.displayName(playlistId, ch.id, ch.name)
            val displayCh = if (display == ch.name) ch else ch.copy(name = display)
            val confirmedBadges = channelCustom.streamBadges(playlistId, ch.id)
            IptvChannelRow(
                channel = displayCh,
                favorite = ch.id in favs,
                nowNext = emptyNowNext,
                locked = parental.isGroupLocked(ch.group) && !unlocked,
                badges = confirmedBadges.ifEmpty { ChannelQuality.labelsFor(displayCh.name) },
                badgesConfirmed = confirmedBadges.isNotEmpty()
            )
        }
        _state.update {
            it.copy(
                settings = settings,
                groups = groups,
                groupDisplayNames = groupDisplayNames,
                groupOrderModes = groupOrderModes,
                groupChannelCounts = groupChannelCounts,
                hiddenGroups = hiddenGroups,
                selectedGroup = selectedGroup,
                rowsForGroup = selectedGroup,
                rows = rows
            )
        }
    }

    private fun programmesForResolved(channel: IptvChannel, playlistId: String): List<IptvProgramme> {
        val override = channelCustom.epgId(playlistId, channel)
        if (!override.isNullOrBlank()) {
            return iptvRepository.programmesForTvgId(override)
        }
        return iptvRepository.programmesFor(channel)
    }

    private fun syncExtraWantedEpgIds(playlistId: String) {
        iptvRepository.setExtraWantedEpgIds(channelCustom.allEpgIds(playlistId))
    }

    /** Measured badges from the last watch of [channel], else hints inferred from its name. */
    fun badgesFor(
        channel: IptvChannel,
        playlistId: String = _state.value.settings.activeIptvPlaylist().id
    ): List<String> =
        channelCustom.streamBadges(playlistId, channel.id)
            .ifEmpty { ChannelQuality.labelsFor(channel.name) }

    fun badgesConfirmedFor(
        channel: IptvChannel,
        playlistId: String = _state.value.settings.activeIptvPlaylist().id
    ): Boolean = channelCustom.streamBadges(playlistId, channel.id).isNotEmpty()

    /** Persist decoder-measured badges for the playing channel and refresh visible rows. */
    fun reportStreamInfo(channelId: String, badges: List<String>) {
        if (badges.isEmpty()) return
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        if (channelCustom.streamBadges(playlistId, channelId) == badges) return
        channelCustom.setStreamBadges(playlistId, channelId, badges)
        _state.update { st ->
            st.copy(
                rows = st.rows.map {
                    if (it.channel.id == channelId) {
                        it.copy(badges = badges, badgesConfirmed = true)
                    } else {
                        it
                    }
                },
                searchChannelResults = st.searchChannelResults.map {
                    if (it.channel.id == channelId) {
                        it.copy(badges = badges, badgesConfirmed = true)
                    } else {
                        it
                    }
                }
            )
        }
    }
}

class IptvViewModelFactory(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val iptvRepository: IptvRepository,
    private val favorites: IptvFavoritesStore,
    private val channelCustom: IptvChannelCustomStore,
    private val parental: IptvParentalStore,
    private val recordings: IptvRecordingStore,
    private val watchHistory: IptvWatchHistoryStore,
    private val searchHistory: IptvSearchHistoryStore,
    private val nasWatchHistory: NasWatchHistoryStore,
    private val resumeStore: LocalResumeStore,
    private val resumeMonitor: ResumeMonitor,
    private val progressSync: NasProgressSync,
    private val nasRepository: NasRepository,
    private val playerLauncher: MediaPlayerLauncher
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return IptvViewModel(
            application,
            settingsRepository,
            iptvRepository,
            favorites,
            channelCustom,
            parental,
            recordings,
            watchHistory,
            searchHistory,
            nasWatchHistory,
            resumeStore,
            resumeMonitor,
            progressSync,
            nasRepository,
            playerLauncher
        ) as T
    }
}
