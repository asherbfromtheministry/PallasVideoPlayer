package com.vizvag.shieldvideo.data.iptv

import android.content.Context

class IptvFavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_favorites", Context.MODE_PRIVATE)

    fun getFavorites(playlistId: String): Set<String> =
        prefs.getStringSet(key(playlistId), emptySet())?.toSet().orEmpty()

    fun isFavorite(playlistId: String, channelId: String): Boolean =
        getFavorites(playlistId).contains(channelId)

    fun toggle(playlistId: String, channelId: String): Boolean {
        val current = getFavorites(playlistId).toMutableSet()
        val added = if (channelId in current) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        prefs.edit().putStringSet(key(playlistId), current).apply()
        return added
    }

    fun setAll(playlistId: String, ids: Set<String>) {
        prefs.edit().putStringSet(key(playlistId), ids).apply()
    }

    private fun key(playlistId: String) = "fav_$playlistId"
}
