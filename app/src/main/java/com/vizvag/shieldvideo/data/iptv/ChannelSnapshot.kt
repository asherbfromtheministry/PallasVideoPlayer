package com.vizvag.shieldvideo.data.iptv

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Compact on-disk channel catalog for instant Live TV open (avoids re-parsing M3U).
 * Format: line-oriented, versioned, unit-separator fields.
 */
object ChannelSnapshot {
    private const val MAGIC = "PALLAS_CH1"
    private const val SEP = '\u001f'

    data class Loaded(
        val loadedAtMs: Long,
        val groups: List<String>,
        val channels: List<IptvChannel>
    )

    fun file(cacheDir: File, playlistId: String): File =
        File(cacheDir, "channels_$playlistId.idx")

    fun write(file: File, loadedAtMs: Long, groups: List<String>, channels: List<IptvChannel>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        BufferedWriter(OutputStreamWriter(FileOutputStream(tmp), StandardCharsets.UTF_8)).use { out ->
            out.append(MAGIC).append('\n')
            out.append(loadedAtMs.toString()).append('\n')
            out.append(groups.size.toString()).append('\n')
            groups.forEach { out.append(escape(it)).append('\n') }
            out.append(channels.size.toString()).append('\n')
            val groupIndex = groups.withIndex().associate { it.value to it.index }
            channels.forEach { ch ->
                val gi = groupIndex[ch.group] ?: 0
                out.append(escape(ch.id)).append(SEP)
                    .append(escape(ch.name)).append(SEP)
                    .append(escape(ch.logoUrl.orEmpty())).append(SEP)
                    .append(gi.toString()).append(SEP)
                    .append(escape(ch.streamUrl)).append(SEP)
                    .append(escape(ch.tvgId.orEmpty())).append(SEP)
                    .append(ch.catchupDays.toString()).append(SEP)
                    .append(escape(ch.catchupSource.orEmpty())).append(SEP)
                    .append(escape(ch.catchupType.orEmpty()))
                    .append('\n')
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun read(file: File): Loaded? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
                if (reader.readLine() != MAGIC) return null
                val loadedAtMs = reader.readLine()?.toLongOrNull() ?: return null
                val groupCount = reader.readLine()?.toIntOrNull() ?: return null
                val groups = ArrayList<String>(groupCount)
                repeat(groupCount) {
                    groups += unescape(reader.readLine() ?: return null)
                }
                val channelCount = reader.readLine()?.toIntOrNull() ?: return null
                val channels = ArrayList<IptvChannel>(channelCount)
                repeat(channelCount) {
                    val line = reader.readLine() ?: return null
                    val p = line.split(SEP)
                    if (p.size < 9) return null
                    val gi = p[3].toIntOrNull() ?: 0
                    channels += IptvChannel(
                        id = unescape(p[0]),
                        name = unescape(p[1]),
                        logoUrl = unescape(p[2]).ifBlank { null },
                        group = groups.getOrElse(gi) { "Other" },
                        streamUrl = unescape(p[4]),
                        tvgId = unescape(p[5]).ifBlank { null },
                        catchupDays = p[6].toIntOrNull() ?: 0,
                        catchupSource = unescape(p[7]).ifBlank { null },
                        catchupType = unescape(p[8]).ifBlank { null }
                    )
                }
                Loaded(loadedAtMs = loadedAtMs, groups = groups, channels = channels)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Tiny groups-only file for first paint before full channel idx is ready. */
    fun groupsFile(cacheDir: File, playlistId: String): File =
        File(cacheDir, "groups_$playlistId.txt")

    fun writeGroups(file: File, groups: List<String>) {
        file.writeText(groups.joinToString("\n") { escape(it) }, StandardCharsets.UTF_8)
    }

    fun readGroups(file: File): List<String>? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.readLines(StandardCharsets.UTF_8).map { unescape(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace(SEP.toString(), "\\s")

    private fun unescape(value: String): String =
        buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n' -> { append('\n'); i += 2 }
                        's' -> { append(SEP); i += 2 }
                        '\\' -> { append('\\'); i += 2 }
                        else -> { append(c); i++ }
                    }
                } else {
                    append(c)
                    i++
                }
            }
        }
}
