package com.vizvag.shieldvideo.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs [Mp4RemuxHelper] in process `:mp4remux` so a native MPEG4Writer abort
 * cannot kill the main app / recording UI.
 *
 * Contract: writes [EXTRA_DONE] with `ok` or `fail:<message>` when finished
 * (or the process dies with no done file — caller treats that as failure).
 */
class Mp4RemuxProcessService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent?.getStringExtra(EXTRA_INPUT)?.let(::File)
        val output = intent?.getStringExtra(EXTRA_OUTPUT)?.let(::File)
        val done = intent?.getStringExtra(EXTRA_DONE)?.let(::File)
        val modeName = intent?.getStringExtra(EXTRA_MODE) ?: Mp4RemuxHelper.Mode.AUDIO_VIDEO.name
        if (input == null || output == null || done == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        thread(name = "mp4-remux") {
            try {
                done.delete()
                output.delete()
                val mode = runCatching { Mp4RemuxHelper.Mode.valueOf(modeName) }
                    .getOrDefault(Mp4RemuxHelper.Mode.AUDIO_VIDEO)
                Mp4RemuxHelper.remux(input, output, mode)
                done.writeText("ok")
            } catch (t: Throwable) {
                Log.w(TAG, "Remux failed", t)
                runCatching {
                    output.delete()
                    done.writeText("fail:${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "Mp4RemuxProcess"
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_DONE = "done"
        const val EXTRA_MODE = "mode"

        fun start(
            context: Context,
            input: File,
            output: File,
            done: File,
            mode: Mp4RemuxHelper.Mode
        ) {
            context.startService(
                Intent(context, Mp4RemuxProcessService::class.java).apply {
                    putExtra(EXTRA_INPUT, input.absolutePath)
                    putExtra(EXTRA_OUTPUT, output.absolutePath)
                    putExtra(EXTRA_DONE, done.absolutePath)
                    putExtra(EXTRA_MODE, mode.name)
                }
            )
        }
    }
}
