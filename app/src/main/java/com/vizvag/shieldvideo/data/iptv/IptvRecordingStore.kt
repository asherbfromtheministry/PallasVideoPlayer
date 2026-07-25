package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class IptvRecordingStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_recordings", Context.MODE_PRIVATE)

    fun list(): List<IptvRecording> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        IptvRecording(
                            id = o.getString("id"),
                            channelId = o.getString("channelId"),
                            channelName = o.optString("channelName"),
                            title = o.optString("title"),
                            startMs = o.optLong("startMs"),
                            stopMs = o.optLong("stopMs"),
                            streamUrl = o.optString("streamUrl"),
                            status = runCatching {
                                IptvRecordingStatus.valueOf(o.optString("status"))
                            }.getOrDefault(IptvRecordingStatus.SCHEDULED),
                            localPath = o.optString("localPath").ifBlank { null },
                            error = o.optString("error").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(recording: IptvRecording) {
        val items = list().toMutableList()
        val idx = items.indexOfFirst { it.id == recording.id }
        if (idx >= 0) items[idx] = recording else items.add(0, recording)
        save(items)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    private fun save(items: List<IptvRecording>) {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("channelId", r.channelId)
                    .put("channelName", r.channelName)
                    .put("title", r.title)
                    .put("startMs", r.startMs)
                    .put("stopMs", r.stopMs)
                    .put("streamUrl", r.streamUrl)
                    .put("status", r.status.name)
                    .put("localPath", r.localPath ?: "")
                    .put("error", r.error ?: "")
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "recordings"
    }
}
