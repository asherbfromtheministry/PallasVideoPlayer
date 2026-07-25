package com.vizvag.shieldvideo.music.player

import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient

class StreamUrlBuilder constructor(
    private val synologyApiClient: SynologyApiClient,
) {
    suspend fun buildStreamUrl(nasPath: String): String =
        synologyApiClient.buildDownloadUrl(nasPath, mode = "open").invoke()

    suspend fun buildArtUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return synologyApiClient.buildDownloadUrl(path, mode = "open").invoke()
    }
}
