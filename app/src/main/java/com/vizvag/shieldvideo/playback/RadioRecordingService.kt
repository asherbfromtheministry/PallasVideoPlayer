package com.vizvag.shieldvideo.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the live radio stream (HLS or progressive) while the user listens, then saves
 * the file to the same Local / NAS recording folder as IPTV (Settings → Live TV).
 */
class RadioRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var scheduledFinishJob: Job? = null
    private val finishRequested = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Discard — cancel without saving
                cancelScheduledFinish()
                finishRequested.set(false)
                job?.cancel()
                job = null
                _state.value = State()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINISH -> {
                // Stop capturing and keep what was recorded
                cancelScheduledFinish()
                finishRequested.set(true)
                return START_NOT_STICKY
            }
            ACTION_SCHEDULE_FINISH -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                if (minutes <= 0 || job?.isActive != true) return START_NOT_STICKY
                scheduleFinish(minutes)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_SCHEDULE -> {
                cancelScheduledFinish()
                if (_state.value.recording) {
                    _state.value = _state.value.copy(
                        finishAtMs = 0L,
                        message = "Recording…"
                    )
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (job?.isActive == true) return START_NOT_STICKY
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim().orEmpty()
                val stationName = intent.getStringExtra(EXTRA_STATION_NAME)?.trim().orEmpty()
                    .ifBlank { "Radio" }
                val programme = intent.getStringExtra(EXTRA_PROGRAMME)?.trim().orEmpty()
                if (streamUrl.isBlank()) return START_NOT_STICKY
                cancelScheduledFinish()
                finishRequested.set(false)
                val startedAt = System.currentTimeMillis()
                startInForeground(stationName)
                _state.value = State(
                    recording = true,
                    stationName = stationName,
                    startedAtMs = startedAt
                )
                job = scope.launch {
                    try {
                        record(
                            streamUrl = streamUrl,
                            stationName = stationName,
                            programme = programme,
                            startedAtMs = startedAt
                        )
                    } finally {
                        cancelScheduledFinish()
                        job = null
                        _state.value = State(
                            message = _state.value.message?.takeIf {
                                it.startsWith("Saved") || it.contains("failed", true)
                            }
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleFinish(minutes: Int) {
        cancelScheduledFinish()
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(
            finishAtMs = at,
            message = "Stops in ${formatMinutesLabel(minutes)}"
        )
        scheduledFinishJob = scope.launch {
            delay(minutes * 60_000L)
            if (isActive && !finishRequested.get()) {
                finishRequested.set(true)
            }
        }
    }

    private fun cancelScheduledFinish() {
        scheduledFinishJob?.cancel()
        scheduledFinishJob = null
    }

    private suspend fun record(
        streamUrl: String,
        stationName: String,
        programme: String,
        startedAtMs: Long
    ) {
        val workDir = File(cacheDir, "radio_recordings").also { it.mkdirs() }
        val raw = File(workDir, "capture_${startedAtMs}.bin")
        try {
            _state.value = _state.value.copy(message = "Recording…")
            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                captureHls(streamUrl, raw)
            } else {
                captureProgressive(streamUrl, raw)
            }
            if (raw.length() == 0L) throw IllegalStateException("No audio captured")
            _state.value = _state.value.copy(recording = false, saving = true, message = "Saving…")
            val remuxed = File(workDir, "capture_${startedAtMs}.mp4")
            val completed = runCatching {
                remuxAudioToMp4(raw, remuxed)
                remuxed
            }.getOrElse {
                remuxed.delete()
                raw
            }
            val extension = when {
                completed === remuxed -> "mp4"
                streamUrl.contains(".m3u8", ignoreCase = true) -> "mp4"
                streamUrl.contains(".mp3", ignoreCase = true) -> "mp3"
                streamUrl.contains(".aac", ignoreCase = true) -> "aac"
                else -> "mp4"
            }
            val outputName = recordingFileName(stationName, programme, startedAtMs, extension)
            RecordingDestination.save(this, completed, outputName)
            if (completed != raw) raw.delete()
            completed.delete()
            _state.value = State(message = "Saved: $outputName")
            delay(50)
        } catch (cancelled: CancellationException) {
            raw.delete()
            throw cancelled
        } catch (e: Exception) {
            raw.delete()
            _state.value = State(message = e.message ?: "Recording failed")
        }
    }

    private suspend fun captureProgressive(streamUrl: String, output: File) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty body")
            body.byteStream().use { input ->
                output.outputStream().buffered(256 * 1024).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (currentCoroutineContext().isActive && !finishRequested.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private suspend fun captureHls(playlistUrl: String, output: File) {
        val downloaded = linkedSetOf<String>()
        var mediaPlaylistUrl = resolveToMediaPlaylist(playlistUrl)
        var sawSegment = false
        output.outputStream().buffered(256 * 1024).use { out ->
            while (currentCoroutineContext().isActive && !finishRequested.get()) {
                val text = fetchText(mediaPlaylistUrl)
                if (text.contains("#EXT-X-STREAM-INF")) {
                    mediaPlaylistUrl = resolveToMediaPlaylist(mediaPlaylistUrl, text)
                    continue
                }
                val before = downloaded.size
                processMediaPlaylist(mediaPlaylistUrl, text, downloaded, out)
                if (downloaded.size > before) sawSegment = true
                delay(1_500)
            }
        }
        if (!sawSegment) throw IllegalStateException("HLS playlist returned no segments")
    }

    private fun resolveToMediaPlaylist(playlistUrl: String, knownText: String? = null): String {
        val text = knownText ?: fetchText(playlistUrl)
        if (!text.contains("#EXT-X-STREAM-INF")) return playlistUrl
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(i + 1) ?: continue
                if (!next.startsWith("#")) return resolveUrl(playlistUrl, next)
            }
        }
        return playlistUrl
    }

    private fun processMediaPlaylist(
        playlistUrl: String,
        text: String,
        downloaded: MutableSet<String>,
        out: java.io.OutputStream
    ) {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var pendingMap: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = extractQuotedUri(line) ?: continue
                    pendingMap = resolveUrl(playlistUrl, uri)
                }
                line.startsWith("#") -> Unit
                else -> {
                    val segmentUrl = resolveUrl(playlistUrl, line)
                    pendingMap?.let { mapUrl ->
                        if (mapUrl !in downloaded) {
                            downloadAppend(mapUrl, out)
                            downloaded += mapUrl
                        }
                        pendingMap = null
                    }
                    if (segmentUrl !in downloaded) {
                        downloadAppend(segmentUrl, out)
                        downloaded += segmentUrl
                    }
                }
            }
        }
    }

    private fun downloadAppend(url: String, out: java.io.OutputStream) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Segment HTTP ${response.code}")
            val body = response.body ?: return
            body.byteStream().use { input -> input.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Playlist HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            return relative
        }
        return URI(base).resolve(relative).toString()
    }

    private fun extractQuotedUri(extXMapLine: String): String? {
        val match = Regex("""URI="([^"]+)"""").find(extXMapLine)
            ?: Regex("""URI=([^,\s]+)""").find(extXMapLine)
        return match?.groupValues?.getOrNull(1)
    }

    private fun remuxAudioToMp4(source: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackMap[index] = muxer.addTrack(format)
                    extractor.selectTrack(index)
                }
            }
            check(trackMap.isNotEmpty()) { "No audio track found" }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val outputTrack = trackMap[extractor.sampleTrackIndex]
                if (outputTrack != null) {
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun recordingFileName(
        stationName: String,
        programme: String,
        startedAtMs: Long,
        extension: String
    ): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(startedAtMs))
        val station = RecordingDestination.safeFilePart(stationName).ifBlank { "Radio" }
        val title = RecordingDestination.safeFilePart(programme)
            .takeUnless { it.isBlank() || it.equals(station, ignoreCase = true) }
        return buildString {
            append(date)
            append(" - ")
            append(station)
            if (title != null) {
                append(" - ")
                append(title)
            }
            append('.')
            append(extension)
        }
    }

    private fun startInForeground(title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording radio")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class State(
        val recording: Boolean = false,
        val saving: Boolean = false,
        val stationName: String = "",
        val startedAtMs: Long = 0L,
        /** Wall-clock time when a timed stop should finish and save; 0 = none. */
        val finishAtMs: Long = 0L,
        val message: String? = null
    )

    companion object {
        private const val CHANNEL_ID = "radio_recording"
        private const val NOTIF_ID = 4403
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        const val ACTION_START = "com.vizvag.shieldvideo.radio.RECORD_START"
        const val ACTION_STOP = "com.vizvag.shieldvideo.radio.RECORD_STOP"
        const val ACTION_FINISH = "com.vizvag.shieldvideo.radio.RECORD_FINISH"
        const val ACTION_SCHEDULE_FINISH = "com.vizvag.shieldvideo.radio.RECORD_SCHEDULE_FINISH"
        const val ACTION_CANCEL_SCHEDULE = "com.vizvag.shieldvideo.radio.RECORD_CANCEL_SCHEDULE"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_STATION_NAME = "station_name"
        const val EXTRA_PROGRAMME = "programme"
        const val EXTRA_MINUTES = "minutes"

        val STOP_AFTER_MINUTES = listOf(15, 30, 45, 60, 120)

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(
            context: Context,
            streamUrl: String,
            stationName: String,
            programme: String?
        ) {
            val intent = Intent(context, RadioRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_STATION_NAME, stationName)
                putExtra(EXTRA_PROGRAMME, programme.orEmpty())
            }
            context.startForegroundService(intent)
        }

        /** Ends capture and saves what was recorded. */
        fun finish(context: Context) {
            context.startService(
                Intent(context, RadioRecordingService::class.java).apply { action = ACTION_FINISH }
            )
        }

        /** Finish and save after [minutes] from now (while already recording). */
        fun scheduleFinish(context: Context, minutes: Int) {
            context.startService(
                Intent(context, RadioRecordingService::class.java).apply {
                    action = ACTION_SCHEDULE_FINISH
                    putExtra(EXTRA_MINUTES, minutes)
                }
            )
        }

        fun cancelScheduledFinish(context: Context) {
            context.startService(
                Intent(context, RadioRecordingService::class.java).apply {
                    action = ACTION_CANCEL_SCHEDULE
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RadioRecordingService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun formatMinutesLabel(minutes: Int): String =
            if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
    }
}
