package com.vizvag.shieldvideo.data.radio

/** Now-playing metadata from the BBC RMS API (not embedded in the HLS stream). */
sealed class RadioNowPlaying {
    data class Music(
        val artist: String,
        val track: String,
        val status: String,
        val imageUrl: String?,
        val recent: List<RadioTrackHistory> = emptyList(),
        /** Current programme name from BBC broadcasts API (e.g. "Radio 1 Breakfast"). */
        val showTitle: String? = null,
        val showEpisode: String? = null
    ) : RadioNowPlaying()

    data class Show(
        val title: String,
        val episode: String?,
        val synopsis: String?,
        val imageUrl: String?,
        val startMs: Long?,
        val endMs: Long?
    ) : RadioNowPlaying()

    data object Unavailable : RadioNowPlaying()
}

data class RadioTrackHistory(
    val artist: String,
    val track: String,
    val status: String
)
