package com.vizvag.shieldvideo.playback.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal LAN HTTP control server (JSON). Preferred port [PREFERRED_PORT], falls back if busy.
 */
class RemoteControlHttpServer(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val router: PlaybackCommandRouter,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = Executors.newCachedThreadPool()

    @Volatile
    var boundPort: Int = 0
        private set

    fun start(): Int {
        if (running.get()) return boundPort
        val socket = openSocket()
        serverSocket = socket
        boundPort = socket.localPort
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = socket.accept()
                    clients.execute { handleClient(client) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Accept failed: ${e.message}")
                }
            }
        }, "pallas-remote-accept").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "Remote control listening on :$boundPort")
        return boundPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        acceptThread = null
    }

    private fun openSocket(): ServerSocket {
        for (port in listOf(PREFERRED_PORT) + (PREFERRED_PORT + 1..PREFERRED_PORT + 20)) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            } catch (_: Exception) {
                // try next
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", 0))
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            // Long enough for large UTF-8 play payloads + prepareInAppPlayer.
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            socket.use { s ->
                handleClientBody(s)
            }
        } catch (e: java.io.IOException) {
            // Client disconnected mid-request (LAN probe, scanner, flaky Wi‑Fi) — never crash the app.
            Log.d(TAG, "Client gone: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "handleClient: ${e.message}")
        }
    }

    private fun handleClientBody(s: Socket) {
        val input = s.getInputStream()
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeResponse(s, 400, """{"error":"bad request"}""")
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }
        // Content-Length is bytes — never treat it as a char count (UTF-8 titles break that).
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (contentLength > 0) {
            String(readExact(input, contentLength), Charsets.UTF_8)
        } else {
            ""
        }

        when {
            method == "GET" && path == "/v1/status" -> {
                writeResponse(s, 200, router.status().toJson().toString())
            }
            method == "POST" && path == "/v1/transport" -> {
                val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
                    writeResponse(s, 400, """{"error":"invalid json"}""")
                    return
                }
                val action = runCatching {
                    TransportAction.valueOf(
                        json.optString("action").replaceFirstChar { it.uppercase() },
                    )
                }.getOrElse {
                    when (json.optString("action").lowercase()) {
                        "play" -> TransportAction.Play
                        "pause" -> TransportAction.Pause
                        "toggle" -> TransportAction.Toggle
                        "stop" -> TransportAction.Stop
                        "next" -> TransportAction.Next
                        "previous", "prev" -> TransportAction.Previous
                        "seek" -> TransportAction.Seek
                        else -> {
                            writeResponse(s, 400, """{"error":"unknown action"}""")
                            return
                        }
                    }
                }
                val result = runBlocking {
                    router.transport(action, json.optLong("positionMs"))
                }
                if (result.isSuccess) {
                    writeResponse(s, 200, router.status().toJson().toString())
                } else {
                    writeResponse(
                        s,
                        500,
                        JSONObject().put(
                            "error",
                            result.exceptionOrNull()?.message ?: "transport failed",
                        ).toString(),
                    )
                }
            }
            method == "POST" && path == "/v1/play" -> {
                val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
                    writeResponse(s, 400, """{"error":"invalid json"}""")
                    return
                }
                val request = runCatching { PlaybackCommandRouter.parsePlayBody(json) }.getOrElse {
                    writeResponse(
                        s,
                        400,
                        JSONObject().put("error", it.message ?: "bad play body").toString(),
                    )
                    return
                }
                val result = runBlocking {
                    router.play(request)
                }
                if (result.isSuccess) {
                    writeResponse(s, 200, router.status().toJson().toString())
                } else {
                    writeResponse(
                        s,
                        500,
                        JSONObject().put(
                            "error",
                            result.exceptionOrNull()?.message ?: "play failed",
                        ).toString(),
                    )
                }
            }
            method == "POST" && path == "/v1/music/queue" -> {
                val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
                    writeResponse(s, 400, """{"error":"invalid json"}""")
                    return
                }
                val action = when (json.optString("action").lowercase()) {
                    "add" -> MusicQueueAction.Add
                    "remove" -> MusicQueueAction.Remove
                    "playindex", "play_index" -> MusicQueueAction.PlayIndex
                    "clear" -> MusicQueueAction.Clear
                    "move" -> MusicQueueAction.Move
                    else -> {
                        writeResponse(s, 400, """{"error":"unknown queue action"}""")
                        return
                    }
                }
                val tracksArr = json.optJSONArray("tracks")
                val tracks = buildList {
                    if (tracksArr != null) {
                        for (i in 0 until tracksArr.length()) {
                            val t = tracksArr.optJSONObject(i) ?: continue
                            add(
                                MusicTrackRef(
                                    id = t.optString("id"),
                                    nasPath = t.optString("nasPath"),
                                    title = t.optString("title"),
                                    artistName = t.optString("artistName"),
                                    albumTitle = t.optString("albumTitle"),
                                    durationMs = t.optLong("durationMs"),
                                ),
                            )
                        }
                    }
                }
                val result = runBlocking {
                    router.musicQueue(
                        action = action,
                        tracks = tracks,
                        index = json.optInt("index", -1),
                        from = json.optInt("from", -1),
                        to = json.optInt("to", -1),
                    )
                }
                if (result.isSuccess) {
                    writeResponse(s, 200, router.status().toJson().toString())
                } else {
                    writeResponse(
                        s,
                        500,
                        JSONObject().put(
                            "error",
                            result.exceptionOrNull()?.message ?: "queue failed",
                        ).toString(),
                    )
                }
            }
            else -> writeResponse(s, 404, """{"error":"not found"}""")
        }
    }

    private fun writeResponse(socket: Socket, code: Int, body: String) {
        if (socket.isClosed || !socket.isConnected) return
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            else -> "$code Error"
        }
        try {
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            out.write(header)
            out.write(bytes)
            out.flush()
        } catch (e: java.io.IOException) {
            Log.d(TAG, "writeResponse aborted: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RemoteHttpServer"
        const val PREFERRED_PORT = 8765
        const val SERVICE_TYPE = "_pallas._tcp."

        /** Read one HTTP header/request line as ASCII (no BufferedReader — it steals body bytes). */
        private fun readAsciiLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString(Charsets.US_ASCII.name())
        }

        private fun readExact(input: InputStream, length: Int): ByteArray {
            val buf = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(buf, offset, length - offset)
                if (n < 0) {
                    error("unexpected end of stream ($offset/$length)")
                }
                offset += n
            }
            return buf
        }
    }
}
