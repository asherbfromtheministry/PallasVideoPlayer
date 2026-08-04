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
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.vizvag.shieldvideo.data.iptv.IptvRecording
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStatus
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * Records the transport stream to a temporary file, remuxes/transcodes to MP4 when possible,
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
    private val mainHandler = Handler(Looper.getMainLooper())

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
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                finishRequests.add(id)
                // Clear ● REC in the guide immediately — remux/save can take a while.
                markSaving(IptvRecordingStore(this), id)
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

    private fun markSaving(store: IptvRecordingStore, recordingId: String) {
        val current = store.list().firstOrNull { it.id == recordingId } ?: return
        if (current.status != IptvRecordingStatus.RECORDING &&
            current.status != IptvRecordingStatus.SCHEDULED
        ) {
            return
        }
        val now = System.currentTimeMillis()
        store.upsert(
            current.copy(
                status = IptvRecordingStatus.SAVING,
                stopMs = minOf(current.stopMs, now),
                error = "Saving…",
            )
        )
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
            // Re-read in case finish was requested while waiting to start.
            val active = store.list().firstOrNull { it.id == recording.id } ?: recording
            val stopAtMs = active.stopMs
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
                            if (System.currentTimeMillis() >= stopAtMs) break
                            if (finishRequests.remove(recording.id)) break
                            // Also honour a mid-capture SAVING mark from ACTION_FINISH.
                            val latest = store.list().firstOrNull { it.id == recording.id }
                            if (latest?.status == IptvRecordingStatus.SAVING) break
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (transportStream.length() == 0L) throw IllegalStateException("Stream returned no data")

            store.upsert(
                (store.list().firstOrNull { it.id == recording.id } ?: recording).copy(
                    status = IptvRecordingStatus.SAVING,
                    stopMs = minOf(stopAtMs, System.currentTimeMillis()),
                    error = "Packaging MP4…",
                )
            )

            val mp4 = File(workDir, "${recording.id}.mp4")
            val completedFile = convertToMp4(transportStream, mp4)
            val outputName = recordingFileName(
                store.list().firstOrNull { it.id == recording.id } ?: recording,
                if (completedFile.extension.equals("mp4", true)) "mp4" else "ts"
            )
            val outputLocation = RecordingDestination.save(this, completedFile, outputName)
            val savedAsTs = !completedFile.extension.equals("mp4", true)
            store.upsert(
                (store.list().firstOrNull { it.id == recording.id } ?: recording).copy(
                    status = IptvRecordingStatus.COMPLETED,
                    localPath = outputLocation,
                    error = if (savedAsTs) {
                        "Saved as .ts (stream codecs could not be remuxed to MP4 without re-encoding)"
                    } else {
                        null
                    }
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

    /**
     * Capture is always a temp .ts (cheap while recording).
     * After stop: **fast remux** into MP4 (no re-encode — usually near disk-copy speed).
     * Full re-encode (Transformer) is only attempted for small files; long programmes
     * keep .ts rather than blocking the device for ages.
     */
    private suspend fun convertToMp4(transportStream: File, mp4: File): File {
        val remuxed = runCatching {
            remuxToMp4(transportStream, mp4)
            check(mp4.exists() && mp4.length() > 0L) { "Empty MP4 after remux" }
            mp4
        }.getOrNull()
        if (remuxed != null) return remuxed
        mp4.delete()

        // Re-encoding a multi‑GB programme can take longer than the show itself.
        // Only try Transformer on short captures.
        if (transportStream.length() <= TRANSFORM_MAX_BYTES) {
            val transformed = runCatching {
                transformToMp4(transportStream, mp4)
                check(mp4.exists() && mp4.length() > 0L) { "Empty MP4 after transform" }
                mp4
            }.getOrNull()
            if (transformed != null) return transformed
            mp4.delete()
        } else {
            android.util.Log.i(
                TAG,
                "Skipping slow re-encode for ${(transportStream.length() / (1024 * 1024))}MB capture — keeping .ts"
            )
        }

        android.util.Log.w(TAG, "MP4 remux failed — keeping transport stream")
        return transportStream
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
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.US)
                if (!isMp4CompatibleMime(mime)) continue
                trackMap[index] = muxer.addTrack(format)
                extractor.selectTrack(index)
            }
            check(trackMap.isNotEmpty()) { "No MP4-compatible audio/video tracks" }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstPts = -1L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val outputTrack = trackMap[extractor.sampleTrackIndex]
                if (outputTrack != null) {
                    val pts = extractor.sampleTime
                    if (firstPts < 0L && pts >= 0L) firstPts = pts
                    val outPts = if (pts >= 0L && firstPts >= 0L) (pts - firstPts).coerceAtLeast(0L) else 0L
                    info.set(0, size, outPts, extractor.sampleFlags)
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

    private fun isMp4CompatibleMime(mime: String): Boolean =
        mime == "video/avc" ||
            mime == "video/hevc" ||
            mime == "video/mp4v-es" ||
            mime == "audio/mp4a-latm" ||
            mime == "audio/mpeg" ||
            mime == "audio/aac"

    private suspend fun transformToMp4(source: File, output: File) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(this@IptvRecordingService)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult
                            ) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        }
                    )
                    .build()
                val edited = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(source))
                ).build()
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(edited).build()
                ).build()
                transformer.start(composition, output.absolutePath)
                cont.invokeOnCancellation {
                    mainHandler.post { runCatching { transformer.cancel() } }
                }
            }
        }
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        private const val TAG = "IptvRecording"
        private const val CHANNEL_ID = "iptv_recording"
        private const val NOTIF_ID = 4402
        /** Above this size, skip slow re-encode and keep .ts if remux fails. ~400MB ≈ short HD clip. */
        private const val TRANSFORM_MAX_BYTES = 400L * 1024L * 1024L
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
