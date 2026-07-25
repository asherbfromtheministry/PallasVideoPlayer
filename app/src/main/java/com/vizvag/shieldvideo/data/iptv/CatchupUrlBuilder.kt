package com.vizvag.shieldvideo.data.iptv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object CatchupUrlBuilder {

    /**
     * Builds a catch-up / timeshift URL when the playlist provides catchup metadata
     * or when the stream looks like Xtream Codes (`…/user/pass/streamId`).
     */
    fun build(
        channel: IptvChannel,
        programme: IptvProgramme
    ): String? {
        if (!channel.supportsCatchup && channel.catchupSource.isNullOrBlank()) {
            // Still try Xtream-style timeshift when catchup-days unknown but user asks.
            return buildXtreamTimeshift(channel.streamUrl, programme)
                ?.takeIf { channel.catchupDays > 0 }
        }
        val durationMin = ((programme.stopMs - programme.startMs) / 60_000L)
            .coerceIn(1L, 24L * 60L)
            .toInt()
        val source = channel.catchupSource
        if (!source.isNullOrBlank()) {
            return expandTemplate(source, channel, programme, durationMin)
        }
        return buildXtreamTimeshift(channel.streamUrl, programme)
    }

    fun canCatchup(channel: IptvChannel, programme: IptvProgramme, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (programme.stopMs > nowMs) return false
        val ageDays = TimeUnit.MILLISECONDS.toDays(nowMs - programme.startMs)
        val maxDays = channel.catchupDays.takeIf { it > 0 } ?: return channel.supportsCatchup
        return ageDays <= maxDays && build(channel, programme) != null
    }

    private fun expandTemplate(
        template: String,
        channel: IptvChannel,
        programme: IptvProgramme,
        durationMin: Int
    ): String {
        val startLocal = format(programme.startMs, "yyyy-MM-dd:HH-mm")
        val startUtc = formatUtc(programme.startMs, "yyyy-MM-dd:HH-mm")
        val startEpoch = (programme.startMs / 1000L).toString()
        val utcYmd = formatUtc(programme.startMs, "yyyyMMddHHmmss")
        return template
            .replace("\${start}", startLocal)
            .replace("\${utc}", startUtc)
            .replace("\${timestamp}", startEpoch)
            .replace("\${offset}", "0")
            .replace("\${duration}", durationMin.toString())
            .replace("\${start_iso}", formatUtc(programme.startMs, "yyyy-MM-dd'T'HH:mm:ss'Z'"))
            .replace("\${utc:yymmddhhmmss}", utcYmd)
            .replace("{utc}", startUtc)
            .replace("{duration}", durationMin.toString())
            .replace("{start}", startLocal)
            .let { url ->
                if (url.contains("{stream_id}") || url.contains("\${stream_id}")) {
                    val streamId = channel.streamUrl.trimEnd('/').substringAfterLast('/')
                    url.replace("{stream_id}", streamId).replace("\${stream_id}", streamId)
                } else url
            }
    }

    private fun buildXtreamTimeshift(streamUrl: String, programme: IptvProgramme): String? {
        val parts = streamUrl.trimEnd('/').split('/')
        if (parts.size < 4) return null
        val streamId = parts.last().substringBefore('?')
        if (streamId.toLongOrNull() == null && !streamId.all { it.isDigit() }) return null
        val password = parts.getOrNull(parts.size - 2) ?: return null
        val username = parts.getOrNull(parts.size - 3) ?: return null
        val base = parts.dropLast(3).joinToString("/")
        if (!base.startsWith("http")) return null
        val durationMin = ((programme.stopMs - programme.startMs) / 60_000L)
            .coerceIn(1L, 24L * 60L)
        val start = format(programme.startMs, "yyyy-MM-dd:HH-mm")
        // Common Xtream timeshift form
        return "$base/streaming/timeshift.php?username=$username&password=$password" +
            "&stream=$streamId&start=$start&duration=$durationMin"
    }

    private fun format(ms: Long, pattern: String): String {
        val sdf = SimpleDateFormat(pattern, Locale.US)
        return sdf.format(Date(ms))
    }

    private fun formatUtc(ms: Long, pattern: String): String {
        val sdf = SimpleDateFormat(pattern, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}
