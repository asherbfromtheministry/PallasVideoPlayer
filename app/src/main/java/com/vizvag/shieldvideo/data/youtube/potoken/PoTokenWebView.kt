package com.vizvag.shieldvideo.data.youtube.potoken

import com.vizvag.shieldvideo.data.youtube.YoutubeClientKeys
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.regex.Pattern

/**
 * BotGuard poToken mint — aligned with SmartTube MediaServiceCore PoTokenWebView4 (Aug 2026):
 * homepage ytcfg + ytAtN challenge (Create/att tokens without EVENT_ID are rejected).
 */
class PoTokenWebView private constructor(
    context: Context,
    private val onReady: (PoTokenWebView) -> Unit,
    private val onInitError: (Throwable) -> Unit,
) : PoTokenGenerator {
    private val webView = WebView(context)
    private val poTokenEmitters = mutableListOf<Pair<String, CompletableDeferred<String>>>()
    private lateinit var expirationInstant: Instant
    private val http = defaultPoTokenHttpClient()

    init {
        val webViewSettings = webView.settings
        webViewSettings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            runCatching { WebSettingsCompat.setSafeBrowsingEnabled(webViewSettings, false) }
        }
        webViewSettings.userAgentString = USER_AGENT
        webViewSettings.blockNetworkLoads = true
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val exception = BadWebViewException(fmt)
                    Log.e(TAG, "Broken WebView: $fmt")
                    onInitializationErrorCloseAndCancel(exception)
                    popAllPoTokenEmitters().forEach { (_, deferred) ->
                        deferred.completeExceptionally(exception)
                    }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    fun loadHtml(context: Context) {
        val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst(
                "</script>",
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Thread {
            runCatching {
                val (parsedChallengeData, ytcfg) = getChallengeFromHomepage()
                    ?: getLegacyAttChallenge()
                    ?: throw PoTokenException("No BotGuard challenge from homepage or /att/get")
                val ytcfgJs = ytcfg?.takeIf { it.isNotBlank() } ?: "null"
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        """try {
                if ($ytcfgJs)
                    yt = { config_: $ytcfgJs }
                runBotGuard($parsedChallengeData).then(function (result) {
                    webPoSignalOutput = result.webPoSignalOutput
                    if (!webPoSignalOutput.length)
                        $JS_INTERFACE.onJsInitializationError("webPoSignalOutput is empty")
                    else
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                }, function (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                        null,
                    )
                }
            }.onFailure { onInitializationErrorCloseAndCancel(it) }
        }.start()
    }

    /** SmartTube Aug 2026: mint from homepage (ytcfg, ytAtN) so BotGuard sees EVENT_ID. */
    private fun getChallengeFromHomepage(): Pair<String, String?>? {
        val pageHtml = botguardGet("https://www.youtube.com") ?: return null

        val ytcfgMatcher = Pattern.compile("""ytcfg\.set\((\{.+?\})\);""", Pattern.DOTALL)
            .matcher(pageHtml)
        val ytcfg = if (ytcfgMatcher.find()) ytcfgMatcher.group(1) else {
            Log.w(TAG, "homepage-challenge: no ytcfg")
            null
        }

        val attMatcher = Pattern.compile("""window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)""")
            .matcher(pageHtml)
        if (!attMatcher.find()) {
            Log.w(TAG, "homepage-challenge: no ytAtN")
            return null
        }
        val attData = parseLooseJSON(attMatcher.group(1)!!)
        val rawChallengeData = attData["R"]
        if (rawChallengeData.isNullOrBlank() ||
            !rawChallengeData.contains("bgChallenge") ||
            !rawChallengeData.contains("program")
        ) {
            Log.w(TAG, "homepage-challenge: ytAtN missing bgChallenge")
            return null
        }
        Log.i(TAG, "Using BotGuard challenge from homepage")
        return parseDescrambledChallengeData(rawChallengeData, http) to ytcfg
    }

    /** Legacy fallback: /att/get (not Create — Create tokens are rejected without EVENT_ID). */
    private fun getLegacyAttChallenge(): Pair<String, String?>? {
        val body = botguardPost(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            """{"context":{"client":{"clientName":"WEB","clientVersion":"$WEB_CLIENT_VERSION"}},"engagementType":"ENGAGEMENT_TYPE_UNBOUND"}""",
            contentType = "application/json",
        ) ?: return null
        Log.i(TAG, "Using BotGuard challenge from /att/get fallback")
        return parseDescrambledChallengeData(body, http) to null
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        postBotguardRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { responseBody ->
            try {
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)
                webView.evaluateJavascript(
                    """try {
                getMinter = webPoSignalOutput[0]
                mintCallback = getMinter($integrityToken)
                if (typeof mintCallback === 'undefined')
                    $JS_INTERFACE.onJsInitializationError("mintCallback is not defined")
                else
                    $JS_INTERFACE.onJsInitializationDone()
                webPoSignalOutput = null
                getMinter = null
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                    null,
                )
            } catch (e: Throwable) {
                onInitializationErrorCloseAndCancel(
                    if (e is PoTokenException) e else PoTokenException("GenerateIT parse failed", e),
                )
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationDone() {
        Log.i(TAG, "poToken BotGuard ready (homepage mint)")
        onReady(this)
    }

    override suspend fun generatePoToken(identifier: String): String {
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main.immediate) {
            addPoTokenEmitter(identifier, deferred)
            val u8Identifier = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                poTokenU8 = obtainPoToken($u8Identifier)
                poTokenU8String = ""
                for (i = 0; i < poTokenU8.length; i++) {
                    if (i != 0) poTokenU8String += ","
                    poTokenU8String += poTokenU8[i]
                }
                $JS_INTERFACE.onObtainPoTokenResult("$identifier", poTokenU8String)
                poTokenU8 = null
                poTokenU8String = null
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError("$identifier", error + "\n" + error.stack)
                }""",
                null,
            )
        }
        return deferred.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        popPoTokenEmitter(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val deferred = popPoTokenEmitter(identifier) ?: return
        runCatching { u8ToBase64(poTokenU8) }
            .fold(deferred::complete, deferred::completeExceptionally)
    }

    override fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)

    private fun botguardGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.7")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 200) return@use null
            response.body?.string()
        }
    }.getOrNull()

    private fun botguardPost(
        url: String,
        data: String,
        contentType: String = "application/json+protobuf",
    ): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .header("x-goog-api-key", YoutubeClientKeys.poToken)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(data.toRequestBody(contentType.toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 200) return@use null
            response.body?.string()
        }
    }.getOrNull()

    private fun postBotguardRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        Thread {
            runCatching {
                botguardPost(url, data)
                    ?: throw PoTokenException("Empty BotGuard response for $url")
            }.fold(
                onSuccess = { body ->
                    Handler(Looper.getMainLooper()).post { handleResponseBody(body) }
                },
                onFailure = { onInitializationErrorCloseAndCancel(it) },
            )
        }.start()
    }

    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        Handler(Looper.getMainLooper()).post {
            close()
            onInitError(error)
        }
    }

    @MainThread
    override fun close() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    private fun addPoTokenEmitter(identifier: String, deferred: CompletableDeferred<String>) {
        synchronized(poTokenEmitters) { poTokenEmitters.add(identifier to deferred) }
    }

    private fun popPoTokenEmitter(identifier: String): CompletableDeferred<String>? {
        return synchronized(poTokenEmitters) {
            val index = poTokenEmitters.indexOfFirst { it.first == identifier }
            if (index >= 0) poTokenEmitters.removeAt(index).second else null
        }
    }

    private fun popAllPoTokenEmitters(): List<Pair<String, CompletableDeferred<String>>> {
        return synchronized(poTokenEmitters) {
            val result = poTokenEmitters.toList()
            poTokenEmitters.clear()
            result
        }
    }

    companion object {
        private val TAG = PoTokenWebView::class.java.simpleName
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val WEB_CLIENT_VERSION = "2.20260708.00.00"
        // Match SmartTube PoTokenWebView4 BotGuard UA.
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        private const val JS_INTERFACE = "PoTokenWebView"

        suspend fun create(context: Context): PoTokenWebView = withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<PoTokenWebView>()
            val instance = PoTokenWebView(
                context = context,
                onReady = { ready.complete(it) },
                onInitError = { ready.completeExceptionally(it) },
            )
            instance.loadHtml(context.applicationContext)
            ready.await()
        }
    }
}
