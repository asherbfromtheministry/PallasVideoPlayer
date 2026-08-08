package com.vizvag.shieldvideo.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast

/**
 * Resume tracking needs [ShieldNotificationListener] in
 * `Settings.Secure.enabled_notification_listeners`.
 *
 * Device differences matter:
 * - **Chromecast / some Google TV**: [Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS]
 *   opens `NotificationAccessActivity` — user toggles PallasVideoPlayer on.
 * - **NVIDIA Shield**: that intent does not resolve; older fallbacks opened unrelated
 *   Settings. Prefer enabling via WRITE_SECURE_SETTINGS (ADB sideload builds).
 */
object NotificationAccessHelper {
    sealed class EnableResult {
        data object AlreadyOn : EnableResult()
        data object Enabled : EnableResult()
        /** Opened the device’s Notification access list (e.g. Chromecast). */
        data object OpenedSettingsUi : EnableResult()
        data object Unavailable : EnableResult()
    }

    fun isEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = listenerComponent(context)
        return flat.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry) ?: return@any false
            cn.packageName == expected.packageName && cn.className == expected.className
        }
    }

    /** True when the OS exposes a Notification access settings activity (Chromecast yes, Shield no). */
    fun hasListenerSettingsUi(context: Context): Boolean =
        listenerSettingsIntents(context).any { intent ->
            intent.resolveActivity(context.packageManager) != null
        }

    /**
     * Turn on resume tracking.
     * 1) Already on → rebind
     * 2) WRITE_SECURE_SETTINGS → enable silently (sideload / ADB)
     * 3) Else open system Notification access UI when the device has one (Chromecast)
     * 4) Else explain Shield has no such screen
     */
    fun enable(context: Context): EnableResult {
        if (isEnabled(context)) {
            requestRebind(context)
            return EnableResult.AlreadyOn
        }
        if (enableViaSecureSettings(context)) {
            requestRebind(context)
            Toast.makeText(
                context,
                "Resume tracking on — Continue Watching can follow VLC",
                Toast.LENGTH_LONG
            ).show()
            return EnableResult.Enabled
        }
        if (openListenerSettingsUi(context)) {
            Toast.makeText(
                context,
                "Find PallasVideoPlayer and turn it ON",
                Toast.LENGTH_LONG
            ).show()
            return EnableResult.OpenedSettingsUi
        }
        Toast.makeText(
            context,
            "This TV has no Notification Access screen. Use an ADB build, or: adb shell cmd notification allow_listener ${listenerComponent(context).flattenToString()}",
            Toast.LENGTH_LONG
        ).show()
        return EnableResult.Unavailable
    }

    /** @deprecated Use [enable]. */
    fun openSettings(context: Context): Boolean =
        when (enable(context)) {
            EnableResult.AlreadyOn,
            EnableResult.Enabled,
            EnableResult.OpenedSettingsUi -> true
            EnableResult.Unavailable -> false
        }

    private fun enableViaSecureSettings(context: Context): Boolean {
        val expected = listenerComponent(context).flattenToString()
        return try {
            val cr = context.contentResolver
            val flat = Settings.Secure.getString(cr, "enabled_notification_listeners").orEmpty()
            val entries = flat.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val already = entries.any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.flattenToString().equals(expected, ignoreCase = true) ||
                    (cn.packageName == context.packageName &&
                        cn.className.endsWith("ShieldNotificationListener"))
            }
            if (!already) {
                entries += expected
                Settings.Secure.putString(
                    cr,
                    "enabled_notification_listeners",
                    entries.joinToString(":")
                )
            }
            isEnabled(context)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun listenerSettingsIntents(context: Context): List<Intent> {
        val component = listenerComponent(context)
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            component.flattenToString()
                        )
                    }
                )
            }
            // Chromecast / Google TV register this exact action string.
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    private fun openListenerSettingsUi(context: Context): Boolean {
        for (intent in listenerSettingsIntents(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // try next — never fall through to DND / app-info / general Settings
            }
        }
        return false
    }

    private fun requestRebind(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            NotificationListenerService.requestRebind(listenerComponent(context))
        } catch (_: Exception) {
        }
    }

    private fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, ShieldNotificationListener::class.java)
}
