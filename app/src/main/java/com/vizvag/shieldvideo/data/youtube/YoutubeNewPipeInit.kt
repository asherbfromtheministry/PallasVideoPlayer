package com.vizvag.shieldvideo.data.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import com.vizvag.shieldvideo.data.youtube.potoken.YoutubePoTokenProvider

object YoutubeNewPipeInit {
    @Volatile
    private var initialized = false
    @Volatile
    private var poTokenProvider: YoutubePoTokenProvider? = null

    fun ensureInitialized(appContext: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(
                NewPipeDownloader.init(),
                Localization("en", "GB"),
                ContentCountry("US"),
            )
            poTokenProvider = YoutubePoTokenProvider(appContext.applicationContext)
            YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)
            initialized = true
        }
    }

    fun warmPoToken(appContext: Context) {
        ensureInitialized(appContext)
        Handler(Looper.getMainLooper()).post {
            poTokenProvider?.ensureReadyAsync()
        }
    }

    fun awaitPoTokenReady(timeoutMs: Long = 90_000L) {
        poTokenProvider?.awaitReady(timeoutMs)
    }

    /** GVS streaming token — videoId-bound; append as `pot=` on googlevideo URLs. */
    fun streamingPoTokenForVideo(appContext: Context, videoId: String): String? {
        ensureInitialized(appContext)
        return runCatching {
            val result = poTokenProvider?.getWebClientPoToken(videoId) ?: return null
            // Prefer streaming field; fall back to player pot (same videoId mint after rollout).
            result.streamingDataPoToken?.takeIf { it.isNotBlank() }
                ?: result.playerRequestPoToken.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
