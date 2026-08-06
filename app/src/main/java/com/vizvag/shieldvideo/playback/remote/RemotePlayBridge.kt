package com.vizvag.shieldvideo.playback.remote

import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.music.data.local.TrackEntity

/**
 * When a remote target is selected, send play commands there; otherwise run [local].
 */
object RemotePlayBridge {
    suspend fun playMusicTracks(
        tracks: List<TrackEntity>,
        startIndex: Int = 0,
        local: suspend () -> Unit,
    ) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val refs = tracks.map {
            MusicTrackRef(
                id = it.id,
                nasPath = it.nasPath,
                title = it.title,
                artistName = it.artistName,
                albumTitle = it.albumTitle,
                durationMs = it.durationMs,
            )
        }
        val status = ShieldVideoApp.instance.remoteClient.playMusic(device, refs, startIndex).getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun enqueueMusic(
        tracks: List<TrackEntity>,
        local: suspend () -> Unit,
    ) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val refs = tracks.map {
            MusicTrackRef(
                id = it.id,
                nasPath = it.nasPath,
                title = it.title,
                artistName = it.artistName,
                albumTitle = it.albumTitle,
                durationMs = it.durationMs,
            )
        }
        val status = ShieldVideoApp.instance.remoteClient
            .musicQueue(device, MusicQueueAction.Add, tracks = refs)
            .getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun playNasVideo(
        share: String,
        path: String,
        title: String,
        positionMs: Long?,
        host: String?,
        local: suspend () -> Unit,
    ) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val status = ShieldVideoApp.instance.remoteClient
            .playNasVideo(device, share, path, title, positionMs, host)
            .getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun playLiveTv(channelId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val status = ShieldVideoApp.instance.remoteClient.playLiveTv(device, channelId).getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun playRadio(stationId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val status = ShieldVideoApp.instance.remoteClient.playRadio(device, stationId).getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun playYouTube(videoId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val status = ShieldVideoApp.instance.remoteClient.playYouTube(device, videoId).getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    suspend fun playPodcast(
        showId: String,
        episodeGuid: String,
        audioUrl: String = "",
        episodeTitle: String = "",
        showTitle: String = "",
        imageUrl: String = "",
        durationSec: Long = 0L,
        positionMs: Long = 0L,
        local: suspend () -> Unit,
    ) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        val status = ShieldVideoApp.instance.remoteClient.playPodcast(
            device = device,
            showId = showId,
            episodeGuid = episodeGuid,
            audioUrl = audioUrl,
            episodeTitle = episodeTitle,
            showTitle = showTitle,
            imageUrl = imageUrl,
            durationSec = durationSec,
            positionMs = positionMs,
        ).getOrThrow()
        RemoteStatusPoller.publish(status)
        RemoteStatusPoller.kick()
    }

    fun controllingLabel(): String? =
        RemoteTargetStore.current()?.deviceId?.let { "Controlling $it" }
}
