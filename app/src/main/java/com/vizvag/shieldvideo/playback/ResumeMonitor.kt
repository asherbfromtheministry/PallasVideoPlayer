package com.vizvag.shieldvideo.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vizvag.shieldvideo.data.settings.SettingsRepository

/**
 * Empty listener — enabling it in system settings allows MediaSessionManager
 * to read VLC playback position for resume tiles and HA handoff.
 */
class ShieldNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}

class ResumeMonitor(
    context: Context,
    private val resumeStore: LocalResumeStore,
    private val nowPlayingStore: NowPlayingStore = NowPlayingStore(context),
    private val settingsRepository: SettingsRepository? = null,
    private val haPublisher: HaNowPlayingPublisher = HaNowPlayingPublisher(),
    private val progressSync: NasProgressSync? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activePath: String? = null
    private var activeUri: String? = null
    private var activeShare: String? = null
    private var activeHost: String? = null
    private var activeTitle: String? = null
    private var activePlayerPackage: String = MediaPlayerLauncher.VLC_PACKAGE
    private var missCount = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val path = activePath
            if (path == null) {
                stop()
                return
            }
            val sample = readPlayerPosition(activePlayerPackage)
            if (sample != null) {
                missCount = 0
                persistProgress(path, sample.first, sample.second, forceNas = false)
            } else {
                missCount += 1
                if (missCount >= 8) {
                    flushNas(path)
                    clearNowPlaying()
                    stop()
                    return
                }
            }
            handler.postDelayed(this, 2000L)
        }
    }

    fun start(
        path: String,
        playerPackage: String = MediaPlayerLauncher.VLC_PACKAGE,
        playbackUri: String? = null,
        title: String? = null,
        share: String? = null,
        host: String? = null
    ) {
        resumeStore.setLastLaunched(path)
        activePath = path
        activeUri = playbackUri?.takeIf { it.isNotBlank() } ?: activeUri
        activeShare = share?.takeIf { it.isNotBlank() } ?: activeShare
        activeHost = host?.takeIf { it.isNotBlank() } ?: activeHost
        activeTitle = title?.takeIf { it.isNotBlank() } ?: activeTitle
        activePlayerPackage = playerPackage.ifBlank { MediaPlayerLauncher.VLC_PACKAGE }
        missCount = 0
        if (activeUri != null || path.isNotBlank()) {
            publishNowPlaying(path, 0L, 0L, force = true)
        }
        if (!isNotificationAccessEnabled()) return
        if (running) return
        running = true
        handler.postDelayed(tick, 1500L)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        activePath = null
        missCount = 0
    }

    fun stopPlayer() {
        captureOnce()
        try {
            findPlayerController()?.transportControls?.stop()
        } catch (_: Exception) {
            try {
                findPlayerController()?.transportControls?.pause()
            } catch (_: Exception) {
            }
        }
        clearNowPlaying()
        stop()
    }

    fun captureOnce() {
        val path = resumeStore.lastLaunchedPath() ?: activePath ?: return
        if (!isNotificationAccessEnabled()) return
        val sample = readPlayerPosition(activePlayerPackage) ?: run {
            flushNas(path)
            return
        }
        persistProgress(path, sample.first, sample.second, forceNas = true)
    }

    fun isNotificationAccessEnabled(): Boolean =
        NotificationAccessHelper.isEnabled(appContext)

    private fun persistProgress(
        path: String,
        positionMs: Long,
        durationMs: Long,
        forceNas: Boolean,
    ) {
        resumeStore.save(path, positionMs, durationMs)
        publishNowPlaying(path, positionMs, durationMs, force = forceNas)
        progressSync?.scheduleWrite(
            videoPath = path,
            shareName = activeShare,
            positionMs = positionMs,
            durationMs = durationMs,
            force = forceNas,
        )
    }

    private fun flushNas(path: String) {
        progressSync?.flushLocal(path, activeShare)
    }

    private fun publishNowPlaying(
        path: String,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false
    ) {
        val settings = settingsRepository?.load() ?: return
        val uri = activeUri?.takeIf { it.isNotBlank() }
            ?: return
        val deviceId = settings.deviceId.trim().ifBlank { return }
        val session = NowPlaying(
            deviceId = deviceId,
            uri = uri,
            path = path,
            share = activeShare.orEmpty().ifBlank { settings.defaultShare },
            host = activeHost.orEmpty().ifBlank { settings.host },
            title = activeTitle?.ifBlank { null } ?: path.substringAfterLast('/'),
            positionMs = positionMs,
            durationMs = durationMs
        )
        nowPlayingStore.save(session)
        haPublisher.publish(settings.haWebhookUrl, session, force = force)
    }

    private fun clearNowPlaying() {
        val settings = settingsRepository?.load()
        nowPlayingStore.clear()
        if (settings != null) {
            haPublisher.clear(settings.haWebhookUrl, settings.deviceId)
        }
        activeUri = null
        activeShare = null
        activeHost = null
        activeTitle = null
    }

    private fun findPlayerController(): MediaController? {
        return try {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(appContext, ShieldNotificationListener::class.java)
            val controllers = manager.getActiveSessions(listener)
            controllers.firstOrNull { it.packageName.equals(activePlayerPackage, true) }
                ?: controllers.firstOrNull { it.playbackState != null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readPlayerPosition(preferredPackage: String): Pair<Long, Long>? {
        return try {
            val controller = findPlayerController() ?: return null
            if (!controller.packageName.equals(preferredPackage, true) &&
                preferredPackage.isNotBlank()
            ) {
                // Prefer configured player when present; otherwise use any active session.
            }
            val state = controller.playbackState ?: return null
            if (state.state != PlaybackState.STATE_PLAYING &&
                state.state != PlaybackState.STATE_PAUSED &&
                state.state != PlaybackState.STATE_BUFFERING
            ) {
                return null
            }
            val position = state.position.coerceAtLeast(0L)
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
            if (position <= 0L) return null
            position to duration
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
