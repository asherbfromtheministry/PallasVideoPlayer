package com.vizvag.shieldvideo.data.podcast

data class PodcastShow(
    val id: String,
    val title: String,
    val feedUrl: String,
    val siteUrl: String = "",
    val imageUrl: String = "",
    /** Primary iTunes / RSS categories (e.g. Comedy, History). */
    val genres: List<String> = emptyList(),
    /** Newest episode publish time from last successful feed parse. */
    val latestEpisodeEpochMs: Long = 0L,
) {
    val primaryGenre: String
        get() = genres.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Uncategorised"
}

data class PodcastEpisode(
    val guid: String,
    val showId: String,
    val title: String,
    val description: String = "",
    val audioUrl: String,
    val publishEpochMs: Long = 0L,
    val durationSec: Long = 0L,
    val imageUrl: String = "",
)

/** Compact episode row for HA catalog / deep-link resolution. */
data class HaPodcastEpisodeRef(
    val guid: String,
    val showId: String,
    val showTitle: String,
    val title: String,
    val label: String,
    val audioUrl: String = "",
    val imageUrl: String = "",
    val durationSec: Long = 0L,
    val publishEpochMs: Long = 0L,
)

data class PodcastEpisodeProgress(
    val guid: String,
    val showId: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val progressFraction: Float
        get() = when {
            completed -> 1f
            durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

    val inProgress: Boolean
        get() = !completed && positionMs > 5_000L
}

enum class PodcastShowSort(val label: String) {
    TITLE("A–Z"),
    RECENT("Recent"),
    GENRE("Genre"),
    IN_PROGRESS("In progress"),
    ;

    fun next(): PodcastShowSort {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

enum class PodcastEpisodeSort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    UNPLAYED("Unplayed"),
    ;

    fun next(): PodcastEpisodeSort {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
