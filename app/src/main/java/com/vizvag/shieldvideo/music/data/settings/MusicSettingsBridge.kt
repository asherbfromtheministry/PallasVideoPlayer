package com.vizvag.shieldvideo.music.data.settings

import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Maps shared Pallas NAS credentials into music-library File Station settings. */
class MusicSettingsBridge(
    private val appSettings: SettingsRepository,
) {
    private val revision = MutableStateFlow(0)

    val settings: Flow<NasSettings> = revision.map { toNasSettings(appSettings.load()) }

    fun refresh() {
        revision.value++
    }

    suspend fun currentSettings(): NasSettings = toNasSettings(appSettings.load())

    suspend fun save(settings: NasSettings) {
        val current = appSettings.load()
        appSettings.save(
            current.copy(
                host = settings.host,
                username = settings.username,
                password = settings.password,
                musicPaths = NasSettings.normalizePaths(settings.musicPaths),
                musicUseHttps = settings.useHttps,
                musicTrustSelfSigned = settings.trustSelfSigned,
                port = when {
                    current.connectionMode == ConnectionMode.HTTP -> settings.port
                    else -> current.port
                },
            ),
        )
        refresh()
    }

    private fun toNasSettings(settings: AppSettings): NasSettings {
        val port = when (settings.connectionMode) {
            ConnectionMode.HTTP -> settings.port
            else -> if (settings.musicUseHttps) NasSettings.HTTPS_PORT else NasSettings.HTTP_PORT
        }
        return NasSettings(
            host = settings.host,
            port = NasSettings.portForProtocol(settings.musicUseHttps, port),
            useHttps = settings.musicUseHttps,
            musicPaths = AppSettings.normalizeMusicPaths(settings.musicPaths),
            username = settings.username,
            password = settings.password,
            trustSelfSigned = settings.musicTrustSelfSigned,
        )
    }
}
