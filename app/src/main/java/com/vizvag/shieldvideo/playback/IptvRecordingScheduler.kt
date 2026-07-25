package com.vizvag.shieldvideo.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vizvag.shieldvideo.data.iptv.IptvRecording
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStatus
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore

object IptvRecordingScheduler {
    private const val ACTION_START_SCHEDULED =
        "com.vizvag.shieldvideo.iptv.RECORD_START_SCHEDULED"
    private const val EXTRA_ID = "recording_id"

    fun schedule(context: Context, recording: IptvRecording) {
        if (recording.stopMs <= System.currentTimeMillis()) return
        val startAt = recording.startMs.coerceAtLeast(System.currentTimeMillis())
        if (startAt <= System.currentTimeMillis() + 2_000L) {
            IptvRecordingService.start(context, recording.id)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(context, recording.id)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, operation)
        } else {
            // Still wake the app on devices where exact-alarm access has not been granted.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, operation)
        }
    }

    fun cancel(context: Context, recordingId: String) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, recordingId))
    }

    fun restore(context: Context) {
        IptvRecordingStore(context).list()
            .filter { it.status == IptvRecordingStatus.SCHEDULED }
            .forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, recordingId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            recordingId.hashCode(),
            Intent(context, IptvRecordingAlarmReceiver::class.java).apply {
                action = ACTION_START_SCHEDULED
                putExtra(EXTRA_ID, recordingId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun recordingId(intent: Intent?): String? =
        intent?.takeIf { it.action == ACTION_START_SCHEDULED }?.getStringExtra(EXTRA_ID)
}

class IptvRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> IptvRecordingScheduler.restore(context)
            else -> IptvRecordingScheduler.recordingId(intent)?.let {
                IptvRecordingService.start(context, it)
            }
        }
    }
}
