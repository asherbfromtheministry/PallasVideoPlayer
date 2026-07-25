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
import com.vizvag.shieldvideo.data.iptv.IptvRecording
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStatus
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Records the transport stream to a temporary file, remuxes compatible audio/video into MP4,
 * then moves it to the configured local document tree or NAS folder.
 */
class IptvRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Recordings asked to stop early — the capture loop exits and the file is still saved. */
    private val finishRequests = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id == null) {
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                } else {
                    jobs.remove(id)?.cancel()
                }
                stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_FINISH -> {
                // Stop capturing but keep everything recorded so far: the loop exits,
                // the file is remuxed and saved to the configured destination.
                intent.getStringExtra(EXTRA_ID)?.let(finishRequests::add)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val recordingId = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                if (jobs.containsKey(recordingId)) return START_NOT_STICKY
                val store = IptvRecordingStore(this)
                val recording = store.list().firstOrNull { it.id == recordingId } ?: return START_NOT_STICKY
                startInForeground(recording.title)
                val launched = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        record(store, recording)
                    } finally {
                        jobs.remove(recordingId)
                        stopIfIdle()
                    }
                }
                jobs[recordingId] = launched
                launched.start()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun record(store: IptvRecordingStore, recording: IptvRecording) {
        val workDir = File(cacheDir, "iptv_recordings").also { it.mkdirs() }
        val transportStream = File(workDir, "${recording.id}.ts")
        store.upsert(
            recording.copy(
                status = IptvRecordingStatus.RECORDING,
                localPath = null,
                error = null
            )
        )
        try {
            val waitMs = recording.startMs - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            val request = Request.Builder()
                .url(recording.streamUrl)
                .header("User-Agent", "PallasVideoPlayer/1.6")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty body")
                body.byteStream().use { input ->
                    transportStream.outputStream().buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (currentCoroutineContext().isActive) {
                            if (System.currentTimeMillis() >= recording.stopMs) break
                            if (finishRequests.remove(recording.id)) break
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (transportStream.length() == 0L) throw IllegalStateException("Stream returned no data")
            val mp4 = File(workDir, "${recording.id}.mp4")
            val completedFile = runCatching {
                remuxToMp4(transportStream, mp4)
                mp4
            }.getOrElse {
                mp4.delete()
                // MPEG-2 and a few provider-specific codecs cannot be placed in Android's MP4
                // muxer without transcoding. Preserve the playable transport stream in that case.
                transportStream
            }
            val outputName = recordingFileName(
                recording,
                if (completedFile.extension.equals("mp4", true)) "mp4" else "ts"
            )
            val outputLocation = RecordingDestination.save(this, completedFile, outputName)
            store.upsert(
                recording.copy(
                    status = IptvRecordingStatus.COMPLETED,
                    localPath = outputLocation,
                    error = null
                )
            )
            if (completedFile != transportStream) transportStream.delete()
            completedFile.delete()
        } catch (cancelled: CancellationException) {
            transportStream.delete()
            store.upsert(recording.copy(status = IptvRecordingStatus.CANCELLED, error = null))
            throw cancelled
        } catch (e: Exception) {
            store.upsert(
                recording.copy(
                    status = IptvRecordingStatus.FAILED,
                    localPath = transportStream.absolutePath.takeIf { transportStream.exists() },
                    error = e.message ?: "Recording failed"
                )
            )
        }
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun remuxToMp4(source: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[index] = muxer.addTrack(format)
                    extractor.selectTrack(index)
                }
            }
            check(trackMap.isNotEmpty()) { "No audio/video tracks found" }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
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

    private fun recordingFileName(recording: IptvRecording, extension: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
            .format(Date(recording.startMs))
        val channel = RecordingDestination.safeFilePart(recording.channelName)
        val title = RecordingDestination.safeFilePart(recording.title)
            .takeUnless { it.equals(channel, ignoreCase = true) || it.isBlank() }
        return buildString {
            append(date)
            append(" - ")
            append(channel.ifBlank { "IPTV" })
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
                NotificationChannel(CHANNEL_ID, "IPTV recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
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

    companion object {
        private const val CHANNEL_ID = "iptv_recording"
        private const val NOTIF_ID = 4402
        const val ACTION_START = "com.vizvag.shieldvideo.iptv.RECORD_START"
        const val ACTION_STOP = "com.vizvag.shieldvideo.iptv.RECORD_STOP"
        const val ACTION_FINISH = "com.vizvag.shieldvideo.iptv.RECORD_FINISH"
        const val EXTRA_ID = "recording_id"

        fun start(context: Context, recordingId: String) {
            val intent = Intent(context, IptvRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, recordingId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, IptvRecordingService::class.java).apply { action = ACTION_STOP }
            )
        }

        /** Ends [recordingId] now; the captured portion is remuxed and saved. */
        fun finish(context: Context, recordingId: String) {
            context.startService(
                Intent(context, IptvRecordingService::class.java).apply {
                    action = ACTION_FINISH
                    putExtra(EXTRA_ID, recordingId)
                }
            )
        }
    }
}
