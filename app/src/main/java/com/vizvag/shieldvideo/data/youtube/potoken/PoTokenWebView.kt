package com.vizvag.shieldvideo.data.youtube.potoken

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class PoTokenWebView private constructor(
    context: Context,
    private val onReady: (PoTokenWebView) -> Unit,
    private val onInitError: (Throwable) -> Unit,
) : PoTokenGenerator {
    private val webView = WebView(context)
    private val poTokenEmitters = mutableListOf<Pair<String, CompletableDeferred<String>>>()
    private lateinit var expirationInstant: Instant
    private val http = OkHttpClient()

    init {
        val webViewSettings = webView.settings
        webViewSettings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webViewSettings, false)
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
        postBotguardRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            try {
                val parsedChallengeData = parseChallengeData(responseBody)
                webView.evaluateJavascript(
                    """try {
                data = $parsedChallengeData
                runBotGuard(data).then(function (result) {
                    this.webPoSignalOutput = result.webPoSignalOutput
                    $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                }, function (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                    null,
                )
            } catch (e: Throwable) {
                onInitializationErrorCloseAndCancel(
                    if (e is PoTokenException) e else PoTokenException("Create parse failed", e),
                )
            }
        }
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
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    onReady(this)
                }
            } catch (e: Throwable) {
                onInitializationErrorCloseAndCancel(
                    if (e is PoTokenException) e else PoTokenException("GenerateIT parse failed", e),
                )
            }
        }
    }

    override suspend fun generatePoToken(identifier: String): String {
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main.immediate) {
            addPoTokenEmitter(identifier, deferred)
            val u8Identifier = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                identifier = "$identifier"
                u8Identifier = $u8Identifier
                poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                poTokenU8String = ""
                for (i = 0; i < poTokenU8.length; i++) {
                    if (i != 0) poTokenU8String += ","
                    poTokenU8String += poTokenU8[i]
                }
                $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
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

    private fun postBotguardRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        Thread {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json+protobuf")
                    .header("x-goog-api-key", GOOGLE_API_KEY)
                    .header("x-user-agent", "grpc-web-javascript/0.1")
                    .post(data.toRequestBody("application/json+protobuf".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw PoTokenException("Invalid response code: ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
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
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
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
