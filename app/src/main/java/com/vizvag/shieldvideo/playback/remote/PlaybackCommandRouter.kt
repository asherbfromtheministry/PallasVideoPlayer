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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps LAN remote commands onto local players (Music, VLC, Live TV, Radio, YouTube).
 */
class PlaybackCommandRouter(
    private val app: ShieldVideoApp,
) {
    private val vlcLauncher = VlcLauncher(app)

    fun status(): RemoteStatus = statusBody().copy(uiRoute = RemoteUiRouteStore.current())

    private fun statusBody(): RemoteStatus {
        val settings = app.settingsRepository.load()
        val deviceId = settings.deviceId.trim().lowercase().ifBlank {
            android.os.Build.MODEL.replace(Regex("[^a-zA-Z0-9]+"), "-")
                .trim('-')
                .lowercase()
                .ifBlank { "pallas" }
                .take(32)
        }
        // Prefer live VLC only while the session is actually active — a leftover path
        // must not hide an existing music queue from remotes.
        if (app.resumeMonitor.isPlayerActive()) {
            val pos = app.resumeMonitor.readPosition()
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.NasVideo,
                title = app.resumeMonitor.nowPlayingTitle().orEmpty()
                    .ifBlank { app.resumeMonitor.activeVideoPath()?.substringAfterLast('/').orEmpty() },
                isPlaying = app.resumeMonitor.isPlayerPlaying(),
                positionMs = pos?.first ?: 0L,
                durationMs = pos?.second ?: 0L,
            )
        }
        val musicUi = app.musicModule.playerController.uiState.value
        val queue = app.musicModule.queueManager.queue.value
        val musicTrack = musicUi.track
            ?: app.musicModule.queueManager.currentTrack
            ?: queue.getOrNull(app.musicModule.queueManager.currentIndex.value.coerceAtLeast(0))
        // Report Music whenever there is a queue/session — even if audio was paused/stopped.
        if (musicTrack != null || queue.isNotEmpty()) {
            val track = musicTrack ?: queue.first()
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Music,
                title = track.title,
                subtitle = track.artistName,
                isPlaying = musicUi.isPlaying,
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
            )
        }
        // Stale VLC path with no active session — still report as video for UI, no queue.
        if (app.resumeMonitor.activeVideoPath() != null) {
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
        val yt = app.youtubePlayback.state.value
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
            )
        }
        val radio = app.radioPlayback.state.value
        if (radio.stationId.isNotBlank()) {
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.Radio,
                title = radio.title.ifBlank { radio.stationName },
                subtitle = radio.stationName,
                isPlaying = radio.isPlaying,
            )
        }
        val iptv = app.iptvPlayback.state.value
        if (iptv.channelId.isNotBlank()) {
            return RemoteStatus(
                deviceId = deviceId,
                mode = RemotePlaybackMode.LiveTv,
                title = iptv.channelName,
                isPlaying = iptv.isPlaying,
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
                        RemotePlaybackMode.Idle -> Unit
                    }
                    TransportAction.Pause -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.pause()
                        RemotePlaybackMode.NasVideo -> app.resumeMonitor.pausePlayer()
                        RemotePlaybackMode.LiveTv -> app.iptvPlayback.pause()
                        RemotePlaybackMode.Radio -> app.radioPlayback.pause()
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.pause()
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
                        RemotePlaybackMode.Idle -> Unit
                    }
                    TransportAction.Stop -> stopAll()
                    TransportAction.Next -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.playNext()
                        else -> Unit
                    }
                    TransportAction.Previous -> when (mode) {
                        RemotePlaybackMode.Music -> app.musicModule.playerController.playPrevious()
                        else -> Unit
                    }
                    TransportAction.Seek -> when (mode) {
                        RemotePlaybackMode.Music ->
                            app.musicModule.playerController.seekTo(positionMs)
                        RemotePlaybackMode.NasVideo -> app.resumeMonitor.seekPlayer(positionMs)
                        RemotePlaybackMode.YouTube -> app.youtubePlayback.seekTo(positionMs)
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
        prepareInAppPlayer(RemotePlaybackMode.YouTube, "youtube")
        val info = app.youtubeRepository.streams(videoId)
        app.youtubePlayback.playStream(info)
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
        app.resumeMonitor.stopPlayer()
        LocalMediaProxyService.stop(app)
        bringAppToForeground()
    }

    private fun stopOthers(keep: RemotePlaybackMode) {
        if (keep != RemotePlaybackMode.Music) app.musicModule.playerController.stop()
        if (keep != RemotePlaybackMode.Radio) app.radioPlayback.stop()
        if (keep != RemotePlaybackMode.LiveTv) app.iptvPlayback.stop()
        if (keep != RemotePlaybackMode.YouTube) app.youtubePlayback.stop()
        if (keep != RemotePlaybackMode.NasVideo) {
            app.resumeMonitor.stopPlayer()
            LocalMediaProxyService.stop(app)
        }
    }

    private suspend fun resolveTracks(refs: List<MusicTrackRef>): List<TrackEntity> {
        val lib = app.musicModule.libraryRepository
        return refs.mapNotNull { ref ->
            when {
                ref.id.isNotBlank() -> lib.getTrack(ref.id)
                ref.nasPath.isNotBlank() -> {
                    lib.getTrackByPath(ref.nasPath)
                        ?: TrackEntity(
                            id = ref.nasPath,
                            albumId = "",
                            artistId = "",
                            title = ref.title.ifBlank { ref.nasPath.substringAfterLast('/') },
                            artistName = ref.artistName,
                            albumTitle = ref.albumTitle,
                            durationMs = ref.durationMs,
                            nasPath = ref.nasPath,
                        )
                }
                else -> null
            }
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
                else -> error("Unknown play type: $type")
            }.also {
                Log.d(TAG, "Parsed play request type=$type")
            }
        }
    }
}
