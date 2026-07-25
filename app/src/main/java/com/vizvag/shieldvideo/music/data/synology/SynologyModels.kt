package com.vizvag.shieldvideo.music.data.synology

import com.google.gson.annotations.SerializedName

data class SynologyResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: SynologyError? = null,
)

data class SynologyError(
    val code: Int,
)

data class ApiInfoData(
    val apis: Map<String, ApiDescriptor>? = null,
) {
    @SerializedName("SYNO.API.Auth")
    var auth: ApiDescriptor? = null

    @SerializedName("SYNO.FileStation.List")
    var fileStationList: ApiDescriptor? = null

    @SerializedName("SYNO.FileStation.Search")
    var fileStationSearch: ApiDescriptor? = null

    @SerializedName("SYNO.FileStation.Download")
    var fileStationDownload: ApiDescriptor? = null

    @SerializedName("SYNO.FileStation.Thumb")
    var fileStationThumb: ApiDescriptor? = null
}

data class ApiDescriptor(
    val path: String,
    @SerializedName("minVersion") val minVersion: Int,
    @SerializedName("maxVersion") val maxVersion: Int,
)

data class LoginData(
    val sid: String,
)

data class FileListData(
    val total: Int = 0,
    val offset: Int = 0,
    val files: List<FileEntry> = emptyList(),
    val shares: List<FileEntry> = emptyList(),
)

data class FileEntry(
    val path: String,
    val name: String,
    val isdir: Boolean = false,
    val additional: FileAdditional? = null,
    val children: List<FileEntry>? = null,
)

data class FileAdditional(
    val size: Long? = null,
    val real_path: String? = null,
    val type: String? = null,
    val time: FileTime? = null,
)

data class FileTime(
    val mtime: Long = 0,
)

data class SearchStartData(
    val taskid: String,
)

data class SearchListData(
    val total: Int = 0,
    val offset: Int = 0,
    val finished: Boolean = false,
    val files: List<FileEntry> = emptyList(),
)

/** Synology Audio Station media index (tags already extracted on the NAS). */
data class AudioStationSongListData(
    val songs: List<AudioStationSong> = emptyList(),
    val offset: Int = 0,
    val total: Int = 0,
)

data class AudioStationSong(
    val id: String = "",
    val path: String = "",
    val title: String? = null,
    val type: String? = null,
    val additional: AudioStationSongAdditional? = null,
)

data class AudioStationSongAdditional(
    @SerializedName("song_tag") val songTag: AudioStationSongTag? = null,
    @SerializedName("song_audio") val songAudio: AudioStationSongAudio? = null,
)

data class AudioStationSongTag(
    val title: String? = null,
    val album: String? = null,
    @SerializedName("album_artist") val albumArtist: String? = null,
    val artist: String? = null,
    val comment: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val disc: Int? = null,
    val track: Int? = null,
    val year: Int? = null,
)

data class AudioStationSongAudio(
    val bitrate: Int? = null,
    val channel: Int? = null,
    val codec: String? = null,
    val container: String? = null,
    val duration: Int? = null,
    val filesize: Long? = null,
    val frequency: Int? = null,
)

/** Synology Video Station media index (paths + titles already on the NAS). */
data class VideoStationListData(
    val movies: List<VideoStationItem> = emptyList(),
    val episodes: List<VideoStationItem> = emptyList(),
    val videos: List<VideoStationItem> = emptyList(),
    val recordings: List<VideoStationItem> = emptyList(),
    @SerializedName("tv_recordings") val tvRecordings: List<VideoStationItem> = emptyList(),
    val offset: Int = 0,
    val total: Int = 0,
) {
    fun items(): List<VideoStationItem> = when {
        movies.isNotEmpty() -> movies
        episodes.isNotEmpty() -> episodes
        videos.isNotEmpty() -> videos
        recordings.isNotEmpty() -> recordings
        tvRecordings.isNotEmpty() -> tvRecordings
        else -> emptyList()
    }
}

data class VideoStationItem(
    val id: Int = 0,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerializedName("tvshow_name") val tvshowName: String? = null,
    val additional: VideoStationAdditional? = null,
)

data class VideoStationAdditional(
    val file: List<VideoStationFile> = emptyList(),
    val tvshow: List<VideoStationTvShowRef> = emptyList(),
)

data class VideoStationTvShowRef(
    val title: String? = null,
)

data class VideoStationFile(
    val id: Int = 0,
    val path: String? = null,
    @SerializedName("sharepath") val sharePath: String? = null,
    @SerializedName("share_path") val sharePathAlt: String? = null,
    val filename: String? = null,
    val filesize: Long? = null,
    val size: Long? = null,
) {
    /** File Station-style path (`/share/...`), never a raw `/volumeN/...` path. */
    fun resolvedPath(): String? {
        val raw = sequenceOf(path, sharePath, sharePathAlt)
            .mapNotNull { it?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .firstOrNull()
            ?: return null
        return toFileStationPath(raw)
    }

    fun resolvedSize(): Long = filesize ?: size ?: 0L

    companion object {
        fun toFileStationPath(raw: String): String {
            var p = raw.replace('\\', '/').trim()
            Regex("^/volume\\d+(/.*)$", RegexOption.IGNORE_CASE).matchEntire(p)?.let {
                p = it.groupValues[1]
            }
            if (!p.startsWith("/")) p = "/$p"
            return p.trimEnd('/').ifBlank { "/" }
        }
    }
}
