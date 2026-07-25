package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import org.json.JSONArray

/** Persistent, most-recent-first channel history for each playlist. */
class IptvWatchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_watch_history", Context.MODE_PRIVATE)

    fun channelIds(playlistId: String): List<String> {
        val raw = prefs.getString(key(playlistId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(playlistId: String, channelId: String) {
        val ids = channelIds(playlistId).toMutableList()
        ids.remove(channelId)
        ids.add(0, channelId)
        while (ids.size > MAX_CHANNELS) ids.removeAt(ids.lastIndex)

        val array = JSONArray()
        ids.forEach(array::put)
        prefs.edit().putString(key(playlistId), array.toString()).apply()
    }

    private fun key(playlistId: String) = "history_$playlistId"

    private companion object {
        const val MAX_CHANNELS = 10
    }
}
