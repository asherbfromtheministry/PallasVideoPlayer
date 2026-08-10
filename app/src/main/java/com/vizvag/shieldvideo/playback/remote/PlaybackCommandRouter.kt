package com.vizvag.shieldvideo.playback.remote

import android.content.Intent
import android.util.Log
import com.vizvag.shieldvideo.MainActivity
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.radio.RadioDefaults
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.playback.LocalMediaProxyService
import com.vizvag.shieldvideo.playback.MediaPlayerLauncher
import com.vizvag.shieldvideo.playback.PlayerLaunchResult
import com.vizvag.shieldvideo.playback.VlcLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps LAN remote commands onto local players (Music, VLC, Live TV, Radio, YouTube, Podcasts).
 */
class PlaybackCommandRouter(
    private val app: ShieldVideoApp,
) {
    private val vlcLauncher = VlcLauncher(app)

    fun status(): RemoteStatus = statusBody().copy(uiRoute = RemoteUiRouteStore.current())

    /** ExoPlayer reads must run on the main thread — HTTP handlers call this. */
    suspend fun statusJson(): String = withContext(Dispatchers.Main) {
        status().toJson().toString()
    }

    private fun statusBody(): RemoteStatus {
        val settings = app.settingsRepository.load()
        val deviceId = settings.deviceId.trim().lowercase().ifBlank {
            android.os.Build.MODEL.replace(Regex("[^a-zA-Z0-9]+"), "-")
                .trim('-')
                .lowercase()
                .ifBlank { "pallas" }
                .take(32)
        }
        val musicUi = app.musicModule.playerController.uiState.value
        val queue = app.musicModule.queueManager.queue.value
        val musicTrack = musicUi.track
            ?: app.musicModule.queueManager.currentTrack
            ?: queue.getOrNull(app.musicModule.queueManager.currentIndex.value.coerceAtLeast(0))
        val podcast = app.podcastPlayback.state.value
        val yt = app.youtubePlayback.state.value
        val radio = app.radioPlayback.state.value
        val iptv = app.iptvPlayback.state.value
        val vlcActive = app.resumeMonitor.isPlayerActive()
        val vlcPlaying = vlcActive && app.resumeMonitor.isPlayerPlaying()

        fun musicStatus(playing: Boolean = musicUi.isPlaying): RemoteStatus {
            val track = musicTrack ?: queue.firstOrNull()
                ?: return RemoteStatus(deviceId = deviceId, mode = RemotePlaybackMode.Idle)
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Music,
                title = track.title,
                subtitle = track.artistName,
                isPlaying = playing,
                positionMs = musicUi.positionMs,
                durationMs = musicUi.durationMs.takeIf { it > 0 } ?: track.durationMs,
                queue = queue.map {
                    RemoteQueueItem(
                        id = it.id,
                        title = it.title,
                        artist = it.artistName,
                        nasPath = it.nasPath,
                        durationMs = it.durationMs,
                    )
                },
                queueIndex = app.musicModule.queueManager.currentIndex.value
                    .takeIf { it in queue.indices }
                    ?: queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
                    ?: 0,
                contentId = track.id,
            )
        }

        // 1) Whatever is actually audible wins (never hide live radio behind a leftover music queue).
        when {
            vlcPlaying -> {
                val pos = app.resumeMonitor.readPosition()
                return RemoteStatus(
                    deviceId = deviceId,
                    mode = RemotePlaybackMode.NasVideo,
                    title = app.resumeMonitor.nowPlayingTitle().orEmpty()
                        .ifBlank { app.resumeMonitor.activeVideoPath()?.substringAfterLast('/').orEmpty() },
                    isPlaying = true,
                    positionMs = pos?.first ?: 0L,
                    durationMs = pos?.second ?: 0L,
                )
            }
            musicUi.isPlaying -> return musicStatus(playing = true)
            radio.isPlaying && radio.stationId.isNotBlank() -> return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Radio,
                title = radio.title.ifBlank { radio.stationName },
                subtitle = radio.stationName,
                isPlaying = true,
                contentId = radio.stationId,
            )
            podcast.isPlaying && podcast.episodeGuid.isNotBlank() -> {
                app.podcastPlayback.syncPosition()
                val refreshed = app.podcastPlayback.state.value
                return RemoteStatus(
                    deviceId = deviceId,
                    mode = RemotePlaybackMode.Podcasts,
                    title = refreshed.episodeTitle,
                    subtitle = refreshed.showTitle,
                    isPlaying = true,
                    positionMs = refreshed.positionMs,
                    durationMs = refreshed.durationMs,
                    artworkUrl = refreshed.imageUrl,
                    contentId = refreshed.episodeGuid,
                )
            }
            yt.isPlaying && yt.videoId.isNotBlank() -> {
                app.youtubePlayback.refreshPosition()
                val refreshed = app.youtubePlayback.state.value
                return RemoteStatus(
                    deviceId = deviceId,
                    mode = RemotePlaybackMode.YouTube,
                    title = refreshed.title,
                    subtitle = refreshed.uploader,
                    isPlaying = true,
                    positionMs = refreshed.positionMs,
                    durationMs = refreshed.durationMs,
                    contentId = refreshed.videoId,
                )
            }
            iptv.isPlaying && iptv.channelId.isNotBlank() -> return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.LiveTv,
                title = iptv.channelName,
                isPlaying = true,
                contentId = iptv.channelId,
            )
        }

        // 2) Paused / tuned sessions — radio & friends before a dormant music queue.
        if (radio.stationId.isNotBlank()) {
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Radio,
                title = radio.title.ifBlank { radio.stationName },
                subtitle = radio.stationName,
                isPlaying = false,
                contentId = radio.stationId,
            )
        }
        if (podcast.episodeGuid.isNotBlank()) {
            app.podcastPlayback.syncPosition()
            val refreshed = app.podcastPlayback.state.value
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Podcasts,
                title = refreshed.episodeTitle,
                subtitle = refreshed.showTitle,
                isPlaying = refreshed.isPlaying,
                positionMs = refreshed.positionMs,
                durationMs = refreshed.durationMs,
                artworkUrl = refreshed.imageUrl,
                contentId = refreshed.episodeGuid,
            )
        }
        if (yt.videoId.isNotBlank()) {
            app.youtubePlayback.refreshPosition()
            val refreshed = app.youtubePlayback.state.value
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.YouTube,
                title = refreshed.title,
                subtitle = refreshed.uploader,
                isPlaying = refreshed.isPlaying,
                positionMs = refreshed.positionMs,
                durationMs = refreshed.durationMs,
                contentId = refreshed.videoId,
            )
        }
        if (iptv.channelId.isNotBlank()) {
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.LiveTv,
                title = iptv.channelName,
                isPlaying = iptv.isPlaying,
                contentId = iptv.channelId,
            )
        }
        if (musicTrack != null || queue.isNotEmpty()) {
            return musicStatus(playing = false)
        }
        if (vlcActive || app.resumeMonitor.activeVideoPath() != null) {
            val pos = app.resumeMonitor.readPosition()
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.NasVideo,
                title = app.resumeMonitor.nowPlayingTitle().orEmpty()
                    .ifBlank { app.resumeMonitor.activeVideoPath()?.substringAfterLast('/').orEmpty() },
                isPlaying = false,
                positionMs = pos?.first ?: 0L,
                durationMs = pos?.second ?: 0L,
            )
        }
        return RemoteStatus(deviceId = deviceId, mode = RemotePlaybackMode.Idle)
    }

    suspend fun transport(action: TransportAction, positionMs: Long = 0): Result<Unit> =
        runCatching {
            withContext(Dispatchers.Main) {
                val mode = status().mode
                when (action) {
                    TransportAction.Play -> when (mode) {
                        RemotePlaybackMode.Music -> {
                            val pc = app.musicModule.playerController
                            if (!pc.uiState.value.isPlaying) pc.togglePlayPause()
                        }
                        RemotePlaybackMode.NasVideo -> app.resumeMonitor.resumePlayer()
                        RemotePlaybackMode.LiveTv -> app.iptvPlayback.play()
                        RemotePlaybackMode.Radio -> app.radioPlayback.play()
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.play()
                        RemotePlaybackMode.Podcasts -> app.podcastPlayback.play()
                        RemotePlaybackMode.Idle -> Unit
                    }
                    TransportAction.Pause -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.pause()
                        RemotePlaybackMode.NasVideo -> app.resumeMonitor.pausePlayer()
                        RemotePlaybackMode.LiveTv -> app.iptvPlayback.pause()
                        RemotePlaybackMode.Radio -> app.radioPlayback.pause()
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.pause()
                        RemotePlaybackMode.Podcasts -> app.podcastPlayback.pause()
                        RemotePlaybackMode.Idle -> Unit
                    }
                    TransportAction.Toggle -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.togglePlayPause()
                        RemotePlaybackMode.NasVideo -> {
                            if (app.resumeMonitor.isPlayerPlaying()) app.resumeMonitor.pausePlayer()
                            else app.resumeMonitor.resumePlayer()
                        }
                        RemotePlaybackMode.LiveTv -> app.iptvPlayback.toggle()
                        RemotePlaybackMode.Radio -> app.radioPlayback.toggle()
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.toggle()
                        RemotePlaybackMode.Podcasts -> app.podcastPlayback.toggle()
                        RemotePlaybackMode.Idle -> Unit
                    }
                    TransportAction.Stop -> stopAll()
                    TransportAction.Next -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.playNext()
                        RemotePlaybackMode.Podcasts -> skipPodcast(newer = false)
                        else -> Unit
                    }
                    TransportAction.Previous -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.playPrevious()
                        RemotePlaybackMode.Podcasts -> skipPodcast(newer = true)
                        else -> Unit
                    }
                    TransportAction.Seek -> when (mode) {
                        RemotePlaybackMode.Music ->
                            app.musicModule.playerController.seekTo(positionMs)
                        RemotePlaybackMode.NasVideo -> app.resumeMonitor.seekPlayer(positionMs)
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.seekTo(positionMs)
                        RemotePlaybackMode.Podcasts -> app.podcastPlayback.seekTo(positionMs)
                        else -> Unit
                    }
                }
            }
        }

    suspend fun play(request: RemotePlayRequest): Result<Unit> = runCatching {
        when (request) {
            is RemotePlayRequest.Music -> playMusic(request)
            is RemotePlayRequest.NasVideo -> playNasVideo(request)
            is RemotePlayRequest.LiveTv -> playLiveTv(request.channelId)
            is RemotePlayRequest.Radio -> playRadio(request.stationId)
            is RemotePlayRequest.YouTube -> playYouTube(request.videoId)
            is RemotePlayRequest.Podcast -> playPodcast(request)
        }
    }

    /** Phone/tablet asks the TV to open the same player screen (does not stop current audio). */
    suspend fun navigate(route: String): Result<Unit> = runCatching {
        val trimmed = route.trim()
        if (!RemoteUiRouteStore.isMirrorable(trimmed)) {
            error("Route not mirrorable: $trimmed")
        }
        withContext(Dispatchers.Main) {
            bringAppToForeground(trimmed)
            RemoteUiRouteStore.set(trimmed)
            RemoteNavRequests.requestRoute(trimmed)
        }
        delay(500)
        RemoteNavRequests.requestRoute(trimmed)
    }

    fun podcastSubscriptionsJson(): JSONArray = app.podcastRepository.exportSubscriptionsJson()

    /** Radio station catalog for LAN remotes / HA (`GET /v1/radio/stations`). */
    fun radioStationsJson(): JSONArray {
        val settings = app.settingsRepository.load()
        val stations = settings.customRadioStations.ifEmpty { RadioDefaults.stations() }
        val arr = JSONArray()
        stations.forEach { station ->
            arr.put(
                JSONObject()
                    .put("id", station.id)
                    .put("name", station.name)
                    .put("tagline", station.tagline)
                    .put("bbcServiceId", station.bbcServiceId),
            )
        }
        return arr
    }

    /**
     * Play a radio station by id, or by display name (exact then contains, case-insensitive).
     * Used by LAN remotes and `pallas://radio` deep links.
     */
    suspend fun playRadioStation(stationId: String = "", stationName: String = ""): Result<Unit> =
        runCatching {
            val settings = app.settingsRepository.load()
            val stations = settings.customRadioStations.ifEmpty { RadioDefaults.stations() }
            val id = stationId.trim()
            val name = stationName.trim()
            val station = when {
                id.isNotBlank() -> stations.firstOrNull { it.id.equals(id, ignoreCase = true) }
                    ?: error("Station not found: $id")
                name.isNotBlank() -> {
                    stations.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: stations.firstOrNull {
                            it.name.contains(name, ignoreCase = true) ||
                                name.contains(it.name, ignoreCase = true)
                        }
                        ?: error("Station not found: $name")
                }
                else -> error("Missing stationId or name")
            }
            playRadio(station.id)
        }

    /**
     * Play a podcast episode by guid (+ optional showId), HA catalog label (`Show · Episode`),
     * or latest episode of a show name (`pallas://podcast?show=`).
     */
    suspend fun playPodcastEpisode(
        episodeGuid: String = "",
        showId: String = "",
        label: String = "",
        showName: String = "",
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val guid = episodeGuid.trim()
            val sid = showId.trim()
            val lbl = label.trim()
            val showSpoken = showName.trim()
            val matched = when {
                guid.isNotBlank() -> app.podcastRepository.findCachedEpisodeByGuid(guid, sid)
                    ?: error("Episode not found: $guid")
                lbl.isNotBlank() -> app.podcastRepository.findCachedEpisodeByLabel(lbl)
                    ?: error("Episode not found: $lbl")
                showSpoken.isNotBlank() -> {
                    val show = app.podcastRepository.findShowBySpokenName(showSpoken)
                        ?: error("Podcast show not found: $showSpoken")
                    // Prefer a fresh feed so "latest" means latest.
                    runCatching {
                        app.podcastRepository.episodesForShow(show, forceRefresh = true)
                    }
                    app.podcastRepository.findLatestEpisodeByShowName(show.title)
                        ?: error("No episodes for: ${show.title}")
                }
                else -> error("Missing guid, label, or show")
            }
            val (show, episode) = matched
            if (episode.audioUrl.isBlank()) error("Episode has no audio URL")
            playPodcast(
                RemotePlayRequest.Podcast(
                    showId = show.id,
                    episodeGuid = episode.guid,
                    audioUrl = episode.audioUrl,
                    episodeTitle = episode.title,
                    showTitle = show.title,
                    imageUrl = episode.imageUrl.ifBlank { show.imageUrl },
                    durationSec = episode.durationSec,
                ),
            )
        }
    }

    /** Relative seek while a podcast is loaded (`pallas://podcast?skip=`). */
    suspend fun seekPodcastBy(deltaMs: Long): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) {
            if (!app.podcastPlayback.isActive()) {
                error("No podcast playing")
            }
            app.podcastPlayback.seekBy(deltaMs)
            app.publishPodcastNowPlayingToHa()
        }
    }

    /** Force-refresh all RSS feeds on this device (used by remote Refresh). */
    suspend fun refreshPodcastFeeds(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            var shows = app.podcastRepository.subscriptions.value
            if (shows.isEmpty()) {
                // Cold device / wiped prefs — re-pull OPML from the saved NAS path (or Downloads).
                app.podcastRepository.importOpmlPreferNas().getOrElse { throw it }
                shows = app.podcastRepository.subscriptions.value
            }
            shows.forEach { show ->
                runCatching { app.podcastRepository.episodesForShow(show, forceRefresh = true) }
            }
            shows.size
        }
    }.also { result ->
        if (result.isSuccess) {
            runCatching { app.publishPodcastEpisodesToHa() }
        }
    }

    suspend fun podcastEpisodesJson(showId: String?): JSONArray = withContext(Dispatchers.IO) {
        val shows = app.podcastRepository.subscriptions.value
        val episodes = if (!showId.isNullOrBlank()) {
            val show = shows.firstOrNull { it.id == showId }
                ?: return@withContext JSONArray()
            // Prefer cache for remotes; fall back to a normal load if never cached.
            app.podcastRepository.readCachedEpisodes(show).ifEmpty {
                runCatching {
                    app.podcastRepository.episodesForShow(show, forceRefresh = false)
                }.getOrDefault(emptyList())
            }
        } else {
            val cached = coroutineScope {
                shows.map { show ->
                    async { app.podcastRepository.readCachedEpisodes(show) }
                }.awaitAll().flatten()
            }
            if (cached.isNotEmpty()) {
                cached.sortedByDescending { it.publishEpochMs }.take(250)
            } else {
                // First open after import: caches are cold — fetch so remotes aren't stuck empty.
                coroutineScope {
                    shows.map { show ->
                        async {
                            runCatching {
                                app.podcastRepository.episodesForShow(show, forceRefresh = false)
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }.sortedByDescending { it.publishEpochMs }.take(250)
            }
        }
        val progress = app.podcastRepository.progress.value
        JSONArray().also { arr ->
            episodes.forEach { ep ->
                val p = progress[ep.guid]
                arr.put(
                    JSONObject()
                        .put("guid", ep.guid)
                        .put("showId", ep.showId)
                        .put("title", ep.title)
                        // Omit description — HTML blobs make the LAN payload huge / slow.
                        .put("audioUrl", ep.audioUrl)
                        .put("publishEpochMs", ep.publishEpochMs)
                        .put("durationSec", ep.durationSec)
                        .put("imageUrl", ep.imageUrl)
                        .put("positionMs", p?.positionMs ?: 0L)
                        .put("durationMs", p?.durationMs ?: 0L)
                        .put("completed", p?.completed == true),
                )
            }
        }
    }

    suspend fun musicQueue(
        action: MusicQueueAction,
        tracks: List<MusicTrackRef> = emptyList(),
        index: Int = -1,
        from: Int = -1,
        to: Int = -1,
    ): Result<Unit> = runCatching {
        val qm = app.musicModule.queueManager
        val pc = app.musicModule.playerController
        when (action) {
            MusicQueueAction.Add -> {
                val entities = resolveTracks(tracks)
                if (entities.isEmpty()) error("No tracks to add")
                val (wasEmpty, insertAt, shouldStart) = withContext(Dispatchers.Main) {
                    val empty = qm.queue.value.isEmpty()
                    val at = qm.queue.value.size
                    val playing = pc.uiState.value.isPlaying || pc.uiState.value.track != null
                    qm.addAllToEnd(entities)
                    Triple(empty, at, !playing)
                }
                qm.persist()
                if (shouldStart) {
                    prepareInAppPlayer(RemotePlaybackMode.Music, "music")
                    withContext(Dispatchers.Main) {
                        pc.playQueueIndex(if (wasEmpty) 0 else insertAt)
                    }
                    RemoteNavRequests.requestRoute("music")
                }
            }
            MusicQueueAction.Remove -> {
                if (index < 0) error("index required")
                qm.removeAt(index)
                qm.persist()
            }
            MusicQueueAction.PlayIndex -> {
                if (index < 0) error("index required")
                prepareInAppPlayer(RemotePlaybackMode.Music, "music")
                withContext(Dispatchers.Main) {
                    pc.playQueueIndex(index)
                }
            }
            MusicQueueAction.Clear -> {
                withContext(Dispatchers.Main) { pc.stop() }
                qm.setQueue(emptyList(), 0)
                qm.persist()
            }
            MusicQueueAction.Move -> {
                qm.move(from, to)
                qm.persist()
            }
        }
        if (action == MusicQueueAction.PlayIndex || action == MusicQueueAction.Clear) {
            RemoteNavRequests.requestRoute("music")
        }
    }

    private suspend fun playMusic(request: RemotePlayRequest.Music) {
        prepareInAppPlayer(RemotePlaybackMode.Music, "music")
        val entities = resolveTracks(request.tracks)
        if (entities.isEmpty()) error("No playable music tracks")
        withContext(Dispatchers.Main) {
            app.musicModule.playerController.playTracks(
                entities,
                request.startIndex.coerceIn(0, entities.lastIndex),
            )
        }
        // Audio can start before compose resumes — keep Music UI request sticky.
        RemoteNavRequests.requestRoute("music")
    }

    private suspend fun playNasVideo(request: RemotePlayRequest.NasVideo) = withContext(Dispatchers.Main) {
        stopOthers(keep = RemotePlaybackMode.NasVideo)
        val settings = app.settingsRepository.load()
        val playSettings = if (!request.host.isNullOrBlank()) {
            settings.copy(host = request.host)
        } else {
            settings
        }
        val title = request.title.ifBlank { request.path.substringAfterLast('/') }
        val mediaUri = LocalMediaProxyService.startAndAwait(
            context = app,
            share = request.share,
            path = request.path,
            host = playSettings.host,
            title = title,
        )
        val playerPkg = playSettings.playerPackage.ifBlank { MediaPlayerLauncher.VLC_PACKAGE }
        val result = vlcLauncher.play(
            playbackUri = mediaUri,
            relativePath = request.path,
            title = title,
            playerPackage = playerPkg,
            startPositionMs = request.positionMs,
        )
        when (result) {
            is PlayerLaunchResult.Success -> {
                app.nasWatchHistory.record(request.share, request.path, title)
                val handoff = app.nasRepository.handoffUri(playSettings, request.share, request.path)
                app.resumeMonitor.start(
                    path = request.path,
                    playerPackage = playerPkg,
                    playbackUri = handoff.toString(),
                    title = title,
                    share = request.share,
                    host = playSettings.host,
                )
            }
            is PlayerLaunchResult.NotInstalled -> error("VLC is not installed")
            is PlayerLaunchResult.Failed -> error(result.message)
        }
    }

    private suspend fun playLiveTv(channelId: String) {
        prepareInAppPlayer(RemotePlaybackMode.LiveTv, "iptv")
        val playlist = app.settingsRepository.load().activeIptvPlaylist()
        app.iptvRepository.ensureChannelsLoaded(playlist, forceRefresh = false)
        val channel = app.iptvRepository.catalog.value.channels.firstOrNull { it.id == channelId }
            ?: error("Channel not found: $channelId")
        app.iptvPlayback.playChannel(channel.id, channel.name, channel.streamUrl)
    }

    private suspend fun playRadio(stationId: String) {
        prepareInAppPlayer(RemotePlaybackMode.Radio, "radio")
        val settings = app.settingsRepository.load()
        val stations = settings.customRadioStations.ifEmpty { RadioDefaults.stations() }
        val station = stations.firstOrNull { it.id == stationId }
            ?: error("Station not found: $stationId")
        app.radioPlayback.playStation(station.id, station.name, station.streamUrl)
    }

    private suspend fun playYouTube(videoId: String) {
        check(com.vizvag.shieldvideo.FeatureFlags.youtube) {
            "YouTube is disabled in this build"
        }
        prepareInAppPlayer(RemotePlaybackMode.YouTube, "youtube")
        val info = app.youtubeRepository.streams(videoId)
        withContext(Dispatchers.Main) {
            app.youtubePlayback.playStream(info)
        }
    }

    private suspend fun playPodcast(request: RemotePlayRequest.Podcast) {
        prepareInAppPlayer(RemotePlaybackMode.Podcasts, "podcasts")
        val show = app.podcastRepository.subscriptions.value
            .firstOrNull { it.id == request.showId }
            ?: com.vizvag.shieldvideo.data.podcast.PodcastShow(
                id = request.showId.ifBlank { "remote" },
                title = request.showTitle.ifBlank { "Podcast" },
                feedUrl = "",
                imageUrl = request.imageUrl,
            )
        val fromFeed = if (request.showId.isNotBlank()) {
            runCatching {
                app.podcastRepository.episodesForShow(show, forceRefresh = false)
                    .firstOrNull { it.guid == request.episodeGuid }
            }.getOrNull()
        } else {
            null
        }
        val episode = fromFeed ?: com.vizvag.shieldvideo.data.podcast.PodcastEpisode(
            guid = request.episodeGuid.ifBlank { request.audioUrl },
            showId = show.id,
            title = request.episodeTitle.ifBlank { "Episode" },
            audioUrl = request.audioUrl,
            durationSec = request.durationSec,
            imageUrl = request.imageUrl,
        )
        if (episode.audioUrl.isBlank()) error("Episode has no audio URL")
        val start = request.positionMs.takeIf { it > 5_000L }
            ?: app.podcastRepository.progressFor(episode.guid)
                ?.takeIf { !it.completed && it.positionMs > 5_000L }
                ?.positionMs
            ?: 0L
        withContext(Dispatchers.Main) {
            app.podcastPlayback.playEpisode(show, episode, startPositionMs = start)
        }
    }

    /** newer=true → previous list item (more recent); newer=false → older episode. */
    private suspend fun skipPodcast(newer: Boolean) {
        val current = app.podcastPlayback.state.value
        if (current.episodeGuid.isBlank()) return
        val shows = app.podcastRepository.subscriptions.value
        val merged = ArrayList<com.vizvag.shieldvideo.data.podcast.PodcastEpisode>()
        shows.forEach { show ->
            runCatching {
                merged.addAll(app.podcastRepository.episodesForShow(show, forceRefresh = false))
            }
        }
        val sorted = merged.sortedByDescending { it.publishEpochMs }
        val idx = sorted.indexOfFirst { it.guid == current.episodeGuid }
        if (idx < 0) return
        val nextIdx = if (newer) idx - 1 else idx + 1
        if (nextIdx !in sorted.indices) return
        val episode = sorted[nextIdx]
        val show = shows.firstOrNull { it.id == episode.showId } ?: return
        withContext(Dispatchers.Main) {
            app.podcastPlayback.playEpisode(show, episode, startPositionMs = 0L)
        }
    }

    /**
     * Bring Pallas over VLC (or whatever is fullscreen), stop other players, then show [route].
     * MediaSession stop alone leaves VLC on screen while music already plays underneath.
     */
    private suspend fun prepareInAppPlayer(keep: RemotePlaybackMode, route: String) {
        withContext(Dispatchers.Main) {
            stopOthers(keep = keep)
            bringAppToForeground(route)
            // Sticky until MainActivity consumes while RESUMED.
            RemoteNavRequests.requestRoute(route)
        }
        // Let moveToFront / startActivity settle, then re-assert in case the first
        // emit raced a paused composition that never consumed.
        delay(500)
        RemoteNavRequests.requestRoute(route)
    }

    private fun bringAppToForeground(route: String? = null) {
        val moved = runCatching {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val task = am?.appTasks?.firstOrNull()
            if (task != null) {
                task.moveToFront()
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (moved) return
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            if (!route.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_REMOTE_ROUTE, route)
            }
        }
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "bringAppToForeground failed: ${it.message}") }
    }

    private fun stopAll() {
        app.musicModule.playerController.stop()
        app.radioPlayback.stop()
        app.iptvPlayback.stop()
        app.youtubePlayback.stop()
        app.podcastPlayback.stop()
        app.resumeMonitor.stopPlayer()
        LocalMediaProxyService.stop(app)
        bringAppToForeground()
    }

    private fun stopOthers(keep: RemotePlaybackMode) {
        if (keep != RemotePlaybackMode.Music) app.musicModule.playerController.stop()
        if (keep != RemotePlaybackMode.Radio) app.radioPlayback.stop()
        if (keep != RemotePlaybackMode.LiveTv) app.iptvPlayback.stop()
        if (keep != RemotePlaybackMode.YouTube) app.youtubePlayback.stop()
        if (keep != RemotePlaybackMode.Podcasts) app.podcastPlayback.stop()
        if (keep != RemotePlaybackMode.NasVideo) {
            app.resumeMonitor.stopPlayer()
            LocalMediaProxyService.stop(app)
        }
    }

    private suspend fun resolveTracks(refs: List<MusicTrackRef>): List<TrackEntity> {
        val lib = app.musicModule.libraryRepository
        return refs.mapNotNull { ref ->
            val byId = ref.id.takeIf { it.isNotBlank() }?.let { lib.getTrack(it) }
            if (byId != null) return@mapNotNull byId
            val path = ref.nasPath.trim()
            if (path.isBlank()) return@mapNotNull null
            lib.getTrackByPath(path) ?: TrackEntity(
                id = ref.id.ifBlank { path },
                albumId = "",
                artistId = "",
                title = ref.title.ifBlank { path.substringAfterLast('/') },
                artistName = ref.artistName,
                albumTitle = ref.albumTitle,
                durationMs = ref.durationMs,
                nasPath = path,
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackCmdRouter"

        fun parsePlayBody(body: JSONObject): RemotePlayRequest {
            val type = body.optString("type").lowercase()
            return when (type) {
                "music" -> {
                    val tracksArr = body.optJSONArray("tracks") ?: JSONArray()
                    val tracks = buildList {
                        for (i in 0 until tracksArr.length()) {
                            val t = tracksArr.optJSONObject(i) ?: continue
                            add(
                                MusicTrackRef(
                                    id = t.optString("id"),
                                    nasPath = t.optString("nasPath"),
                                    title = t.optString("title"),
                                    artistName = t.optString("artistName"),
                                    albumTitle = t.optString("albumTitle"),
                                    durationMs = t.optLong("durationMs"),
                                ),
                            )
                        }
                    }
                    RemotePlayRequest.Music(tracks, body.optInt("startIndex", 0))
                }
                "nas", "video", "nasvideo" -> RemotePlayRequest.NasVideo(
                    share = body.optString("share"),
                    path = body.optString("path"),
                    title = body.optString("title"),
                    positionMs = body.optLong("positionMs").takeIf { it > 0 },
                    host = body.optString("host").takeIf { it.isNotBlank() },
                )
                "iptv", "livetv" -> RemotePlayRequest.LiveTv(body.optString("channelId"))
                "radio" -> RemotePlayRequest.Radio(body.optString("stationId"))
                "youtube", "yt" -> RemotePlayRequest.YouTube(body.optString("videoId"))
                "podcast", "podcasts" -> RemotePlayRequest.Podcast(
                    showId = body.optString("showId"),
                    episodeGuid = body.optString("episodeGuid"),
                    audioUrl = body.optString("audioUrl"),
                    episodeTitle = body.optString("episodeTitle"),
                    showTitle = body.optString("showTitle"),
                    imageUrl = body.optString("imageUrl"),
                    durationSec = body.optLong("durationSec"),
                    positionMs = body.optLong("positionMs"),
                )
                else -> error("Unknown play type: $type")
            }.also {
                Log.d(TAG, "Parsed play request type=$type")
            }
        }
    }
}
