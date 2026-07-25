package com.vizvag.shieldvideo.data.iptv

import com.vizvag.shieldvideo.BuildConfig
import java.util.UUID

data class IptvPlaylistConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val m3uUrl: String = IptvDefaults.M3U_URL,
    val epgUrl: String = IptvDefaults.EPG_URL,
    val enabled: Boolean = true
)

data class IptvChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val group: String,
    val streamUrl: String,
    val tvgId: String?,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val catchupType: String? = null
) {
    val supportsCatchup: Boolean get() = catchupDays > 0 || !catchupSource.isNullOrBlank()
}

/** One XMLTV `<channel>` entry for assign/search UI. */
data class EpgChannelEntry(
    val id: String,
    val name: String
)

data class IptvProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null
) {
    fun isAiringAt(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs in startMs until stopMs
}

data class IptvNowNext(
    val now: IptvProgramme?,
    val next: IptvProgramme?
)

data class IptvRecording(
    val id: String = UUID.randomUUID().toString(),
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val streamUrl: String,
    val status: IptvRecordingStatus = IptvRecordingStatus.SCHEDULED,
    val localPath: String? = null,
    val error: String? = null
)

enum class IptvRecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELLED
}

object IptvDefaults {
    val M3U_URL: String = BuildConfig.DEFAULT_IPTV_M3U
    val EPG_URL: String = BuildConfig.DEFAULT_IPTV_EPG
    const val FAVORITES_GROUP = "Favorites"
    const val ALL_GROUP = "All channels"

    fun defaultPlaylists(): List<IptvPlaylistConfig> = listOf(
        IptvPlaylistConfig(
            id = "default",
            name = "Default",
            m3uUrl = M3U_URL,
            epgUrl = EPG_URL,
            enabled = true
        )
    )
}
