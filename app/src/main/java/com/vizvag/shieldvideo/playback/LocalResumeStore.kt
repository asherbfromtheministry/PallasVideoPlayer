package com.vizvag.shieldvideo.playback

import android.content.Context
import org.json.JSONObject

data class LocalResume(
    val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val watched: Boolean = false,
) {
    val progress: Float?
        get() = if (!watched && durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0.02f, 0.98f)
        } else null

    /** In-progress resume suitable for seek / resume bar (not finished, not tiny). */
    val isMeaningful: Boolean
        get() = !watched &&
            positionMs > 5_000L &&
            (durationMs <= 0L || positionMs < durationMs * 0.95)
}

class LocalResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Resume position only — finished / watched entries return null. */
    fun get(path: String): LocalResume? =
        getIncludingWatched(path)?.takeIf { it.isMeaningful }

    /** Any stored marker including watched. */
    fun getIncludingWatched(path: String): LocalResume? {
        val raw = prefs.getString(key(path), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LocalResume(
                path = path,
                positionMs = json.getLong("positionMs"),
                durationMs = json.optLong("durationMs", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                watched = json.optBoolean("watched", false),
            )
        }.getOrNull()
    }

    fun isWatched(path: String): Boolean =
        getIncludingWatched(path)?.watched == true

    fun save(path: String, positionMs: Long, durationMs: Long) {
        if (durationMs > 0L && positionMs >= durationMs * 0.95) {
            markWatched(path, durationMs)
            return
        }
        if (positionMs <= 5_000L) return
        write(
            LocalResume(
                path = path,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
                watched = false,
            )
        )
        prefs.edit().putString(KEY_LAST_PATH, path).apply()
    }

    fun markWatched(path: String, durationMs: Long = 0L) {
        val prior = getIncludingWatched(path)
        write(
            LocalResume(
                path = path,
                positionMs = 0L,
                durationMs = durationMs.takeIf { it > 0 } ?: prior?.durationMs ?: 0L,
                updatedAt = System.currentTimeMillis(),
                watched = true,
            )
        )
    }

    /**
     * Apply a remote/NAS progress record when it is newer than the local copy.
     * Returns true when local prefs were updated.
     */
    fun mergeRemote(remote: LocalResume): Boolean {
        val local = getIncludingWatched(remote.path)
        if (local != null && local.updatedAt >= remote.updatedAt) return false
        write(remote.copy(path = remote.path))
        return true
    }

    fun clear(path: String) {
        prefs.edit().remove(key(path)).apply()
    }

    fun lastLaunchedPath(): String? = prefs.getString(KEY_LAST_PATH, null)

    fun setLastLaunched(path: String) {
        prefs.edit().putString(KEY_LAST_PATH, path).apply()
    }

    private fun write(entry: LocalResume) {
        val json = JSONObject()
            .put("positionMs", entry.positionMs)
            .put("durationMs", entry.durationMs)
            .put("updatedAt", entry.updatedAt)
            .put("watched", entry.watched)
        prefs.edit().putString(key(entry.path), json.toString()).apply()
    }

    private fun key(path: String) = "resume:" + path.trim('/')

    companion object {
        private const val PREFS = "shield_video_resume"
        private const val KEY_LAST_PATH = "last_launched_path"
    }
}
