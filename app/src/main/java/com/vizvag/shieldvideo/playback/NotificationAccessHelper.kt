package com.vizvag.shieldvideo.playback

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object NotificationAccessHelper {
    fun openSettings(context: Context): Boolean {
        val attempts = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"),
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: ActivityNotFoundException) {
                // try next
            } catch (_: Exception) {
                // try next
            }
        }

        Toast.makeText(
            context,
            "Open Settings → Apps → Special app access → Notification access → Shield Video",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    fun isEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(context, ShieldNotificationListener::class.java)
        return flat.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry) ?: return@any false
            cn.packageName == expected.packageName && cn.className == expected.className
        }
    }
}
