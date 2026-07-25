package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import org.json.JSONArray

/** Persistent, most-recent-first IPTV search queries for each playlist. */
class IptvSearchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_search_history", Context.MODE_PRIVATE)

    fun queries(playlistId: String): List<String> {
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

    fun record(playlistId: String, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val items = queries(playlistId).toMutableList()
        items.removeAll { it.equals(clean, ignoreCase = true) }
        items.add(0, clean)
        while (items.size > MAX_QUERIES) items.removeAt(items.lastIndex)
        val array = JSONArray()
        items.forEach(array::put)
        prefs.edit().putString(key(playlistId), array.toString()).apply()
    }

    fun remove(playlistId: String, query: String) {
        val items = queries(playlistId).filterNot { it.equals(query, ignoreCase = true) }
        val array = JSONArray()
        items.forEach(array::put)
        prefs.edit().putString(key(playlistId), array.toString()).apply()
    }

    private fun key(playlistId: String) = "queries_$playlistId"

    private companion object {
        const val MAX_QUERIES = 10
    }
}
