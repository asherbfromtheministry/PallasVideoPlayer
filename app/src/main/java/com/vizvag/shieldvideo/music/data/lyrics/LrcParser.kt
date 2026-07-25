package com.vizvag.shieldvideo.music.data.lyrics

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

object LrcParser {
    /** One or more `[mm:ss.xx]` tags, optional lyric text after the last tag. */
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(content: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = timeTagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                val frac = tag.groupValues[3]
                val ms = min * 60_000L + sec * 1_000L +
                    (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
                out += LyricLine(ms, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) index = i else break
        }
        return index
    }
}
