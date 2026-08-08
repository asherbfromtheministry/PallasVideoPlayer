package com.vizvag.shieldvideo.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Copy-remux into MP4 via [MediaMuxer]. Call only from the isolated `:mp4remux` process —
 * truncated IPTV `.ts` files can make [MPEG4Writer] abort the whole process (FORTIFY write).
 */
object Mp4RemuxHelper {
    private const val BUFFER_BYTES = 4 * 1024 * 1024

    enum class Mode { AUDIO_VIDEO, AUDIO_ONLY }

    fun remux(source: File, output: File, mode: Mode = Mode.AUDIO_VIDEO) {
        require(source.exists() && source.length() > 0L) { "Empty source" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.US)
                val include = when (mode) {
                    Mode.AUDIO_ONLY -> mime.startsWith("audio/")
                    Mode.AUDIO_VIDEO -> isMp4CompatibleMime(mime)
                }
                if (!include) continue
                trackMap[index] = muxer.addTrack(format)
                extractor.selectTrack(index)
            }
            check(trackMap.isNotEmpty()) { "No MP4-compatible tracks" }

            // Dry-run: truncated captures often expose zero/invalid samples that crash MPEG4Writer.
            val probe = probeReadableSamples(source, trackMap.keys, mode)
            check(probe.validSamples > 0) {
                "No readable samples (capture too short or truncated)"
            }

            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var firstPts = -1L
            var written = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                // Zero / oversized samples are what trigger FORTIFY write(count=-1) aborts.
                if (size == 0 || size > buffer.capacity()) {
                    extractor.advance()
                    continue
                }
                val outputTrack = trackMap[extractor.sampleTrackIndex]
                if (outputTrack != null) {
                    val pts = extractor.sampleTime
                    if (firstPts < 0L && pts >= 0L) firstPts = pts
                    val outPts =
                        if (pts >= 0L && firstPts >= 0L) (pts - firstPts).coerceAtLeast(0L) else 0L
                    info.set(0, size, outPts, extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    written++
                }
                extractor.advance()
            }
            check(written > 0) { "Wrote zero samples" }
        } finally {
            extractor.release()
            val m = muxer
            if (m != null) {
                if (started) runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
        check(output.exists() && output.length() > 0L) { "Empty MP4 after remux" }
    }

    private data class Probe(val validSamples: Int)

    private fun probeReadableSamples(
        source: File,
        wantedTracks: Set<Int>,
        mode: Mode
    ): Probe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            for (index in wantedTracks) {
                runCatching { extractor.selectTrack(index) }
            }
            // If caller already filtered, still select compatible tracks for a fresh probe.
            if (wantedTracks.isEmpty()) {
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.US)
                    val include = when (mode) {
                        Mode.AUDIO_ONLY -> mime.startsWith("audio/")
                        Mode.AUDIO_VIDEO -> isMp4CompatibleMime(mime)
                    }
                    if (include) extractor.selectTrack(index)
                }
            }
            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            var valid = 0
            var scanned = 0
            while (scanned < 64 && valid < 8) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                scanned++
                if (size in 1..buffer.capacity() && extractor.sampleTrackIndex >= 0) {
                    valid++
                }
                extractor.advance()
            }
            Probe(valid)
        } finally {
            extractor.release()
        }
    }

    private fun isMp4CompatibleMime(mime: String): Boolean =
        mime == "video/avc" ||
            mime == "video/hevc" ||
            mime == "video/mp4v-es" ||
            mime == "audio/mp4a-latm" ||
            mime == "audio/mpeg" ||
            mime == "audio/aac"
}
