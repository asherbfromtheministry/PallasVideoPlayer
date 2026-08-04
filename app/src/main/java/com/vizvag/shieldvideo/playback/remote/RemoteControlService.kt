package com.vizvag.shieldvideo.playback.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service: HTTP control API + NSD advertisement for LAN remotes.
 */
class RemoteControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: RemoteControlHttpServer? = null
    private var nsd: RemoteNsdAdvertiser? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tearDown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val app = application as ShieldVideoApp
                val settings = app.settingsRepository.load()
                if (!settings.allowRemoteControl) {
                    tearDown()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val advertiseId = settings.deviceId.trim().lowercase().ifBlank {
                    android.os.Build.MODEL.replace(Regex("[^a-zA-Z0-9]+"), "-")
                        .trim('-')
                        .lowercase()
                        .ifBlank { "pallas" }
                        .take(32)
                }
                startAsForeground(advertiseId)
                if (httpServer == null) {
                    httpServer = RemoteControlHttpServer(
                        scope = scope,
                        router = app.playbackRouter,
                    )
                    nsd = RemoteNsdAdvertiser(this)
                }
                val port = httpServer!!.start()
                nsd?.register(advertiseId, port)
                return START_STICKY
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDown()
        scope.cancel()
        super.onDestroy()
    }

    private fun tearDown() {
        nsd?.unregister()
        nsd = null
        httpServer?.stop()
        httpServer = null
    }

    private fun startAsForeground(deviceId: String) {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pallas remote")
            .setContentText("Controllable as $deviceId on Wi‑Fi")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
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
            "Remote control",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RemoteControlSvc"
        private const val CHANNEL_ID = "pallas_remote_control"
        private const val NOTIFICATION_ID = 7102
        const val ACTION_START = "com.vizvag.shieldvideo.remote.START"
        const val ACTION_STOP = "com.vizvag.shieldvideo.remote.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "start failed: ${it.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
