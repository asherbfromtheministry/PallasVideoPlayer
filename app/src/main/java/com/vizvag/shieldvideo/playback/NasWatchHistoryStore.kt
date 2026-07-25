package com.vizvag.shieldvideo.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NasWatchHistoryEntry(
    val share: String,
    val path: String,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Persistent, most-recent-first history of NAS videos launched from the app. */
class NasWatchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(): List<NasWatchHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val share = item.optString("share")
                    val path = item.optString("path")
                    if (share.isBlank() || path.isBlank()) continue
                    add(
                        NasWatchHistoryEntry(
                            share = share,
                            path = path,
                            title = item.optString("title").ifBlank {
                                path.substringAfterLast('/')
                            },
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(share: String, path: String, title: String) {
        if (share.isBlank() || path.isBlank()) return
        val items = entries().toMutableList()
        items.removeAll { it.share == share && it.path == path }
        items.add(
            0,
            NasWatchHistoryEntry(
                share = share,
                path = path,
                title = title.ifBlank { path.substringAfterLast('/') }
            )
        )
        while (items.size > MAX_ENTRIES) items.removeAt(items.lastIndex)
        save(items)
    }

    fun remove(share: String, path: String) {
        save(entries().filterNot { it.share == share && it.path == path })
    }

    private fun save(items: List<NasWatchHistoryEntry>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("share", item.share)
                    .put("path", item.path)
                    .put("title", item.title)
                    .put("updatedAt", item.updatedAt)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "nas_watch_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 10
    }
}
