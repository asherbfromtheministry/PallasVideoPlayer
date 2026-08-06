package com.vizvag.shieldvideo.playback.remote

import android.content.Context
import android.os.Build
import com.vizvag.shieldvideo.ShieldVideoApp
import java.util.UUID

/**
 * Stable identity this device presents when controlling a room TV.
 * Display name prefers Settings → device id, else the Android model.
 */
object RemoteControllerIdentity {
    private const val PREFS = "pallas_remote_controller"
    private const val KEY_ID = "controller_id"

    const val HEADER_ID = "X-Pallas-Controller-Id"
    const val HEADER_NAME = "X-Pallas-Controller-Name"

    fun id(): String {
        val prefs = ShieldVideoApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    fun displayName(): String {
        val settings = ShieldVideoApp.instance.settingsRepository.load()
        return settings.deviceId.trim()
            .ifBlank { Build.MODEL.trim() }
            .ifBlank { "Remote" }
            .take(40)
    }
}
