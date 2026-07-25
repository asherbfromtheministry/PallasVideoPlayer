package com.vizvag.shieldvideo.ui.theme

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Soft TV UI audio — one cue per action (no stacked click+beep). */
class TvFeedback(
    private val view: View,
    private val toneGenerator: ToneGenerator?,
) {
    fun focus() {
        val now = SystemClock.uptimeMillis()
        // Skip focus chirp right after a click (list refresh re-focuses) or rapid re-focus.
        if (now - lastClickAtMs < 220L || now - lastFocusAtMs < 140L) return
        lastFocusAtMs = now
        view.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN)
    }

    fun click() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickAtMs < 90L) return
        lastClickAtMs = now
        lastFocusAtMs = now
        // Single sound only — soft tone when available, else system click.
        val playedTone = try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 28) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!playedTone) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    companion object {
        @Volatile
        private var lastFocusAtMs: Long = 0L

        @Volatile
        private var lastClickAtMs: Long = 0L
    }
}

@Composable
fun rememberTvFeedback(): TvFeedback {
    val view = LocalView.current
    val tone = remember {
        try {
            ToneGenerator(AudioManager.STREAM_SYSTEM, 18)
        } catch (_: RuntimeException) {
            null
        }
    }
    DisposableEffect(tone) {
        onDispose {
            try {
                tone?.release()
            } catch (_: RuntimeException) {
                // ignore
            }
        }
    }
    return remember(view, tone) { TvFeedback(view, tone) }
}
