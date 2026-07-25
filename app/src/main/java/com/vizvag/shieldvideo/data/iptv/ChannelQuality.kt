package com.vizvag.shieldvideo.data.iptv

/**
 * Infer feed quality badges from channel display names (common IPTV naming).
 */
object ChannelQuality {
    private val fpsRegex = Regex("""(?<![0-9])(\d{2,3})\s*(?:FPS|HZ)""")

    fun labelsFor(name: String): List<String> {
        val n = name.uppercase()
        val out = LinkedHashSet<String>()
        when {
            "4K" in n || "UHD" in n || "2160" in n -> out += "4K"
            "FHD" in n || "1080" in n -> out += "FHD"
            Regex("""(?<![A-Z0-9])HD(?![A-Z0-9])""").containsMatchIn(n) || "720" in n -> out += "HD"
            "SD" in n || "480" in n || "576" in n -> out += "SD"
        }
        fpsRegex.find(n)?.let { out += "${it.groupValues[1]}fps" }
        if ("HDR10" in n || "HDR" in n || "HLG" in n || "DOLBY VISION" in n ||
            Regex("""(?<![A-Z])DV(?![A-Z])""").containsMatchIn(n)
        ) {
            out += "HDR"
        }
        if ("HEVC" in n || "H265" in n || "H.265" in n) out += "HEVC"
        return out.toList()
    }
}
