package com.vizvag.shieldvideo.playback.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Fallback when NSD fails: probe this subnet's :8765 for Pallas /v1/status.
 */
object RemoteLanProbe {
    private val http = OkHttpClient.Builder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .callTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    suspend fun scan(context: Context): List<RemoteDevice> = withContext(Dispatchers.IO) {
        val prefix = ipv4Prefix(context) ?: return@withContext emptyList()
        coroutineScope {
            (1..254).map { host ->
                async {
                    val ip = "$prefix.$host"
                    probe(ip, RemoteControlHttpServer.PREFERRED_PORT)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun probe(host: String, port: Int): RemoteDevice? {
        return try {
            val req = Request.Builder()
                .url("http://$host:$port/v1/status")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val status = RemoteStatus.fromJson(JSONObject(body.ifBlank { "{}" }))
                val id = status.deviceId.ifBlank { host }
                RemoteDevice(deviceId = id, host = host, port = port)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ipv4Prefix(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val props: LinkProperties = cm.getLinkProperties(network) ?: return null
        val addr = props.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?: return null
        val parts = addr.hostAddress?.split('.').orEmpty()
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }
}
