package com.vizvag.shieldvideo.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.vizvag.shieldvideo.BuildConfig
import com.vizvag.shieldvideo.data.nas.NasRepository
import org.json.JSONArray
import org.json.JSONObject

class SettingsBackupManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository
) {
    private val appContext = context.applicationContext

    suspend fun exportToNas(settings: AppSettings): Result<String> = runCatching {
        require(settings.backupFolderPath.isNotBlank()) { "Select a NAS backup folder first" }

        // Export exactly what is visible in Settings, including any edits made before Save.
        settingsRepository.save(settings)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("createdAtMs", System.currentTimeMillis())
            .put("settings", settingsRepository.encodeForBackup(settings))
            .put("channelPreferences", encodePreferenceFiles())

        nasRepository.writeTextFile(
            settings = smbTransportSettings(settings),
            folderPath = settings.backupFolderPath,
            fileName = FILE_NAME,
            contents = root.toString(2)
        ).getOrThrow()
        "${settings.backupFolderPath.trimEnd('/')}/$FILE_NAME"
    }

    suspend fun importFromNas(connectionSettings: AppSettings): Result<AppSettings> = runCatching {
        require(connectionSettings.backupFolderPath.isNotBlank()) {
            "Select the NAS folder containing $FILE_NAME first"
        }
        val raw = nasRepository.readTextFile(
            settings = smbTransportSettings(connectionSettings),
            folderPath = connectionSettings.backupFolderPath,
            fileName = FILE_NAME
        ).getOrThrow()
        val root = JSONObject(raw)
        require(root.optString("format") == FORMAT) { "This is not a Pallas settings backup" }
        require(root.optInt("version", 0) in 1..VERSION) {
            "This backup was created by a newer unsupported version"
        }

        val imported = settingsRepository.decodeBackup(
            root.optJSONObject("settings")
                ?: throw IllegalArgumentException("Backup is missing app settings")
        )
        // Keep this TV's HA handoff device id when one is already set.
        // Import remoteToken so phones/tablets share the LAN control secret with the TVs.
        val localSettings = settingsRepository.load()
        val merged = imported.copy(
            deviceId = localSettings.deviceId.ifBlank { imported.deviceId },
            remoteToken = imported.remoteToken.ifBlank { localSettings.remoteToken },
            // SAF permissions belong to this Android device and cannot be transferred.
            iptvRecordingLocalTreeUri = localSettings.iptvRecordingLocalTreeUri
        )
        restorePreferenceFiles(root.optJSONObject("channelPreferences"))
        settingsRepository.save(merged)
        merged
    }

    /**
     * Backup files are transferred over SMB even when normal browsing uses DSM HTTP.
     * This copy is never persisted, so the user's selected connection mode and port stay intact.
     */
    private fun smbTransportSettings(settings: AppSettings): AppSettings =
        settings.copy(
            connectionMode = ConnectionMode.SMB3,
            port = ConnectionMode.SMB3.defaultPort
        )

    private fun encodePreferenceFiles(): JSONObject {
        val files = JSONObject()
        PORTABLE_PREFS.forEach { name ->
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            prefs.all.forEach { (key, value) ->
                entries.put(key, encodePreferenceValue(value))
            }
            files.put(name, entries)
        }
        return files
    }

    private fun encodePreferenceValue(value: Any?): JSONObject {
        val out = JSONObject()
        when (value) {
            is String -> out.put("type", "string").put("value", value)
            is Int -> out.put("type", "int").put("value", value)
            is Long -> out.put("type", "long").put("value", value)
            is Float -> out.put("type", "float").put("value", value.toDouble())
            is Boolean -> out.put("type", "boolean").put("value", value)
            is Set<*> -> out.put("type", "stringSet").put(
                "value",
                JSONArray(value.filterIsInstance<String>())
            )
            else -> out.put("type", "string").put("value", value?.toString().orEmpty())
        }
        return out
    }

    private fun restorePreferenceFiles(files: JSONObject?) {
        if (files == null) return
        PORTABLE_PREFS.forEach { name ->
            val entries = files.optJSONObject(name) ?: return@forEach
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            entries.keys().forEach { key ->
                val encoded = entries.optJSONObject(key) ?: return@forEach
                editor.putEncoded(key, encoded)
            }
            check(editor.commit()) { "Could not restore $name" }
        }
    }

    private fun SharedPreferences.Editor.putEncoded(
        key: String,
        encoded: JSONObject
    ): SharedPreferences.Editor = when (encoded.optString("type")) {
        "int" -> putInt(key, encoded.optInt("value"))
        "long" -> putLong(key, encoded.optLong("value"))
        "float" -> putFloat(key, encoded.optDouble("value").toFloat())
        "boolean" -> putBoolean(key, encoded.optBoolean("value"))
        "stringSet" -> {
            val arr = encoded.optJSONArray("value") ?: JSONArray()
            val values = buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            putStringSet(key, values)
        }
        else -> putString(key, encoded.optString("value"))
    }

    companion object {
        const val FILE_NAME = "PallasVideoPlayer-settings.json"
        private const val FORMAT = "pallas-settings-backup"
        private const val VERSION = 1

        // User-authored channel configuration only. Caches and viewing/search history
        // are intentionally rebuilt locally rather than copied between televisions.
        private val PORTABLE_PREFS = listOf(
            "iptv_favorites",
            "iptv_channel_custom",
            "iptv_parental",
            "podcasts",
        )
    }
}
