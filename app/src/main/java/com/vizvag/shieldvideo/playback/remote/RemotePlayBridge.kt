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
        ShieldVideoApp.instance.remoteClient.playMusic(device, refs, startIndex).getOrThrow()
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
        ShieldVideoApp.instance.remoteClient
            .musicQueue(device, MusicQueueAction.Add, tracks = refs)
            .getOrThrow()
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
        ShieldVideoApp.instance.remoteClient
            .playNasVideo(device, share, path, title, positionMs, host)
            .getOrThrow()
    }

    suspend fun playLiveTv(channelId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        ShieldVideoApp.instance.remoteClient.playLiveTv(device, channelId).getOrThrow()
    }

    suspend fun playRadio(stationId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        ShieldVideoApp.instance.remoteClient.playRadio(device, stationId).getOrThrow()
    }

    suspend fun playYouTube(videoId: String, local: suspend () -> Unit) {
        val device = RemoteTargetStore.current()
        if (device == null) {
            local()
            return
        }
        ShieldVideoApp.instance.remoteClient.playYouTube(device, videoId).getOrThrow()
    }

    fun controllingLabel(): String? =
        RemoteTargetStore.current()?.deviceId?.let { "Controlling $it" }
}
