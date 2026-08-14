package com.vizvag.shieldvideo.data.youtube.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicBoolean

class YoutubePoTokenProvider(
    private val appContext: Context,
) : PoTokenProvider {
    private val webViewSupported by lazy {
        runCatching { WebView(appContext) }.isSuccess
    }
    private val webViewBadImpl = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val initStarted = AtomicBoolean(false)

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    /** Call once from the main thread shortly after app start. */
    fun ensureReadyAsync() {
        if (!webViewSupported || webViewBadImpl.get() || !initStarted.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            Thread {
                runCatching {
                    runBlocking {
                        withTimeout(PO_TOKEN_INIT_TIMEOUT_MS) {
                            synchronized(WebPoTokenGenLock) {
                                if (webPoTokenGenerator == null) {
                                    createGeneratorLocked()
                                }
                            }
                        }
                    }
                    if (!ready.isCompleted) ready.complete(Unit)
                    Log.i(TAG, "poToken ready")
                }.onFailure { e ->
                    Log.e(TAG, "poToken init failed", e)
                    webViewBadImpl.set(true)
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            }.start()
        }
    }

    fun awaitReady(timeoutMs: Long = PO_TOKEN_INIT_TIMEOUT_MS) {
        if (!webViewSupported || webViewBadImpl.get()) return
        if (!initStarted.get()) ensureReadyAsync()
        runBlocking {
            withTimeout(timeoutMs) { ready.await() }
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl.get()) return null
        return try {
            awaitReady()
            getWebClientPoToken(videoId, forceRecreate = false)
        } catch (e: BadWebViewException) {
            Log.e(TAG, "WebView broken — poToken disabled", e)
            webViewBadImpl.set(true)
            null
        } catch (e: PoTokenException) {
            Log.e(TAG, "poToken generation failed", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "poToken unavailable", e)
            null
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? =
        getWebClientPoToken(videoId)

    /**
     * Keep null so NewPipe uses the Reel player (URL formats, no SABR-only).
     * Web videoId pot is still applied afterward for GVS when needed.
     */
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun createGeneratorLocked() {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
        innertubeClientRequestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
        webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
            innertubeClientRequestInfo,
            NewPipe.getPreferredLocalization(),
            NewPipe.getPreferredContentCountry(),
            YoutubeParsingHelper.getYouTubeHeaders(),
            YoutubeParsingHelper.YOUTUBEI_V1_URL,
            null,
            false,
        )
        webPoTokenGenerator?.let { old ->
            Handler(Looper.getMainLooper()).post { old.close() }
        }
        webPoTokenGenerator = runBlocking {
            PoTokenWebView.create(appContext)
        }
        // Warm BotGuard once; YouTube now requires videoId-bound tokens for GVS pot=.
        webPoTokenStreamingPot = null
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        data class Pair(
            val generator: PoTokenGenerator,
            val visitorData: String,
            val recreated: Boolean,
        )

        val (generator, visitorData, recreated) = synchronized(WebPoTokenGenLock) {
            val shouldRecreate = webPoTokenGenerator == null ||
                forceRecreate ||
                webPoTokenGenerator!!.isExpired()

            if (shouldRecreate) {
                createGeneratorLocked()
            }

            Pair(
                webPoTokenGenerator!!,
                webPoTokenVisitorData!!,
                shouldRecreate,
            )
        }

        val videoBoundPot = try {
            runBlocking { generator.generatePoToken(videoId) }
        } catch (t: Throwable) {
            if (recreated) throw t
            Log.e(TAG, "Retrying poToken after failure", t)
            return getWebClientPoToken(videoId, forceRecreate = true)
        }

        // YouTube rolled out content-bound GVS tokens (videoId mint). VisitorData-bound
        // streaming pots now 403 on many videos — put the videoId pot on both fields.
        webPoTokenStreamingPot = videoBoundPot
        return PoTokenResult(visitorData, videoBoundPot, videoBoundPot)
    }

    private companion object {
        const val PO_TOKEN_INIT_TIMEOUT_MS = 90_000L
        val TAG: String = YoutubePoTokenProvider::class.java.simpleName
    }
}
