package com.vizvag.shieldvideo.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Sleep timer presets in minutes (cycle: Off → each → Off). */
val SLEEP_TIMER_MINUTES: IntArray = intArrayOf(15, 30, 45, 60, 90)

/** Fade audio over the last minute before the timer stops playback. */
const val SLEEP_FADE_MS: Long = 60_000L

data class SleepTimerState(
    val presetMinutes: Int = 0,
    val endsAtMs: Long? = null,
    val remainingMs: Long = 0L
) {
    val active: Boolean get() = endsAtMs != null
    val label: String? get() = sleepTimerLabel(endsAtMs, remainingMs)
}

fun sleepTimerLabel(endsAtMs: Long?, remainingMs: Long): String? {
    if (endsAtMs == null) return null
    val totalSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

fun nextSleepPresetMinutes(currentPresetMinutes: Int): Int? {
    val idx = SLEEP_TIMER_MINUTES.indexOf(currentPresetMinutes)
    return if (idx < 0) {
        SLEEP_TIMER_MINUTES.first()
    } else {
        SLEEP_TIMER_MINUTES.getOrNull(idx + 1)
    }
}

fun sleepVolumeForRemaining(remainingMs: Long): Float =
    if (remainingMs >= SLEEP_FADE_MS) {
        1f
    } else {
        (remainingMs.toFloat() / SLEEP_FADE_MS.toFloat()).coerceIn(0f, 1f)
    }

/**
 * Single app-wide sleep timer shared by NAS/VLC, Live TV, Radio, Music, Podcasts,
 * and YouTube so the countdown survives screen changes. The foreground screen may
 * bind volume/stop sinks; [onExpireFallback] always stops every in-app player.
 *
 * After expiry, [onStandby] tries HA power-off. If that fails, [blackout] stays up.
 */
class SleepTimerController(
    private val blackout: BlackoutController,
    private val onApplyVolume: (Float) -> Unit,
    private val onRestoreVolume: () -> Unit,
    private val onExpireFallback: () -> Unit,
    /**
     * Attempt device standby. Invoke [onResult] on the main thread with true when
     * the webhook succeeded, false when the app must stay in blackout.
     */
    private val onStandby: (onResult: (turnedOff: Boolean) -> Unit) -> Unit = { it(false) },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    @Volatile private var stopSink: (() -> Unit)? = null

    private fun applyVolume(volume: Float) {
        onApplyVolume(volume)
    }

    private val expireAtUptime = object : Runnable {
        override fun run() {
            if (_state.value.endsAtMs == null) return
            clearInternal()
            expire()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val endsAt = _state.value.endsAtMs ?: return
            val remaining = endsAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                handler.removeCallbacks(expireAtUptime)
                clearInternal()
                expire()
            } else {
                _state.update { it.copy(remainingMs = remaining) }
                applyVolume(sleepVolumeForRemaining(remaining))
                handler.postDelayed(this, 250L)
            }
        }
    }

    /** Bind stop for the visible in-app player; volume fade is app-wide via [onApplyVolume]. */
    fun bindPlayback(
        onStop: (() -> Unit)? = null
    ) {
        stopSink = onStop
        if (_state.value.active) {
            applyVolume(sleepVolumeForRemaining(_state.value.remainingMs))
        }
    }

    fun unbindPlayback() {
        stopSink = null
    }

    fun cycle() {
        val next = nextSleepPresetMinutes(_state.value.presetMinutes)
        if (next == null) {
            clear()
            onRestoreVolume()
        } else {
            setMinutes(next)
        }
    }

    /** Start (or replace) the timer for an exact duration in minutes (1–999). */
    fun setMinutes(minutes: Int) {
        val mins = minutes.coerceIn(1, 999)
        val endsAt = System.currentTimeMillis() + mins * 60_000L
        handler.removeCallbacks(tick)
        handler.removeCallbacks(expireAtUptime)
        _state.value = SleepTimerState(
            presetMinutes = mins,
            endsAtMs = endsAt,
            remainingMs = mins * 60_000L
        )
        onRestoreVolume()
        handler.post(tick)
        handler.postAtTime(expireAtUptime, SystemClock.uptimeMillis() + mins * 60_000L)
    }

    fun clear() {
        clearInternal()
        onRestoreVolume()
    }

    private fun expire() {
        applyVolume(0f)
        runCatching { stopSink?.invoke() }
        runCatching { onExpireFallback() }
        blackout.enter()
        runCatching {
            onStandby { turnedOff ->
                if (!turnedOff) {
                    blackout.enter()
                }
            }
        }
    }

    private fun clearInternal() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(expireAtUptime)
        _state.value = SleepTimerState()
    }
}
