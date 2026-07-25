package com.vizvag.shieldvideo.playback

import android.net.Uri
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.vizvag.shieldvideo.data.http.HttpNasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HTTP server that streams a NAS file with Range support so VLC can
 * resume without needing its own SMB credentials or fragile File Station URLs.
 */
class LocalMediaProxy(
    private val httpRepository: HttpNasRepository = HttpNasRepository()
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var active: ActiveFile? = null
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()

    private val smbConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(30, TimeUnit.SECONDS)
        .withDialects(
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0
        )
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ActiveFile(
        val settings: AppSettings,
        val share: String,
        val path: String,
        val size: Long,
        val mime: String,
        val fileName: String,
        val preferSmb: Boolean,
        val httpUrl: String?
    )

    suspend fun start(
        settings: AppSettings,
        shareName: String,
        relativePath: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = relativePath.trim('/').replace('\\', '/')
            val fileName = normalized.substringAfterLast('/')
            val mime = mimeFor(fileName)
            val smbSettings = settings.copy(
                port = 445,
                connectionMode = ConnectionMode.SMB3
            )

            // Always prefer SMB for Range/seek — File Station Download often rejects/ignores Range.
            var size = -1L
            var preferSmb = true
            var httpUrl: String? = null

            val smbSize = runCatching { probeSmbSize(smbSettings, shareName, normalized) }
                .onFailure { Log.w(TAG, "SMB probe failed: ${it.message}") }
                .getOrNull()
            if (smbSize != null && smbSize > 0L) {
                size = smbSize
            } else {
                preferSmb = false
                val httpSettings = settings.copy(
                    port = if (settings.port in setOf(0, 445)) 5000 else settings.port,
                    connectionMode = ConnectionMode.HTTP
                )
                val url = httpRepository.playbackUrl(httpSettings, shareName, normalized).getOrThrow()
                val httpSize = probeHttpSize(url)
                    ?: throw IllegalStateException(
                        "NAS SMB unreachable and File Station size probe failed. " +
                            "Check password / SMB enabled on Synology."
                    )
                httpUrl = url
                size = httpSize
            }

            synchronized(lock) {
                stopLocked()
                // Bind on all interfaces and advertise the device LAN IP — some VLC/Shield
                // builds never open http://127.0.0.1 from another UID even when it works via adb.
                val socket = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
                serverSocket = socket
                active = ActiveFile(
                    settings = smbSettings,
                    share = shareName.trim('/'),
                    path = normalized,
                    size = size,
                    mime = mime,
                    fileName = fileName,
                    preferSmb = preferSmb,
                    httpUrl = httpUrl
                )
                running.set(true)
                acceptThread = Thread({ acceptLoop(socket) }, "pallas-media-proxy").also {
                    it.isDaemon = false
                    it.start()
                }
                val port = socket.localPort
                val host = lanHost()
                Log.i(TAG, "Proxy listening on $host:$port smb=$preferSmb size=$size")
                Uri.parse("http://$host:$port/${Uri.encode(fileName)}")
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        active = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (error: Exception) {
                Log.w(TAG, "accept failed", error)
                break
            }
            clients.execute { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            val file = active ?: run {
                writeSimple(output, 503, "text/plain", "Proxy idle")
                return
            }
            if (!request.path.startsWith("/") || request.method != "GET" && request.method != "HEAD") {
                writeSimple(output, 405, "text/plain", "Method not allowed")
                return
            }

            val total = file.size
            val range = parseRange(request.rangeHeader, total)
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1L)
            val length = (end - start + 1L).coerceAtLeast(0L)
            Log.i(
                TAG,
                "HTTP ${request.method} range=${request.rangeHeader} -> $start-$end/$total preferSmb=${file.preferSmb}"
            )

            val status = if (range != null) "206 Partial Content" else "200 OK"
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: ${file.mime}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (range != null) {
                    append("Content-Range: bytes $start-$end/$total\r\n")
                }
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.flush()
            if (request.method == "HEAD" || length == 0L) return

            if (file.preferSmb) {
                streamSmb(file, start, length, output)
            } else {
                streamHttp(file.httpUrl!!, start, end, length, range != null, output)
            }
        } catch (error: Exception) {
            if (error.message?.contains("Broken pipe", ignoreCase = true) == true ||
                error.message?.contains("Connection reset", ignoreCase = true) == true
            ) {
                Log.d(TAG, "client closed early (normal for Range probes)")
            } else {
                Log.w(TAG, "client handler error: ${error.message}", error)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun streamSmb(file: ActiveFile, start: Long, length: Long, output: BufferedOutputStream) {
        val client = SMBClient(smbConfig)
        client.connect(file.settings.host, 445).use { connection ->
            val auth = AuthenticationContext(
                file.settings.username,
                file.settings.password.toCharArray(),
                null
            )
            connection.authenticate(auth).use { session ->
                (session.connectShare(file.share) as DiskShare).use { share ->
                    share.openFile(
                        file.path,
                        EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    ).use { remote ->
                        var remaining = length
                        var offset = start
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = remote.read(buffer, offset, 0, chunk)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            offset += read
                            remaining -= read
                        }
                        output.flush()
                    }
                }
            }
        }
    }

    private fun streamHttp(
        url: String,
        start: Long,
        end: Long,
        length: Long,
        partial: Boolean,
        output: BufferedOutputStream
    ) {
        val builder = Request.Builder().url(url).get()
        if (partial) {
            builder.header("Range", "bytes=$start-$end")
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            Log.i(TAG, "upstream HTTP ${response.code} len=${response.header("Content-Length")}")
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Upstream HTTP ${response.code}")
            }
            if (partial && response.code != 206) {
                throw IOException("Upstream did not honor Range (HTTP ${response.code})")
            }
            val body = response.body ?: throw IOException("Empty upstream body")
            body.byteStream().use { upstream ->
                copyLimited(upstream, output, length)
            }
        }
    }

    private fun copyLimited(input: InputStream, output: BufferedOutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val chunk = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        output.flush()
    }

    private fun probeSmbSize(settings: AppSettings, shareName: String, path: String): Long {
        val client = SMBClient(smbConfig)
        client.connect(settings.host, 445).use { connection ->
            val auth = AuthenticationContext(
                settings.username,
                settings.password.toCharArray(),
                null
            )
            connection.authenticate(auth).use { session ->
                (session.connectShare(shareName.trim('/')) as DiskShare).use { share ->
                    share.openFile(
                        path,
                        EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_DATA),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    ).use { remote ->
                        return remote.fileInformation.standardInformation.endOfFile
                    }
                }
            }
        }
    }

    private fun probeHttpSize(url: String): Long? {
        val head = Request.Builder().url(url).head().build()
        httpClient.newCall(head).execute().use { response ->
            if (response.isSuccessful) {
                val len = response.header("Content-Length")?.toLongOrNull()
                if (len != null && len > 0L) return len
            }
        }
        val get = Request.Builder().url(url).get().header("Range", "bytes=0-0").build()
        httpClient.newCall(get).execute().use { response ->
            val range = response.header("Content-Range")
            val total = range?.substringAfter('/')?.toLongOrNull()
            if (total != null && total > 0L) return total
            if (response.isSuccessful) {
                return response.header("Content-Length")?.toLongOrNull()
            }
        }
        return null
    }

    private data class HttpRequest(val method: String, val path: String, val rangeHeader: String?)

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            lines += line
            if (lines.size > 64) break
        }
        if (lines.isEmpty()) return null
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        return HttpRequest(parts[0].uppercase(Locale.US), parts[1], range)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank() || total <= 0L) return null
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val startPart = spec.substringBefore('-', missingDelimiterValue = "")
        val endPart = spec.substringAfter('-', missingDelimiterValue = "")
        val start = startPart.toLongOrNull() ?: 0L
        val end = endPart.toLongOrNull() ?: (total - 1L)
        if (start < 0L || start >= total) return null
        return start to minOf(end, total - 1L)
    }

    private fun writeSimple(output: BufferedOutputStream, code: Int, mime: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            405 -> "405 Method Not Allowed"
            503 -> "503 Service Unavailable"
            else -> "$code Error"
        }
        val headers = "HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        else -> "video/*"
    }

    private fun lanHost(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is java.net.Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                        return host
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    companion object {
        private const val TAG = "LocalMediaProxy"
    }
}
