package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class GroupChannelOrder(val label: String) {
    CUSTOM("Custom"),
    ALPHABETICAL("Alphabetical"),
    MOST_WATCHED("Most watched");

    companion object {
        fun fromStorage(value: String?): GroupChannelOrder =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * Per-playlist channel display names and per-group order.
 * Order/rename survive playlist reloads; unknown IDs are ignored.
 */
class IptvChannelCustomStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_channel_custom", Context.MODE_PRIVATE)

    fun displayName(playlistId: String, channelId: String, fallback: String): String =
        renames(playlistId)[channelId]?.takeIf { it.isNotBlank() } ?: fallback

    fun setDisplayName(playlistId: String, channelId: String, name: String?) {
        val map = renames(playlistId).toMutableMap()
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            map.remove(channelId)
        } else {
            map[channelId] = trimmed
        }
        saveRenames(playlistId, map)
    }

    fun ordered(playlistId: String, groupKey: String, channels: List<IptvChannel>): List<IptvChannel> {
        val order = getOrder(playlistId, groupKey)
        if (order.isEmpty()) return channels
        val byId = channels.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val out = ArrayList<IptvChannel>(channels.size)
        order.forEach { id ->
            val ch = byId[id] ?: return@forEach
            if (seen.add(id)) out.add(ch)
        }
        channels.forEach { ch ->
            if (seen.add(ch.id)) out.add(ch)
        }
        return out
    }

    fun setOrder(playlistId: String, groupKey: String, channelIds: List<String>) {
        val arr = JSONArray()
        channelIds.forEach { arr.put(it) }
        prefs.edit().putString(orderKey(playlistId, groupKey), arr.toString()).apply()
    }

    /** Saved top-to-bottom order of the group wheel (raw group keys, may be partial). */
    fun groupOrder(playlistId: String): List<String> {
        val raw = prefs.getString(groupOrderKey(playlistId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val key = arr.optString(i)
                    if (key.isNotBlank()) add(key)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setGroupOrder(playlistId: String, groupKeys: List<String>) {
        val arr = JSONArray()
        groupKeys.forEach { arr.put(it) }
        prefs.edit().putString(groupOrderKey(playlistId), arr.toString()).apply()
    }

    fun hiddenGroups(playlistId: String): Set<String> =
        prefs.getStringSet(hiddenGroupsKey(playlistId), emptySet())?.toSet().orEmpty()

    fun setGroupHidden(playlistId: String, groupKey: String, hidden: Boolean) {
        val current = hiddenGroups(playlistId).toMutableSet()
        val changed = if (hidden) current.add(groupKey) else current.remove(groupKey)
        if (changed) {
            prefs.edit().putStringSet(hiddenGroupsKey(playlistId), current).commit()
        }
    }

    fun groupDisplayName(playlistId: String, groupKey: String): String =
        prefs.getString(groupNameKey(playlistId, groupKey), null)
            ?.takeIf { it.isNotBlank() }
            ?: groupKey

    fun setGroupDisplayName(playlistId: String, groupKey: String, displayName: String?) {
        val key = groupNameKey(playlistId, groupKey)
        val trimmed = displayName?.trim().orEmpty()
        val edit = prefs.edit()
        if (trimmed.isBlank() || trimmed.equals(groupKey, ignoreCase = true)) {
            edit.remove(key)
        } else {
            edit.putString(key, trimmed)
        }
        edit.commit()
    }

    fun groupOrderMode(playlistId: String, groupKey: String): GroupChannelOrder =
        GroupChannelOrder.fromStorage(prefs.getString(groupModeKey(playlistId, groupKey), null))

    fun setGroupOrderMode(
        playlistId: String,
        groupKey: String,
        mode: GroupChannelOrder
    ) {
        prefs.edit().putString(groupModeKey(playlistId, groupKey), mode.name).apply()
    }

    fun watchCount(playlistId: String, channelId: String): Int =
        prefs.getInt(watchCountKey(playlistId, channelId), 0)

    fun incrementWatchCount(playlistId: String, channelId: String) {
        val key = watchCountKey(playlistId, channelId)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /** Real stream badges (resolution/fps/HDR) measured while the channel was last watched. */
    fun streamBadges(playlistId: String, channelId: String): List<String> =
        streamInfoMap(playlistId)[channelId]
            ?.split('|')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun setStreamBadges(playlistId: String, channelId: String, badges: List<String>) {
        val map = streamInfoMap(playlistId).toMutableMap()
        val encoded = badges.joinToString("|")
        if (map[channelId] == encoded) return
        map[channelId] = encoded
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(streamInfoKey(playlistId), obj.toString()).apply()
    }

    private fun streamInfoMap(playlistId: String): Map<String, String> {
        val raw = prefs.getString(streamInfoKey(playlistId), null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Custom XMLTV channel id override. Prefer a logical identity that survives provider URL
     * rotation; fall back to the legacy generated channel id for existing assignments.
     */
    fun epgId(playlistId: String, channel: IptvChannel): String? {
        val map = epgMaps(playlistId)
        return map[epgIdentity(channel)]?.takeIf { it.isNotBlank() }
            ?: map[epgIdentityNoGroup(channel)]?.takeIf { it.isNotBlank() }
            ?: map[channel.id]?.takeIf { it.isNotBlank() }
    }

    /**
     * Rewrite every assignment that still resolves onto all stable keys. Heals stores whose
     * entries predate the logical-identity keys (URL-hash ids break when the provider rotates
     * stream URLs, e.g. after importing settings on another device or a playlist refresh).
     */
    fun migrateEpgAssignments(playlistId: String, channels: List<IptvChannel>) {
        val map = epgMaps(playlistId)
        if (map.isEmpty()) return
        val updated = map.toMutableMap()
        var changed = false
        channels.forEach { channel ->
            val resolved = map[epgIdentity(channel)]?.takeIf { it.isNotBlank() }
                ?: map[epgIdentityNoGroup(channel)]?.takeIf { it.isNotBlank() }
                ?: map[channel.id]?.takeIf { it.isNotBlank() }
                ?: return@forEach
            listOf(epgIdentity(channel), epgIdentityNoGroup(channel), channel.id).forEach { key ->
                if (updated[key] != resolved) {
                    updated[key] = resolved
                    changed = true
                }
            }
        }
        if (changed) saveEpgMaps(playlistId, updated)
    }

    fun allEpgIds(playlistId: String): Set<String> =
        epgMaps(playlistId).values.filter { it.isNotBlank() }.toSet()

    fun setEpgId(playlistId: String, channel: IptvChannel, epgChannelId: String?) {
        val map = epgMaps(playlistId).toMutableMap()
        val trimmed = epgChannelId?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            map.remove(epgIdentity(channel))
            map.remove(epgIdentityNoGroup(channel))
            map.remove(channel.id)
        } else {
            map[epgIdentity(channel)] = trimmed
            // Group-independent key survives the provider moving a channel between groups.
            map[epgIdentityNoGroup(channel)] = trimmed
            // Keep the old key during migration so older builds can still read the assignment.
            map[channel.id] = trimmed
        }
        saveEpgMaps(playlistId, map)
    }

    private fun getOrder(playlistId: String, groupKey: String): List<String> {
        val raw = prefs.getString(orderKey(playlistId, groupKey), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank()) add(id)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun renames(playlistId: String): Map<String, String> {
        val raw = prefs.getString(renameKey(playlistId), null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveRenames(playlistId: String, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(renameKey(playlistId), obj.toString()).apply()
    }

    private fun epgMaps(playlistId: String): Map<String, String> {
        val raw = prefs.getString(epgMapKey(playlistId), null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveEpgMaps(playlistId: String, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        // Assignment is an explicit user action; commit before closing the dialog/process.
        prefs.edit().putString(epgMapKey(playlistId), obj.toString()).commit()
    }

    private fun epgIdentity(channel: IptvChannel): String {
        return "channel:${clean(channel.tvgId)}|${clean(channel.name)}|${clean(channel.group)}"
    }

    private fun epgIdentityNoGroup(channel: IptvChannel): String {
        return "channelng:${clean(channel.tvgId)}|${clean(channel.name)}"
    }

    private fun clean(value: String?): String = value.orEmpty()
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

    private fun renameKey(playlistId: String) = "rename_$playlistId"

    private fun streamInfoKey(playlistId: String) = "streaminfo_$playlistId"

    private fun epgMapKey(playlistId: String) = "epgmap_$playlistId"

    private fun orderKey(playlistId: String, groupKey: String): String {
        val safe = safeGroupKey(groupKey)
        return "order_${playlistId}_$safe"
    }

    private fun groupOrderKey(playlistId: String) = "grouporder_$playlistId"

    private fun hiddenGroupsKey(playlistId: String) = "hiddengroups_$playlistId"

    private fun groupNameKey(playlistId: String, groupKey: String) =
        "groupname_${playlistId}_${safeGroupKey(groupKey)}"

    private fun groupModeKey(playlistId: String, groupKey: String) =
        "groupmode_${playlistId}_${safeGroupKey(groupKey)}"

    private fun watchCountKey(playlistId: String, channelId: String) =
        "watchcount_${playlistId}_$channelId"

    private fun safeGroupKey(groupKey: String): String =
        groupKey.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
}
