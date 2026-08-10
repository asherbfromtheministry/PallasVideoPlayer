package com.vizvag.shieldvideo.data.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Most-recent-first YouTube watch history (local only). */
class YoutubeWatchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_watch_history", Context.MODE_PRIVATE)

    /** Continue-watching shelf (newest first). */
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
                            channelId = o.cleanString("channelId"),
                            resolutionLabel = o.cleanString("resolutionLabel").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** All known watched video ids (history + long-lived id set) for badges / filters. */
    fun watchedIds(): Set<String> {
        val fromHistory = items().map { it.id }
        val fromIds = loadWatchedIdList()
        if (fromIds.isEmpty() && fromHistory.isNotEmpty()) {
            // One-time seed so existing continue-watching entries get badges / filters.
            prefs.edit().putString(KEY_WATCHED_IDS, JSONArray(fromHistory).toString()).apply()
            return fromHistory.toSet()
        }
        return (fromHistory + fromIds).toSet()
    }

    fun isWatched(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        return videoId in watchedIds()
    }

    fun record(item: YoutubeVideoItem) {
        if (item.id.isBlank()) return
        val next = items().toMutableList()
        next.removeAll { it.id == item.id }
        next.add(0, item)
        while (next.size > MAX_HISTORY) next.removeAt(next.lastIndex)
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
                    .put("channelId", v.channelId)
                    .put("resolutionLabel", v.resolutionLabel.orEmpty())
            )
        }
        val ids = loadWatchedIdList().toMutableList()
        ids.removeAll { it == item.id }
        ids.add(0, item.id)
        while (ids.size > MAX_WATCHED_IDS) ids.removeAt(ids.lastIndex)
        prefs.edit()
            .putString(KEY, array.toString())
            .putString(KEY_WATCHED_IDS, JSONArray(ids).toString())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(KEY_WATCHED_IDS).apply()
    }

    private fun loadWatchedIdList(): List<String> {
        val raw = prefs.getString(KEY_WATCHED_IDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotBlank() && id != "null") add(id)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.cleanString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        return if (value.equals("null", ignoreCase = true)) "" else value.trim()
    }

    private companion object {
        const val KEY = "history"
        const val KEY_WATCHED_IDS = "watched_ids"
        const val MAX_HISTORY = 20
        /** Long-lived set for Recommended filtering + WATCHED badges. */
        const val MAX_WATCHED_IDS = 500
    }
}
