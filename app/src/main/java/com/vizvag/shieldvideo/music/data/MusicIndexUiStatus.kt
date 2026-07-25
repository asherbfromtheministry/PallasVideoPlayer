package com.vizvag.shieldvideo.music.data

import com.vizvag.shieldvideo.music.data.local.LibraryIndexStateEntity

data class MusicIndexUiStatus(
    val building: Boolean = false,
    val trackCount: Int = 0,
    val builtAtMs: Long = 0L,
    val progress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
)

fun LibraryIndexStateEntity?.toUiStatus(): MusicIndexUiStatus {
    if (this == null) return MusicIndexUiStatus()
    val error = statusMessage.takeIf { it.startsWith("Error:", ignoreCase = true) }
    return MusicIndexUiStatus(
        building = isIndexing,
        trackCount = trackCount,
        builtAtMs = lastIndexedAt,
        progress = progress,
        message = statusMessage.takeIf { it.isNotBlank() && error == null },
        error = error,
    )
}
