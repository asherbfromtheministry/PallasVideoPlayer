package com.vizvag.shieldvideo.playback

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invokes [Mp4RemuxProcessService] and waits for a done marker.
 * If the remux process is aborted by native code, we observe a missing/failed
 * marker and return false — the caller can fall back to Transformer.
 */
object IsolatedMp4Remux {
    private const val TAG = "IsolatedMp4Remux"
    private const val REMUX_PROCESS_SUFFIX = ":mp4remux"
    private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    private const val POLL_MS = 200L
    /** Allow cold-start of the remux process before treating "not alive" as failure. */
    private const val START_GRACE_POLLS = 40 // ~8s

    suspend fun remux(
        context: Context,
        source: File,
        output: File,
        mode: Mp4RemuxHelper.Mode = Mp4RemuxHelper.Mode.AUDIO_VIDEO,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        if (!source.exists() || source.length() == 0L) return false
        val done = File(output.parentFile, "${output.name}.done")
        done.delete()
        output.delete()

        Mp4RemuxProcessService.start(context, source, output, done, mode)

        val app = context.applicationContext
        val result = withTimeoutOrNull(timeoutMs) {
            var sawRemuxProcess = false
            var notAlivePolls = 0
            while (true) {
                if (done.exists()) {
                    val text = runCatching { done.readText().trim() }.getOrDefault("")
                    done.delete()
                    return@withTimeoutOrNull text == "ok" &&
                        output.exists() &&
                        output.length() > 0L
                }
                val alive = isRemuxProcessAlive(app)
                if (alive) {
                    sawRemuxProcess = true
                    notAlivePolls = 0
                } else if (sawRemuxProcess) {
                    // Process started then vanished without writing done → native abort.
                    Log.w(TAG, "Remux process died without result (likely MPEG4Writer abort)")
                    return@withTimeoutOrNull false
                } else {
                    notAlivePolls++
                    if (notAlivePolls > START_GRACE_POLLS) {
                        Log.w(TAG, "Remux process never appeared")
                        return@withTimeoutOrNull false
                    }
                }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

        if (result != true) {
            done.delete()
            output.delete()
            Log.w(TAG, "Isolated remux unsuccessful for ${source.name} (${source.length()} bytes)")
        }
        return result == true
    }

    private fun isRemuxProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val packageName = context.packageName
        val target = "$packageName$REMUX_PROCESS_SUFFIX"
        @Suppress("DEPRECATION")
        return am.runningAppProcesses.orEmpty().any { it.processName == target }
    }
}
