package com.vizvag.shieldvideo.ui.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot play request when opening YouTube via intent (avoids home-screen D-pad mistakes). */
object YoutubeNavRequests {
    private val _pendingVideoId = MutableStateFlow<String?>(null)
    val pendingVideoId: StateFlow<String?> = _pendingVideoId.asStateFlow()

    fun requestPlay(videoId: String) {
        val id = videoId.trim()
        if (id.matches(Regex("^[\\w-]{11}$"))) {
            _pendingVideoId.value = id
        }
    }

    fun consume(videoId: String) {
        if (_pendingVideoId.value == videoId) {
            _pendingVideoId.value = null
        }
    }
}
