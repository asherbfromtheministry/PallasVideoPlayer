package com.vizvag.shieldvideo.data.index

import android.content.Context
import com.vizvag.shieldvideo.data.smb.SmbEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IndexedVideo(
    val root: String,
    val share: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Optional Video Station / metadata title used for search matching. */
    val title: String? = null,
) {
    fun toEntry(): SmbEntry = SmbEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size
    )

    fun matches(tokens: List<String>): Boolean {
        val hay = "$name $path ${title.orEmpty()}".lowercase()
        return tokens.all { it in hay }
    }
}

data class VideoIndexSnapshot(
    val builtAtMs: Long = 0L,
    val host: String = "",
    val connectionMode: String = "",
    val roots: List<String> = emptyList(),
    val entries: List<IndexedVideo> = emptyList(),
    /** `videostation` when synced from DiskStation Video Station; `walk` for folder scan. */
    val source: String = "",
) {
    val isEmpty: Boolean get() = entries.isEmpty() || builtAtMs <= 0L
    val ageMs: Long get() = if (builtAtMs <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - builtAtMs
}

class VideoIndexStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "video_index.json")

    @Synchronized
    fun load(): VideoIndexSnapshot {
        if (!file.exists()) return VideoIndexSnapshot()
        return runCatching {
            val json = JSONObject(file.readText())
            val roots = buildList {
                val arr = json.optJSONArray("roots") ?: JSONArray()
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
            val entries = buildList {
                val arr = json.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        IndexedVideo(
                            root = obj.getString("root"),
                            share = obj.getString("share"),
                            path = obj.getString("path"),
                            name = obj.getString("name"),
                            isDirectory = obj.optBoolean("dir", false),
                            size = obj.optLong("size", 0L),
                            title = obj.optString("title", "").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
            VideoIndexSnapshot(
                builtAtMs = json.optLong("builtAtMs", 0L),
                host = json.optString("host", ""),
                connectionMode = json.optString("connectionMode", ""),
                roots = roots,
                entries = entries,
                source = json.optString("source", ""),
            )
        }.getOrDefault(VideoIndexSnapshot())
    }

    @Synchronized
    fun save(snapshot: VideoIndexSnapshot) {
        val rootsArr = JSONArray()
        snapshot.roots.forEach { rootsArr.put(it) }
        val entriesArr = JSONArray()
        snapshot.entries.forEach { entry ->
            val obj = JSONObject()
                .put("root", entry.root)
                .put("share", entry.share)
                .put("path", entry.path)
                .put("name", entry.name)
                .put("dir", entry.isDirectory)
                .put("size", entry.size)
            if (!entry.title.isNullOrBlank()) {
                obj.put("title", entry.title)
            }
            entriesArr.put(obj)
        }
        val json = JSONObject()
            .put("builtAtMs", snapshot.builtAtMs)
            .put("host", snapshot.host)
            .put("connectionMode", snapshot.connectionMode)
            .put("roots", rootsArr)
            .put("entries", entriesArr)
            .put("source", snapshot.source)
        file.writeText(json.toString())
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }
}
