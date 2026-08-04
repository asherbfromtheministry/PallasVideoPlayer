package com.vizvag.shieldvideo.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Passthrough PCM tap for Music ExoPlayer — RMS level + bass envelope for Hue sync.
 * Does not require RECORD_AUDIO (unlike [android.media.audiofx.Visualizer]).
 */
class MusicEnergyProbe : BaseAudioProcessor() {
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    var bass: Float = 0f
        private set

    /** Rising-edge strength 0..1 when a beat-like bass hit is detected. */
    @Volatile
    var beat: Float = 0f
        private set

    private var sampleRateHz: Int = 44_100
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT

    private var smoothLevel = 0f
    private var smoothBass = 0f
    private var prevBass = 0f
    private var lowPass = 0.0

    fun asProcessor(): AudioProcessor = this

    fun resetLevels() {
        level = 0f
        bass = 0f
        beat = 0f
        smoothLevel = 0f
        smoothBass = 0f
        prevBass = 0f
        lowPass = 0.0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRateHz = inputAudioFormat.sampleRate.coerceAtLeast(1)
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        lowPass = 0.0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val remaining = inputBuffer.remaining()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> analyzePcm16(inputBuffer.asReadOnlyBuffer())
            C.ENCODING_PCM_FLOAT -> analyzePcmFloat(inputBuffer.asReadOnlyBuffer())
            else -> Unit
        }
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    private fun analyzePcm16(buffer: ByteBuffer) {
        val view = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var bassSq = 0.0
        var n = 0
        val alpha = (120.0 * 2.0 * Math.PI / sampleRateHz).coerceIn(0.01, 0.5)
        while (view.remaining() >= 2) {
            var mixed = 0.0
            repeat(channelCount.coerceAtMost(view.remaining() / 2)) {
                if (view.remaining() < 2) return@repeat
                mixed += view.short / 32768.0
            }
            mixed /= channelCount.coerceAtLeast(1)
            sumSq += mixed * mixed
            lowPass += alpha * (mixed - lowPass)
            bassSq += lowPass * lowPass
            n++
            if (n >= 4096) break
        }
        if (n == 0) return
        publish(sqrt(sumSq / n).toFloat(), sqrt(bassSq / n).toFloat())
    }

    private fun analyzePcmFloat(buffer: ByteBuffer) {
        val view = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var bassSq = 0.0
        var n = 0
        val alpha = (120.0 * 2.0 * Math.PI / sampleRateHz).coerceIn(0.01, 0.5)
        while (view.remaining() >= 4) {
            var mixed = 0.0
            repeat(channelCount.coerceAtMost(view.remaining() / 4)) {
                if (view.remaining() < 4) return@repeat
                mixed += view.float.toDouble()
            }
            mixed /= channelCount.coerceAtLeast(1)
            sumSq += mixed * mixed
            lowPass += alpha * (mixed - lowPass)
            bassSq += lowPass * lowPass
            n++
            if (n >= 4096) break
        }
        if (n == 0) return
        publish(sqrt(sumSq / n).toFloat(), sqrt(bassSq / n).toFloat())
    }

    private fun publish(rms: Float, bassRms: Float) {
        val boosted = (rms * 4f).coerceIn(0f, 1f)
        val bassBoosted = (bassRms * 6f).coerceIn(0f, 1f)
        smoothLevel = smoothLevel * 0.65f + boosted * 0.35f
        smoothBass = smoothBass * 0.7f + bassBoosted * 0.3f
        val rise = (smoothBass - prevBass).coerceAtLeast(0f)
        prevBass = smoothBass
        beat = (rise * 8f).coerceIn(0f, 1f)
        level = smoothLevel
        bass = smoothBass
    }
}
