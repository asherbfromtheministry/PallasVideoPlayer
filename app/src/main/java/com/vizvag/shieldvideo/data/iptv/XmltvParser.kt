package com.vizvag.shieldvideo.data.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming XMLTV parser. Only keeps programmes for [wantedChannelIds] within
 * [windowStartMs, windowEndMs] to keep memory bounded on Android TV.
 */
object XmltvParser {

    /**
     * Lightweight pass over `<channel id="…"><display-name>…` for the assign-EPG picker.
     * Stops once programmes begin (XMLTV usually lists channels first).
     */
    fun parseChannelList(input: InputStream, maxChannels: Int = 20_000): List<EpgChannelEntry> {
        val out = ArrayList<EpgChannelEntry>(4096)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < maxChannels) {
            if (event == XmlPullParser.START_TAG) {
                when {
                    parser.name.equals("programme", true) -> break
                    parser.name.equals("channel", true) -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty().trim()
                        var displayName = ""
                        var depth = 1
                        while (depth > 0) {
                            event = parser.next()
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    depth++
                                    if (parser.name.equals("display-name", true) && displayName.isBlank()) {
                                        displayName = parser.nextText().trim()
                                        depth--
                                    }
                                }
                                XmlPullParser.END_TAG -> depth--
                                XmlPullParser.END_DOCUMENT -> depth = 0
                            }
                        }
                        if (id.isNotBlank()) {
                            out += EpgChannelEntry(
                                id = id,
                                name = displayName.ifBlank { id }
                            )
                        }
                        event = parser.eventType
                        continue
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedBy { it.name.lowercase(Locale.US) }
    }

    fun parse(
        input: InputStream,
        wantedChannelIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        maxProgrammes: Int = 80_000
    ): Map<String, List<IptvProgramme>> {
        if (wantedChannelIds.isEmpty()) return emptyMap()
        val wantedLower = wantedChannelIds.map { it.lowercase(Locale.US) }.toSet()
        val byChannel = HashMap<String, MutableList<IptvProgramme>>()
        var total = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && total < maxProgrammes) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("programme", true)) {
                val channelRaw = parser.getAttributeValue(null, "channel").orEmpty()
                val channelKey = channelRaw.lowercase(Locale.US)
                if (channelKey in wantedLower) {
                    val startMs = parseXmltvTime(parser.getAttributeValue(null, "start"))
                    val stopMs = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                    if (startMs != null && stopMs != null &&
                        stopMs >= windowStartMs && startMs <= windowEndMs
                    ) {
                        var title = ""
                        var description: String? = null
                        var depth = 1
                        while (depth > 0) {
                            event = parser.next()
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    depth++
                                    when {
                                        parser.name.equals("title", true) -> {
                                            title = parser.nextText().trim()
                                            depth--
                                        }
                                        parser.name.equals("desc", true) -> {
                                            description = parser.nextText().trim().ifBlank { null }
                                            depth--
                                        }
                                    }
                                }
                                XmlPullParser.END_TAG -> depth--
                                XmlPullParser.END_DOCUMENT -> depth = 0
                            }
                        }
                        if (title.isNotBlank()) {
                            val list = byChannel.getOrPut(channelRaw) { ArrayList() }
                            list += IptvProgramme(
                                channelId = channelRaw,
                                startMs = startMs,
                                stopMs = stopMs,
                                title = title,
                                description = description
                            )
                            total++
                        }
                        event = parser.eventType
                        continue
                    }
                }
            }
            event = parser.next()
        }

        return byChannel.mapValues { (_, list) -> list.sortedBy { it.startMs } }
    }

    fun nowNext(
        programmes: List<IptvProgramme>,
        nowMs: Long = System.currentTimeMillis()
    ): IptvNowNext {
        val now = programmes.firstOrNull { it.isAiringAt(nowMs) }
        val next = programmes.firstOrNull { it.startMs > nowMs }
            ?: programmes.firstOrNull { now == null || it.startMs >= (now.stopMs) }
        return IptvNowNext(now = now, next = next?.takeIf { it !== now })
    }

    /** Programmes overlapping `[windowStartMs, windowStartMs + windowMs)`. */
    fun inWindow(
        programmes: List<IptvProgramme>,
        windowStartMs: Long,
        windowMs: Long
    ): List<IptvProgramme> {
        val windowEnd = windowStartMs + windowMs
        return programmes.filter { it.stopMs > windowStartMs && it.startMs < windowEnd }
            .sortedBy { it.startMs }
    }

    private fun parseXmltvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        // 20260716180000 +0000  or 20260716180000
        val patterns = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmssZ",
            "yyyyMMddHHmmss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (!pattern.contains('Z') && !pattern.contains(' ')) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                val tzPart = if (cleaned.length > 14) cleaned.substring(14) else ""
                val normalized = when {
                    cleaned.length >= 19 && cleaned[14] == ' ' -> cleaned
                    cleaned.length > 14 && cleaned[14] != ' ' &&
                        '+' !in tzPart && !tzPart.startsWith("-") -> {
                        cleaned.substring(0, 14) + " " + tzPart.replace("UTC", "+0000")
                    }
                    else -> cleaned
                }
                val parsed = runCatching { sdf.parse(normalized) }.getOrNull()
                    ?: runCatching {
                        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(
                            if (cleaned.length >= 14) {
                                val body = cleaned.take(14)
                                val tz = cleaned.drop(14).trim().ifBlank { "+0000" }
                                    .replace("UTC", "+0000")
                                "$body $tz"
                            } else cleaned
                        )
                    }.getOrNull()
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // try next
            }
        }
        // Fallback: first 14 digits as UTC
        val digits = cleaned.filter { it.isDigit() }.take(14)
        if (digits.length == 14) {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return runCatching { sdf.parse(digits)?.time }.getOrNull()
        }
        return null
    }
}
