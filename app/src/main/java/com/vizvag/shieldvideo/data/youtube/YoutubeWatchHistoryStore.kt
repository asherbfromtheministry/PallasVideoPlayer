package com.vizvag.shieldvideo.data.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Most-recent-first YouTube watch history (local only). */
class YoutubeWatchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_watch_history", Context.MODE_PRIVATE)

    fun items(): List<YoutubeVideoItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.cleanString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        YoutubeVideoItem(
                            id = id,
                            title = o.cleanString("title").ifBlank { "YouTube" },
                            uploader = o.cleanString("uploader"),
                            thumbnailUrl = o.cleanString("thumbnailUrl"),
                            durationSec = o.optLong("durationSec"),
                            views = o.optLong("views").coerceAtLeast(0L),
                            uploadedDate = o.cleanString("uploadedDate"),
                            uploadedEpochMs = o.optLong("uploadedEpochMs").coerceAtLeast(0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(item: YoutubeVideoItem) {
        if (item.id.isBlank()) return
        val next = items().toMutableList()
        next.removeAll { it.id == item.id }
        next.add(0, item)
        while (next.size > MAX) next.removeAt(next.lastIndex)
        val array = JSONArray()
        next.forEach { v ->
            array.put(
                JSONObject()
                    .put("id", v.id)
                    .put("title", v.title)
                    .put("uploader", v.uploader)
                    .put("thumbnailUrl", v.thumbnailUrl)
                    .put("durationSec", v.durationSec)
                    .put("views", v.views)
                    .put("uploadedDate", v.uploadedDate)
                    .put("uploadedEpochMs", v.uploadedEpochMs)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun JSONObject.cleanString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        return if (value.equals("null", ignoreCase = true)) "" else value.trim()
    }

    private companion object {
        const val KEY = "history"
        const val MAX = 20
    }
}
