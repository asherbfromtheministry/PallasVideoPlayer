package com.vizvag.shieldvideo.playback

import android.os.Handler
import android.os.Looper
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
 * Single app-wide sleep timer shared by NAS/VLC, Live TV, and Radio so the
 * countdown survives screen changes. The foreground screen binds volume/stop
 * sinks; [onExpireFallback] always runs (stops external VLC via ResumeMonitor).
 */
class SleepTimerController(
    private val onExpireFallback: () -> Unit,
    private val onStandby: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    @Volatile private var volumeSink: ((Float) -> Unit)? = null
    @Volatile private var stopSink: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val endsAt = _state.value.endsAtMs ?: return
            val remaining = endsAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                clearInternal()
                expire()
            } else {
                _state.update { it.copy(remainingMs = remaining) }
                volumeSink?.invoke(sleepVolumeForRemaining(remaining))
                handler.postDelayed(this, 250L)
            }
        }
    }

    /** Bind the currently visible in-app player; timer keeps running if unbound. */
    fun bindPlayback(
        onVolume: ((Float) -> Unit)? = null,
        onStop: (() -> Unit)? = null
    ) {
        volumeSink = onVolume
        stopSink = onStop
        if (_state.value.active) {
            volumeSink?.invoke(sleepVolumeForRemaining(_state.value.remainingMs))
        }
    }

    fun unbindPlayback() {
        volumeSink = null
        stopSink = null
    }

    fun cycle() {
        val next = nextSleepPresetMinutes(_state.value.presetMinutes)
        if (next == null) {
            clear()
            volumeSink?.invoke(1f)
        } else {
            val endsAt = System.currentTimeMillis() + next * 60_000L
            handler.removeCallbacks(tick)
            _state.value = SleepTimerState(
                presetMinutes = next,
                endsAtMs = endsAt,
                remainingMs = next * 60_000L
            )
            volumeSink?.invoke(1f)
            handler.post(tick)
        }
    }

    fun clear() {
        clearInternal()
    }

    private fun expire() {
        volumeSink?.invoke(0f)
        runCatching { stopSink?.invoke() }
        runCatching { onExpireFallback() }
        runCatching { onStandby() }
        volumeSink?.invoke(1f)
    }

    private fun clearInternal() {
        handler.removeCallbacks(tick)
        _state.value = SleepTimerState()
    }
}
