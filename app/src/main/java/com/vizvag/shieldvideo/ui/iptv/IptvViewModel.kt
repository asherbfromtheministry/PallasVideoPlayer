package com.vizvag.shieldvideo.ui.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vizvag.shieldvideo.data.iptv.CatchupUrlBuilder
import com.vizvag.shieldvideo.data.iptv.ChannelQuality
import com.vizvag.shieldvideo.data.iptv.EpgChannelEntry
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
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryEntry
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.PlayerLaunchResult
import com.vizvag.shieldvideo.playback.ResumeMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IptvChannelRow(
    val channel: IptvChannel,
    val favorite: Boolean,
    val nowNext: IptvNowNext,
    val locked: Boolean,
    /** Measured stream badges from the last watch, else name-derived hints. */
    val badges: List<String> = emptyList(),
    val badgesConfirmed: Boolean = false
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
    val selectedGroup: String = IptvDefaults.FAVORITES_GROUP,
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
    val assignEpgCurrentId: String? = null
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
            rebuildRows(settings, catalog.groups, selectedGroup = _state.value.selectedGroup)
            _state.update {
                it.copy(
                    settings = settings,
                    loading = false,
                    refreshing = true,
                    epgChannelCount = catalog.epgChannelCount,
                    recordings = recordings.list()
                )
            }
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

            // 1) Channels from memory / snapshot / disk — never blocks the Live TV UI.
            val channelsCatalog = iptvRepository.ensureChannelsLoaded(playlist, forceRefresh = force)
            // Heal EPG assignments keyed only by URL-hash channel ids (old builds / imported
            // backups) onto stable identity keys, then re-sync the wanted XMLTV ids.
            channelCustom.migrateEpgAssignments(playlist.id, channelsCatalog.channels)
            syncExtraWantedEpgIds(playlist.id)
            rebuildRows(settings, channelsCatalog.groups, selectedGroup = _state.value.selectedGroup)
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

            // 2) EPG from cache, then optional network upgrade — UI already interactive.
            val epgCatalog = iptvRepository.ensureEpgLoaded(playlist, forceRefresh = force)
            // Backfill manually assigned EPG ids if the in-memory parse predates them.
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

            // 3) Quietly upgrade stale caches from the network after UI is usable.
            if (!force) {
                if (channelsCatalog.channelsStale) {
                    val refreshed = iptvRepository.refreshChannelsIfStale(playlist)
                    if (refreshed.loadedAtMs != channelsCatalog.loadedAtMs) {
                        rebuildRows(settings, refreshed.groups, selectedGroup = _state.value.selectedGroup)
                    }
                }
                val epgRefreshed = iptvRepository.refreshEpgIfStale(playlist)
                if (epgRefreshed.epgLoadedAtMs != epgCatalog.epgLoadedAtMs) {
                    rebuildRows(settings, epgRefreshed.groups, selectedGroup = _state.value.selectedGroup)
                    _state.update {
                        it.copy(
                            epgChannelCount = epgRefreshed.epgChannelCount,
                            epgVersion = it.epgVersion + 1
                        )
                    }
                }
            }
            _state.update { it.copy(refreshing = false) }
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
        val rows = _state.value.rows
        val ids = rows.map { it.channel.id }.toMutableList()
        val index = ids.indexOf(channelId)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, ids.lastIndex)
        if (target == index) return
        ids.removeAt(index)
        ids.add(target, channelId)
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupOrderMode(
            playlistId,
            _state.value.selectedGroup,
            GroupChannelOrder.CUSTOM
        )
        channelCustom.setOrder(playlistId, _state.value.selectedGroup, ids)
        val catalogGroups = iptvRepository.catalog.value.groups
        rebuildRows(_state.value.settings, catalogGroups, _state.value.selectedGroup)
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
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        channelCustom.setGroupOrder(playlistId, keys)
        rebuildRows(_state.value.settings, iptvRepository.catalog.value.groups, _state.value.selectedGroup)
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
    }

    /**
     * OK on wheel: first time previews, second OK on same channel → fullscreen.
     */
    fun selectChannel(channel: IptvChannel) {
        if (parental.isGroupLocked(channel.group) && !_state.value.unlockedSession) {
            _state.update {
                it.copy(pinPrompt = true, pendingGroup = channel.group, message = "Enter PIN to watch")
            }
            return
        }
        val current = _state.value.previewChannel
        if (current?.id == channel.id) {
            captureFullscreenChannels()
            _state.update { it.copy(fullscreen = true, message = null) }
        } else {
            lastZapChannel = current
            recordWatched(channel)
            _state.update {
                it.copy(
                    previewChannel = channel,
                    fullscreen = false,
                    message = null
                )
            }
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
    }

    fun playHistoryChannel(channel: IptvChannel) {
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
            val uriResult = nasRepository.playbackUri(
                settings = settings,
                shareName = entry.share,
                relativePath = entry.path
            )
            val result = uriResult.fold(
                onSuccess = { uri ->
                    val playerPackage = settings.playerPackage.ifBlank {
                        MediaPlayerLauncher.VLC_PACKAGE
                    }
                    playerLauncher.play(
                        playbackUri = uri,
                        relativePath = entry.path,
                        title = entry.title,
                        playerPackage = playerPackage,
                        startPositionMs = startPositionMs
                    ) {
                        nasWatchHistory.record(entry.share, entry.path, entry.title)
                        val handoff = nasRepository.handoffUri(
                            settings,
                            entry.share,
                            entry.path
                        ).toString()
                        resumeMonitor.start(
                            path = entry.path,
                            playerPackage = playerPackage,
                            playbackUri = handoff,
                            title = entry.title,
                            share = entry.share,
                            host = settings.host
                        )
                    }
                },
                onFailure = { error ->
                    PlayerLaunchResult.Failed(error.message ?: "Unable to build playback URL")
                }
            )
            when (result) {
                PlayerLaunchResult.Success -> {
                    _state.update { it.copy(nasWatchHistory = nasWatchHistory.entries()) }
                }
                PlayerLaunchResult.NotInstalled -> _state.update {
                    it.copy(message = "Selected video player is not installed")
                }
                is PlayerLaunchResult.Failed -> _state.update { it.copy(message = result.message) }
            }
        }
    }

    fun openFullscreen() {
        val current = _state.value.previewChannel ?: return
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
        val settings = _state.value.settings
        if (parental.isGroupLocked(target.group) && !_state.value.unlockedSession) {
            _state.update { it.copy(pinPrompt = true, pendingGroup = target.group, message = "Enter PIN to watch") }
            return
        }
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
                assignEpgResults = iptvRepository.searchEpgChannels(""),
                assignEpgCurrentId = current
            )
        }
    }

    fun setAssignEpgQuery(query: String) {
        _state.update {
            it.copy(
                assignEpgQuery = query,
                assignEpgResults = iptvRepository.searchEpgChannels(query)
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

    fun openSearch() {
        val playlistId = _state.value.settings.activeIptvPlaylist().id
        _state.update {
            it.copy(
                searchOpen = true,
                searchQuery = "",
                searchHistory = searchHistory.queries(playlistId)
            )
        }
    }
    fun closeSearch() = _state.update {
        it.copy(searchOpen = false, searchQuery = "", searchChannelResults = emptyList(), searchProgrammeResults = emptyList())
    }

    fun setSearchQuery(query: String) {
        val settings = _state.value.settings
        val playlistId = settings.activeIptvPlaylist().id
        val favs = favorites.getFavorites(playlistId)
        val channels = iptvRepository.catalog.value.channels
        val channelHits = iptvRepository.searchChannels(query, channels).map { ch ->
            val display = channelCustom.displayName(playlistId, ch.id, ch.name)
            val confirmedBadges = channelCustom.streamBadges(playlistId, ch.id)
            IptvChannelRow(
                channel = if (display == ch.name) ch else ch.copy(name = display),
                favorite = ch.id in favs,
                nowNext = XmltvParser.nowNext(programmesForResolved(ch, playlistId)),
                locked = parental.isGroupLocked(ch.group) && !_state.value.unlockedSession,
                badges = confirmedBadges.ifEmpty { ChannelQuality.labelsFor(ch.name) },
                badgesConfirmed = confirmedBadges.isNotEmpty()
            )
        }
        val programmeHits = iptvRepository.searchProgrammes(query)
        _state.update {
            it.copy(
                searchQuery = query,
                searchChannelResults = channelHits,
                searchProgrammeResults = programmeHits
            )
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
        overlapping.forEach { r ->
            if (r.status == IptvRecordingStatus.RECORDING) {
                // In-flight: stop and keep what was captured rather than throwing it away.
                IptvRecordingService.finish(getApplication(), r.id)
            } else {
                IptvRecordingScheduler.cancel(getApplication(), r.id)
                recordings.remove(r.id)
            }
        }
        _state.update {
            it.copy(
                recordings = recordings.list(),
                message = "Recording cancelled: ${programme.title}"
            )
        }
    }

    /** Manual stop: keeps everything captured so far and saves it to the recording folder. */
    fun stopRecording(id: String) {
        IptvRecordingService.finish(getApplication(), id)
        _state.update {
            it.copy(
                recordings = recordings.list(),
                message = "Stopping recording — saving what was captured"
            )
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
        rebuildRows(settings, catalogGroups, group)
    }

    private fun rebuildRows(settings: AppSettings, catalogGroups: List<String>, selectedGroup: String) {
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
        val rows = ordered.map { ch ->
            val display = channelCustom.displayName(playlistId, ch.id, ch.name)
            val displayCh = if (display == ch.name) ch else ch.copy(name = display)
            val confirmedBadges = channelCustom.streamBadges(playlistId, ch.id)
            IptvChannelRow(
                channel = displayCh,
                favorite = ch.id in favs,
                nowNext = if (settings.iptvShowEpgInList) {
                    XmltvParser.nowNext(programmesForResolved(ch, playlistId))
                } else {
                    IptvNowNext(null, null)
                },
                locked = parental.isGroupLocked(ch.group) && !_state.value.unlockedSession,
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
                hiddenGroups = hiddenGroups,
                selectedGroup = selectedGroup,
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
