package com.vizvag.shieldvideo.data.iptv

import java.security.MessageDigest

object M3uParser {

    fun parse(text: String): List<IptvChannel> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val channels = ArrayList<IptvChannel>(lines.size / 2)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("#EXTINF", ignoreCase = true)) {
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size && lines[j].startsWith("#") && !lines[j].startsWith("#EXTINF", ignoreCase = true)) {
                j++
            }
            if (j >= lines.size) break
            val url = lines[j]
            if (url.startsWith("#")) {
                i = j
                continue
            }
            channels += parseExtInf(line, url)
            i = j + 1
        }
        return channels
    }

    private fun parseExtInf(extInf: String, streamUrl: String): IptvChannel {
        val comma = extInf.lastIndexOf(',')
        val name = if (comma >= 0) extInf.substring(comma + 1).trim() else "Channel"
        val attrsPart = if (comma >= 0) extInf.substring(0, comma) else extInf
        val tvgId = attr(attrsPart, "tvg-id")?.ifBlank { null }
        val tvgName = attr(attrsPart, "tvg-name")?.ifBlank { null }
        val logo = attr(attrsPart, "tvg-logo")?.ifBlank { null }
        val group = attr(attrsPart, "group-title")?.ifBlank { null } ?: "Ungrouped"
        val catchupDays = attr(attrsPart, "catchup-days")?.toIntOrNull()
            ?: attr(attrsPart, "timeshift")?.toIntOrNull()
            ?: 0
        val catchupSource = attr(attrsPart, "catchup-source")?.ifBlank { null }
        val catchupType = attr(attrsPart, "catchup")?.ifBlank { null }
            ?: attr(attrsPart, "catchup-type")?.ifBlank { null }
        val displayName = name.ifBlank { tvgName ?: "Channel" }
        val id = stableId(tvgId, displayName, streamUrl)
        return IptvChannel(
            id = id,
            name = displayName,
            logoUrl = logo,
            group = group,
            streamUrl = streamUrl.trim(),
            tvgId = tvgId,
            catchupDays = catchupDays,
            catchupSource = catchupSource,
            catchupType = catchupType
        )
    }

    private fun attr(source: String, key: String): String? {
        val regex = Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE)
        return regex.find(source)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
    }

    private fun stableId(tvgId: String?, name: String, url: String): String {
        val raw = listOf(tvgId.orEmpty(), name, url).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
