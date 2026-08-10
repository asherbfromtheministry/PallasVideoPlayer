package com.vizvag.shieldvideo.data.youtube.potoken

import java.io.Closeable

interface PoTokenGenerator : Closeable {
    suspend fun generatePoToken(identifier: String): String
    fun isExpired(): Boolean
}
