package com.vizvag.shieldvideo.data.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.UUID

object OpmlParser {
    fun parse(xml: String): List<PodcastShow> {
        if (xml.isBlank()) return emptyList()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
        val shows = LinkedHashMap<String, PodcastShow>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", ignoreCase = true)) {
                val type = parser.attr("type")
                val feedUrl = parser.attr("xmlUrl").ifBlank { parser.attr("xmlurl") }
                if (feedUrl.isNotBlank() && (type.isBlank() || type.equals("rss", ignoreCase = true))) {
                    val title = parser.attr("text")
                        .ifBlank { parser.attr("title") }
                        .ifBlank { feedUrl }
                    val key = feedUrl.trim().lowercase()
                    if (!shows.containsKey(key)) {
                        shows[key] = PodcastShow(
                            id = UUID.nameUUIDFromBytes(key.toByteArray()).toString(),
                            title = title.trim(),
                            feedUrl = feedUrl.trim(),
                            siteUrl = parser.attr("htmlUrl").ifBlank { parser.attr("htmlurl") },
                            imageUrl = parser.attr("imageUrl").ifBlank { parser.attr("imageurl") },
                        )
                    }
                }
            }
            event = parser.next()
        }
        return shows.values.toList()
    }

    private fun XmlPullParser.attr(name: String): String {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i).equals(name, ignoreCase = true)) {
                return getAttributeValue(i)?.trim().orEmpty()
            }
        }
        return ""
    }
}
