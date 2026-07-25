package com.vizvag.shieldvideo.playback

import android.content.Context
import org.json.JSONObject

data class NowPlaying(
    val deviceId: String,
    val uri: String,
    val path: String,
    val share: String = "",
    val host: String = "",
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device", deviceId)
        .put("uri", uri)
        .put("path", path)
        .put("share", share)
        .put("host", host)
        .put("title", title)
        .put("position_ms", positionMs)
        .put("duration_ms", durationMs)
        .put("updated_at", updatedAt)
}

/** Local snapshot of the active playback session (for HA handoff). */
class NowPlayingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(session: NowPlaying) {
        prefs.edit()
            .putString(KEY_JSON, session.toJson().toString())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    fun get(): NowPlaying? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            NowPlaying(
                deviceId = json.optString("device"),
                uri = json.optString("uri"),
                path = json.optString("path"),
                share = json.optString("share"),
                host = json.optString("host"),
                title = json.optString("title"),
                positionMs = json.optLong("position_ms"),
                durationMs = json.optLong("duration_ms"),
                updatedAt = json.optLong("updated_at")
            ).takeIf { it.uri.isNotBlank() || it.path.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        private const val PREFS = "shield_video_now_playing"
        private const val KEY_JSON = "session"
    }
}
