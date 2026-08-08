package com.vizvag.shieldvideo.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Keeps [LocalMediaProxy] alive in the foreground while VLC streams from 127.0.0.1.
 */
class LocalMediaProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxyAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val share = intent.getStringExtra(EXTRA_SHARE).orEmpty()
                val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Pallas handoff" }
                if (share.isBlank() || path.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(title)
                val app = application as ShieldVideoApp
                val settings = app.settingsRepository.load()
                val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }
                val playSettings = if (host != null) settings.copy(host = host) else settings
                scope.launch {
                    val result = app.localMediaProxy.start(playSettings, share, path)
                    val callback = pendingCallback
                    pendingCallback = null
                    if (result.isSuccess) {
                        // HTTP URI kept for VLC; MediaPlayerLauncher swaps to content:// for MX/XPlayer.
                        callback?.onReady(result.getOrThrow())
                    } else {
                        val message = result.exceptionOrNull()?.message ?: "Proxy failed"
                        Log.e(TAG, message, result.exceptionOrNull())
                        callback?.onError(message)
                        stopProxyAndSelf()
                    }
                }
                return START_STICKY
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        (application as? ShieldVideoApp)?.localMediaProxy?.stop()
        super.onDestroy()
    }

    private fun startAsForeground(title: String) {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming to VLC")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media handoff",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun stopProxyAndSelf() {
        (application as? ShieldVideoApp)?.localMediaProxy?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "LocalMediaProxySvc"
        private const val CHANNEL_ID = "pallas_media_proxy"
        private const val NOTIFICATION_ID = 7101
        const val ACTION_START = "com.vizvag.shieldvideo.proxy.START"
        const val ACTION_STOP = "com.vizvag.shieldvideo.proxy.STOP"
        const val EXTRA_SHARE = "share"
        const val EXTRA_PATH = "path"
        const val EXTRA_HOST = "host"
        const val EXTRA_TITLE = "title"

        @Volatile
        private var pendingCallback: Callback? = null

        fun start(
            context: Context,
            share: String,
            path: String,
            host: String?,
            title: String,
            callback: Callback
        ) {
            pendingCallback = callback
            val intent = Intent(context, LocalMediaProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SHARE, share)
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(intent)
        }

        suspend fun startAndAwait(
            context: Context,
            share: String,
            path: String,
            host: String?,
            title: String
        ): Uri = suspendCancellableCoroutine { cont ->
            start(
                context = context,
                share = share,
                path = path,
                host = host,
                title = title,
                callback = object : Callback {
                    override fun onReady(uri: Uri) {
                        if (cont.isActive) cont.resume(uri)
                    }

                    override fun onError(message: String) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException(message))
                    }
                }
            )
            cont.invokeOnCancellation { stop(context) }
        }

        fun stop(context: Context) {
            pendingCallback = null
            val intent = Intent(context, LocalMediaProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    interface Callback {
        fun onReady(uri: Uri)
        fun onError(message: String)
    }
}
