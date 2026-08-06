package com.vizvag.shieldvideo.data.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object RssPodcastParser {
    const val MAX_EPISODES = 100

    data class ParsedFeed(
        val title: String = "",
        val imageUrl: String = "",
        val siteUrl: String = "",
        val genres: List<String> = emptyList(),
        val episodes: List<PodcastEpisode> = emptyList(),
    )

    fun parse(xml: String, showId: String, fallbackImageUrl: String = ""): ParsedFeed {
        if (xml.isBlank()) return ParsedFeed()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

        var channelTitle = ""
        var channelImage = fallbackImageUrl
        var channelLink = ""
        val channelGenres = linkedSetOf<String>()
        val episodes = mutableListOf<PodcastEpisode>()

        var inChannel = false
        var inItem = false
        var inImage = false
        var inItunesImage = false

        var itemTitle = ""
        var itemDesc = ""
        var itemGuid = ""
        var itemLink = ""
        var itemPubDate = ""
        var itemEnclosure = ""
        var itemDuration = ""
        var itemImage = ""
        var textBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.getLocal()
                    textBuf = StringBuilder()
                    when {
                        name.equals("channel", true) -> inChannel = true
                        name.equals("item", true) || name.equals("entry", true) -> {
                            inItem = true
                            itemTitle = ""
                            itemDesc = ""
                            itemGuid = ""
                            itemLink = ""
                            itemPubDate = ""
                            itemEnclosure = ""
                            itemDuration = ""
                            itemImage = ""
                        }
                        inItem && name.equals("enclosure", true) -> {
                            val url = parser.attr("url")
                            val type = parser.attr("type")
                            if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio") ||
                                    url.contains(".mp3", true) || url.contains(".m4a", true) ||
                                    url.contains(".aac", true) || url.contains(".ogg", true))
                            ) {
                                itemEnclosure = url
                            }
                        }
                        inItem && name.equals("content", true) && parser.attr("type").contains("audio") -> {
                            parser.attr("url").takeIf { it.isNotBlank() }?.let { itemEnclosure = it }
                        }
                        inItem && name.equals("link", true) -> {
                            val rel = parser.attr("rel")
                            val href = parser.attr("href")
                            if (rel.equals("enclosure", true) && href.isNotBlank()) {
                                itemEnclosure = href
                            }
                        }
                        !inItem && inChannel && name.equals("image", true) -> inImage = true
                        name.equals("image", true) && parser.namespaceContainsItunes() -> {
                            val href = parser.attr("href")
                            if (href.isNotBlank()) {
                                if (inItem) itemImage = href else if (channelImage.isBlank()) channelImage = href
                            }
                            inItunesImage = true
                        }
                        !inItem && inChannel && name.equals("category", true) -> {
                            val textAttr = parser.attr("text")
                            if (textAttr.isNotBlank()) channelGenres += textAttr
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    textBuf.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.getLocal()
                    val text = textBuf.toString().trim()
                    when {
                        name.equals("channel", true) -> inChannel = false
                        name.equals("image", true) -> {
                            inImage = false
                            inItunesImage = false
                        }
                        inImage && name.equals("url", true) && text.isNotBlank() && channelImage.isBlank() -> {
                            channelImage = text
                        }
                        !inItem && inChannel && name.equals("title", true) && channelTitle.isBlank() -> {
                            channelTitle = text
                        }
                        !inItem && inChannel && name.equals("link", true) && channelLink.isBlank() && text.isNotBlank() -> {
                            channelLink = text
                        }
                        !inItem && inChannel && name.equals("category", true) && text.isNotBlank() -> {
                            channelGenres += text
                        }
                        inItem && name.equals("title", true) -> itemTitle = text
                        inItem && (name.equals("description", true) || name.equals("summary", true) ||
                            name.equals("subtitle", true)) && itemDesc.isBlank() -> itemDesc = text
                        inItem && name.equals("guid", true) -> itemGuid = text
                        inItem && name.equals("id", true) && itemGuid.isBlank() -> itemGuid = text
                        inItem && name.equals("link", true) && text.isNotBlank() && itemLink.isBlank() -> {
                            itemLink = text
                        }
                        inItem && (name.equals("pubDate", true) || name.equals("published", true) ||
                            name.equals("updated", true)) && itemPubDate.isBlank() -> itemPubDate = text
                        inItem && name.equals("duration", true) -> itemDuration = text
                        (name.equals("item", true) || name.equals("entry", true)) && inItem -> {
                            inItem = false
                            if (itemEnclosure.isNotBlank() && episodes.size < MAX_EPISODES) {
                                val guid = itemGuid.ifBlank { itemEnclosure }.ifBlank {
                                    UUID.nameUUIDFromBytes(
                                        "${showId}|${itemTitle}|${itemPubDate}".toByteArray()
                                    ).toString()
                                }
                                episodes.add(
                                    PodcastEpisode(
                                        guid = guid,
                                        showId = showId,
                                        title = itemTitle.ifBlank { "Episode" },
                                        description = itemDesc,
                                        audioUrl = itemEnclosure,
                                        publishEpochMs = parseDate(itemPubDate),
                                        durationSec = parseDuration(itemDuration),
                                        imageUrl = itemImage.ifBlank { channelImage },
                                    )
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        return ParsedFeed(
            title = channelTitle,
            imageUrl = channelImage.ifBlank { fallbackImageUrl },
            siteUrl = channelLink,
            genres = channelGenres.toList(),
            episodes = episodes,
        )
    }

    private fun XmlPullParser.getLocal(): String =
        name?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: name.orEmpty()

    private fun XmlPullParser.attr(name: String): String {
        for (i in 0 until attributeCount) {
            val n = getAttributeName(i)?.substringAfterLast(':').orEmpty()
            if (n.equals(name, ignoreCase = true)) {
                return getAttributeValue(i)?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun XmlPullParser.namespaceContainsItunes(): Boolean {
        val ns = namespace.orEmpty()
        if (ns.contains("itunes", ignoreCase = true)) return true
        for (i in 0 until attributeCount) {
            if (getAttributeNamespace(i).orEmpty().contains("itunes", ignoreCase = true)) return true
        }
        return false
    }

    private fun parseDuration(raw: String): Long {
        val t = raw.trim()
        if (t.isBlank()) return 0L
        t.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        val parts = t.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }.coerceAtLeast(0L)
    }

    private fun parseDate(raw: String): Long {
        val t = raw.trim()
        if (t.isBlank()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd",
        )
        for (p in patterns) {
            val ms = runCatching {
                SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = true
                }.parse(t)?.time
            }.getOrNull()
            if (ms != null && ms > 0L) return ms
        }
        return 0L
    }
}
