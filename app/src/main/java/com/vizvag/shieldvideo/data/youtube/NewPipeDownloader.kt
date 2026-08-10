package com.vizvag.shieldvideo.data.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val requestBody = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), requestBody)
            .url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        val body = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile
        private var instance: NewPipeDownloader? = null

        fun init(): NewPipeDownloader {
            val created = NewPipeDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build(),
            )
            instance = created
            return created
        }

        fun getInstance(): NewPipeDownloader =
            instance ?: init()
    }
}
