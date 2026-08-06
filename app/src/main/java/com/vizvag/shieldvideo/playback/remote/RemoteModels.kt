package com.vizvag.shieldvideo.playback.remote

import org.json.JSONArray
import org.json.JSONObject

enum class RemotePlaybackMode {
    Idle,
    Music,
    NasVideo,
    LiveTv,
    Radio,
    YouTube,
    Podcasts,
}

data class RemoteQueueItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val nasPath: String = "",
    val durationMs: Long = 0,
)

data class RemoteStatus(
    val deviceId: String,
    val mode: RemotePlaybackMode,
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val artworkUrl: String = "",
    val queue: List<RemoteQueueItem> = emptyList(),
    val queueIndex: Int = -1,
    /** Compose route currently shown on the player (`home`, `music`, …). */
    val uiRoute: String = "home",
    /** Radio station id / Live TV channel id / YouTube video id / podcast guid when relevant. */
    val contentId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("mode", mode.name)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("isPlaying", isPlaying)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("artworkUrl", artworkUrl)
        .put("queueIndex", queueIndex)
        .put("uiRoute", uiRoute)
        .put("contentId", contentId)
        .put(
            "queue",
            JSONArray().also { arr ->
                queue.forEach { item ->
                    arr.put(
                        JSONObject()
                            .put("id", item.id)
                            .put("title", item.title)
                            .put("artist", item.artist)
                            .put("nasPath", item.nasPath)
                            .put("durationMs", item.durationMs),
                    )
                }
            },
        )

    companion object {
        fun fromJson(obj: JSONObject): RemoteStatus {
            val queueArr = obj.optJSONArray("queue")
            val queue = buildList {
                if (queueArr != null) {
                    for (i in 0 until queueArr.length()) {
                        val q = queueArr.optJSONObject(i) ?: continue
                        add(
                            RemoteQueueItem(
                                id = q.optString("id"),
                                title = q.optString("title"),
                                artist = q.optString("artist"),
                                nasPath = q.optString("nasPath"),
                                durationMs = q.optLong("durationMs"),
                            ),
                        )
                    }
                }
            }
            return RemoteStatus(
                deviceId = obj.optString("deviceId"),
                mode = runCatching {
                    RemotePlaybackMode.valueOf(obj.optString("mode", RemotePlaybackMode.Idle.name))
                }.getOrDefault(RemotePlaybackMode.Idle),
                title = obj.optString("title"),
                subtitle = obj.optString("subtitle"),
                isPlaying = obj.optBoolean("isPlaying"),
                positionMs = obj.optLong("positionMs"),
                durationMs = obj.optLong("durationMs"),
                artworkUrl = obj.optString("artworkUrl"),
                queue = queue,
                queueIndex = obj.optInt("queueIndex", -1),
                uiRoute = obj.optString("uiRoute", "home").ifBlank { "home" },
                contentId = obj.optString("contentId"),
            )
        }
    }
}

data class RemoteDevice(
    val deviceId: String,
    val host: String,
    val port: Int,
) {
    val baseUrl: String get() = "http://$host:$port"
}

sealed class RemotePlayRequest {
    data class Music(
        val tracks: List<MusicTrackRef>,
        val startIndex: Int = 0,
    ) : RemotePlayRequest()

    data class NasVideo(
        val share: String,
        val path: String,
        val title: String = "",
        val positionMs: Long? = null,
        val host: String? = null,
    ) : RemotePlayRequest()

    data class LiveTv(val channelId: String) : RemotePlayRequest()
    data class Radio(val stationId: String) : RemotePlayRequest()
    data class YouTube(val videoId: String) : RemotePlayRequest()
    data class Podcast(
        val showId: String,
        val episodeGuid: String,
        val audioUrl: String = "",
        val episodeTitle: String = "",
        val showTitle: String = "",
        val imageUrl: String = "",
        val durationSec: Long = 0L,
        val positionMs: Long = 0L,
    ) : RemotePlayRequest()
}

data class MusicTrackRef(
    val id: String = "",
    val nasPath: String = "",
    val title: String = "",
    val artistName: String = "",
    val albumTitle: String = "",
    val durationMs: Long = 0,
)

enum class TransportAction {
    Play,
    Pause,
    Toggle,
    Stop,
    Next,
    Previous,
    Seek,
}

enum class MusicQueueAction {
    Add,
    Remove,
    PlayIndex,
    Clear,
    Move,
}
