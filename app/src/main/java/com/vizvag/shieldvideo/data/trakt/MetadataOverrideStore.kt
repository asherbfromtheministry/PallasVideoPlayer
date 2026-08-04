package com.vizvag.shieldvideo.data.trakt

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ForcedMetadata(
    val mediaType: String,
    val tmdbId: Int,
    val traktId: Int? = null,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
)

/**
 * Paths the user has cleared — skip Trakt/TMDB enrichment.
 * Recursive clears cover everything under the path (ancestor match).
 * Exact clears apply only to that path; nested folders/files keep metadata.
 * Forced assignments pin a folder/file to a chosen show/movie and inherit
 * to every nested path unless a closer clear or exact override wins.
 */
class MetadataOverrideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val clearedRecursive = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet(KEY_CLEARED, emptySet()).orEmpty().map { normalize(it) })
    }
    private val clearedExact = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet(KEY_CLEARED_EXACT, emptySet()).orEmpty().map { normalize(it) })
    }
    private val forced = ConcurrentHashMap<String, ForcedMetadata>().apply {
        val raw = prefs.getString(KEY_FORCED, null) ?: return@apply
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { path ->
                val obj = root.getJSONObject(path)
                put(
                    path,
                    ForcedMetadata(
                        mediaType = obj.getString("mediaType"),
                        tmdbId = obj.getInt("tmdbId"),
                        traktId = obj.optInt("traktId").takeIf { obj.has("traktId") && !obj.isNull("traktId") },
                        title = obj.getString("title"),
                        year = obj.optInt("year").takeIf { obj.has("year") && !obj.isNull("year") },
                        overview = obj.optString("overview").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    /** True if this path is cleared exactly, or any ancestor (including self) was cleared recursively. */
    fun isCleared(path: String): Boolean {
        val key = normalize(path)
        if (clearedExact.contains(key) || clearedRecursive.contains(key)) return true
        var prefix = key
        while (true) {
            val slash = prefix.lastIndexOf('/')
            if (slash <= 0) break
            prefix = prefix.substring(0, slash)
            if (clearedRecursive.contains(prefix)) return true
        }
        return false
    }

    /**
     * Exact assignment for this path, or nearest assigned ancestor folder.
     * Applies to files and folders so a folder assign covers every nested
     * subfolder and every file under it. An exact clear on this path (or an
     * ancestor recursive clear) blocks inheritance.
     */
    fun getForced(path: String): ForcedMetadata? {
        val key = normalize(path)
        forced[key]?.let { return it }
        if (isCleared(path)) return null
        var prefix = key
        while (true) {
            val slash = prefix.lastIndexOf('/')
            if (slash <= 0) break
            prefix = prefix.substring(0, slash)
            forced[prefix]?.let { return it }
        }
        return null
    }

    /** Same as [getForced] — kept for call sites that name folders explicitly. */
    fun getForcedForFolder(path: String): ForcedMetadata? = getForced(path)

    fun clearMetadata(path: String, includeDescendants: Boolean = true) {
        val key = normalize(path)
        if (includeDescendants) {
            clearedRecursive += key
            clearedExact.remove(key)
            val childPrefix = "$key/"
            forced.keys.filter { it.startsWith(childPrefix) }.forEach { forced.remove(it) }
            clearedRecursive.filter { it != key && it.startsWith(childPrefix) }.forEach { clearedRecursive.remove(it) }
            clearedExact.filter { it.startsWith(childPrefix) }.forEach { clearedExact.remove(it) }
        } else {
            clearedExact += key
            clearedRecursive.remove(key)
        }
        forced.remove(key)
        persist()
    }

    fun restoreMetadata(path: String) {
        val key = normalize(path)
        clearedRecursive.remove(key)
        clearedExact.remove(key)
        persist()
    }

    fun assignMetadata(path: String, assignment: ForcedMetadata) {
        val key = normalize(path)
        clearedRecursive.remove(key)
        clearedExact.remove(key)
        val childPrefix = "$key/"
        clearedRecursive.filter { it.startsWith(childPrefix) }.forEach { clearedRecursive.remove(it) }
        clearedExact.filter { it.startsWith(childPrefix) }.forEach { clearedExact.remove(it) }
        forced.keys.filter { it.startsWith(childPrefix) }.forEach { forced.remove(it) }
        // If an ancestor folder was cleared, lift those clears so this assignment can show
        var prefix = key
        while (true) {
            val slash = prefix.lastIndexOf('/')
            if (slash <= 0) break
            prefix = prefix.substring(0, slash)
            clearedRecursive.remove(prefix)
            clearedExact.remove(prefix)
        }
        forced[key] = assignment
        persist()
    }

    fun keepEmpty(path: String) {
        clearMetadata(path, includeDescendants = true)
    }

    private fun persist() {
        val forcedJson = JSONObject()
        forced.forEach { (path, meta) ->
            forcedJson.put(
                path,
                JSONObject().apply {
                    put("mediaType", meta.mediaType)
                    put("tmdbId", meta.tmdbId)
                    if (meta.traktId != null) put("traktId", meta.traktId) else put("traktId", JSONObject.NULL)
                    put("title", meta.title)
                    if (meta.year != null) put("year", meta.year) else put("year", JSONObject.NULL)
                    put("overview", meta.overview ?: "")
                },
            )
        }
        prefs.edit()
            .putStringSet(KEY_CLEARED, HashSet(clearedRecursive))
            .putStringSet(KEY_CLEARED_EXACT, HashSet(clearedExact))
            .putString(KEY_FORCED, forcedJson.toString())
            .commit()
    }

    private fun normalize(path: String): String = path.trim().replace('\\', '/').lowercase()

    companion object {
        private const val PREFS = "metadata_overrides"
        private const val KEY_CLEARED = "cleared_paths"
        private const val KEY_CLEARED_EXACT = "cleared_paths_exact"
        private const val KEY_FORCED = "forced_assignments"
    }
}
