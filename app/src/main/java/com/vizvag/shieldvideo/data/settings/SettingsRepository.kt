package com.vizvag.shieldvideo.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vizvag.shieldvideo.BuildConfig
import com.vizvag.shieldvideo.data.iptv.EpgAiMatcher
import com.vizvag.shieldvideo.data.iptv.EpgAiProvider
import com.vizvag.shieldvideo.data.iptv.IptvDefaults
import com.vizvag.shieldvideo.data.iptv.IptvPlaylistConfig
import com.vizvag.shieldvideo.data.radio.CustomRadioStationConfig
import com.vizvag.shieldvideo.data.radio.RadioDefaults
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.youtube.YoutubeDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class IptvRecordingStorage {
    LOCAL,
    NAS;

    companion object {
        fun fromStorage(value: String?): IptvRecordingStorage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}

data class AppSettings(
    val host: String = BuildConfig.DEFAULT_NAS_HOST,
    val port: Int = 445,
    val connectionMode: ConnectionMode = ConnectionMode.SMB3,
    val username: String = BuildConfig.DEFAULT_NAS_USER,
    val password: String = "",
    val shares: List<String> = listOf("download", "video", "docs"),
    val defaultShare: String = "download",
    val backgroundFolderPath: String = NasPaths.DEFAULT_BACKGROUND_FOLDER,
    val backupFolderPath: String = "",
    val playerPackage: String = "org.videolan.vlc",
    val deviceId: String = "",
    /**
     * Shared secret for LAN remote control (Bearer token). Auto-generated on first load;
     * included in settings backup so phones/tablets match the TVs after import.
     */
    val remoteToken: String = "",
    /** When true and [deviceId] is set, advertise NSD and accept LAN control commands. */
    val allowRemoteControl: Boolean = true,
    val haWebhookUrl: String = BuildConfig.DEFAULT_HA_WEBHOOK,
    /** When enabled, sleep timer expiry POSTs to the HA sleep webhook (standby). */
    val sleepTimerHaStandby: Boolean = false,
    /** Music/Radio Philips Hue light sync over the local bridge API. */
    val hueEnabled: Boolean = false,
    val hueBridgeIp: String = BuildConfig.DEFAULT_HUE_BRIDGE_IP,
    /** Bridge username/token from pairing (link button). */
    val hueUsername: String = "",
    /** Selected Hue light IDs (CLIP v1 numeric ids as strings). */
    val hueLightIds: List<String> = emptyList(),
    val traktClientId: String = BuildConfig.DEFAULT_TRAKT_CLIENT_ID,
    val traktClientSecret: String = "",
    val traktUsername: String = BuildConfig.DEFAULT_TRAKT_USERNAME,
    val traktSlug: String = BuildConfig.DEFAULT_TRAKT_USERNAME.substringBefore('@'),
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val tmdbApiKey: String = BuildConfig.DEFAULT_TMDB_API_KEY,
    val tmdbReadToken: String = BuildConfig.DEFAULT_TMDB_READ_TOKEN,
    val iptvPlaylists: List<IptvPlaylistConfig> = IptvDefaults.defaultPlaylists(),
    val activeIptvPlaylistId: String = "default",
    val iptvShowEpgInList: Boolean = true,
    val iptvGuideSize: IptvGuideSize = IptvGuideSize.Medium,
    val iptvCompactRows: Boolean = false,
    val iptvOpenInVlc: Boolean = true,
    val iptvRecordingStorage: IptvRecordingStorage = IptvRecordingStorage.LOCAL,
    /** Persisted Storage Access Framework tree URI; blank uses app-local Movies/IPTV Recordings. */
    val iptvRecordingLocalTreeUri: String = "",
    /** NAS path in /share/folder form. */
    val iptvRecordingNasFolder: String = "",
    /**
     * API key for AI EPG matching (Gemini free key or OpenAI / compatible).
     * Used by Live TV → hold group → AI match EPG.
     */
    val iptvEpgAiApiKey: String = BuildConfig.DEFAULT_IPTV_EPG_AI_KEY,
    val iptvEpgAiProvider: EpgAiProvider = EpgAiProvider.GEMINI,
    /** OpenAI-compatible base URL when provider is OPENAI. */
    val iptvEpgAiOpenAiBaseUrl: String = EpgAiMatcher.DEFAULT_OPENAI_BASE,
    /** NAS music library folder paths (e.g. /music). Indexed and shown in Music → Folders. */
    val musicPaths: List<String> = defaultMusicPaths(),
    val musicUseHttps: Boolean = false,
    val musicTrustSelfSigned: Boolean = true,
    val customRadioStations: List<CustomRadioStationConfig> = emptyList(),
    /** Piped API base URL for search / stream fallback. */
    val youtubePipedApiUrl: String = YoutubeDefaults.DEFAULT_PIPED_API_URL,
    /** Piped account username (optional legacy feed). */
    val youtubePipedUsername: String = "",
    /** Piped session token (optional legacy feed). */
    val youtubePipedAuthToken: String = "",
    /** Stable device id for YouTube TV OAuth pairing. */
    val youtubeTvDeviceId: String = "",
    /** YouTube TV OAuth refresh token (real Google/YouTube account). */
    val youtubeRefreshToken: String = "",
    /** Cached access token; refreshed as needed. */
    val youtubeAccessToken: String = "",
    /** Epoch ms when [youtubeAccessToken] expires. */
    val youtubeAccessTokenExpiresAtMs: Long = 0L,
    /** Display name from last successful link (optional). */
    val youtubeAccountName: String = "",
    /** On-screen clock corner (subtle time + long date). */
    val clockCorner: ClockCorner = ClockCorner.BottomRight,
    /**
     * Weaker devices (e.g. Chromecast HD): skip blurred art, looping EQ/ambient
     * animations, and other continuous GPU effects.
     */
    val liteVisuals: Boolean = false,
    /** Home landing hotspots — which room tiles are shown. */
    val homeShowRadio: Boolean = true,
    val homeShowMusic: Boolean = true,
    val homeShowLibrary: Boolean = true,
    val homeShowYouTube: Boolean = false,
    val homeShowLiveTv: Boolean = true,
    val homeShowPodcasts: Boolean = true,
    /**
     * Full NAS path to a Podcast Addict (or standard) OPML file,
     * e.g. `/backups/Backup files/podcast addict/export.opml`.
     */
    val podcastOpmlNasPath: String = "",
) {
    val isTraktLinked: Boolean get() = traktAccessToken.isNotBlank()
    /** Linked via YouTube TV device code, or legacy Piped session. */
    val isYoutubeLoggedIn: Boolean
        get() = youtubeRefreshToken.isNotBlank() || youtubePipedAuthToken.isNotBlank()
    val isYoutubeTvLinked: Boolean get() = youtubeRefreshToken.isNotBlank()
    val isYoutubePipedLoggedIn: Boolean get() = youtubePipedAuthToken.isNotBlank()

    /** Primary music folder (first configured path). */
    val musicPath: String
        get() = musicPaths.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_MUSIC_PATH.ifBlank { "/music" }

    fun activeIptvPlaylist(): IptvPlaylistConfig =
        iptvPlaylists.firstOrNull { it.id == activeIptvPlaylistId && it.enabled }
            ?: iptvPlaylists.firstOrNull { it.enabled }
            ?: IptvDefaults.defaultPlaylists().first()

    companion object {
        fun defaultMusicPaths(): List<String> {
            val raw = BuildConfig.DEFAULT_MUSIC_PATH.trim()
            return if (raw.isBlank()) listOf("/music") else listOf(normalizeMusicPath(raw))
        }

        fun normalizeMusicPath(path: String): String {
            val trimmed = path.trim().replace('\\', '/')
            if (trimmed.isBlank() || trimmed == "/") return "/music"
            return if (trimmed.startsWith("/")) trimmed.trimEnd('/') else "/${trimmed.trimEnd('/')}"
        }

        fun normalizeMusicPaths(paths: List<String>): List<String> =
            paths.map { normalizeMusicPath(it) }
                .filter { it.isNotBlank() && it != "/" }
                .distinctBy { it.lowercase() }
                .ifEmpty { defaultMusicPaths() }
    }
}

class SettingsRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "shield_video_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _revision = MutableStateFlow(0L)
    /** Bumps whenever settings are saved so UI overlays can refresh. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun load(): AppSettings {
        val sharesRaw = prefs.getString(KEY_SHARES, "download,video,docs").orEmpty()
        val shares = sharesRaw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("download", "video", "docs") }

        val username = prefs.getString(KEY_TRAKT_USERNAME, BuildConfig.DEFAULT_TRAKT_USERNAME)
            ?: BuildConfig.DEFAULT_TRAKT_USERNAME
        val defaultSlug = username.substringBefore('@')

        return AppSettings(
            host = prefs.getString(KEY_HOST, BuildConfig.DEFAULT_NAS_HOST) ?: BuildConfig.DEFAULT_NAS_HOST,
            port = prefs.getInt(KEY_PORT, 445),
            connectionMode = ConnectionMode.fromStorage(prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.SMB3.name)),
            username = prefs.getString(KEY_USERNAME, BuildConfig.DEFAULT_NAS_USER) ?: BuildConfig.DEFAULT_NAS_USER,
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            shares = shares,
            defaultShare = prefs.getString(KEY_DEFAULT_SHARE, "download") ?: "download",
            backgroundFolderPath = prefs.getString(
                KEY_BACKGROUND_FOLDER,
                NasPaths.DEFAULT_BACKGROUND_FOLDER
            ) ?: NasPaths.DEFAULT_BACKGROUND_FOLDER,
            backupFolderPath = prefs.getString(KEY_BACKUP_FOLDER, "") ?: "",
            playerPackage = prefs.getString(KEY_PLAYER_PACKAGE, "org.videolan.vlc")
                ?: "org.videolan.vlc",
            deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: "",
            remoteToken = ensureRemoteToken(),
            allowRemoteControl = prefs.getBoolean(KEY_ALLOW_REMOTE_CONTROL, true),
            haWebhookUrl = prefs.getString(
                KEY_HA_WEBHOOK,
                BuildConfig.DEFAULT_HA_WEBHOOK
            ) ?: BuildConfig.DEFAULT_HA_WEBHOOK,
            sleepTimerHaStandby = prefs.getBoolean(KEY_SLEEP_TIMER_HA_STANDBY, false),
            hueEnabled = prefs.getBoolean(KEY_HUE_ENABLED, false),
            hueBridgeIp = prefs.getString(KEY_HUE_BRIDGE_IP, BuildConfig.DEFAULT_HUE_BRIDGE_IP)
                ?: BuildConfig.DEFAULT_HUE_BRIDGE_IP,
            hueUsername = prefs.getString(KEY_HUE_USERNAME, "") ?: "",
            hueLightIds = prefs.getString(KEY_HUE_LIGHT_IDS, "")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            traktClientId = prefs.getString(KEY_TRAKT_CLIENT_ID, BuildConfig.DEFAULT_TRAKT_CLIENT_ID)
                ?: BuildConfig.DEFAULT_TRAKT_CLIENT_ID,
            traktClientSecret = prefs.getString(KEY_TRAKT_CLIENT_SECRET, "") ?: "",
            traktUsername = username,
            traktSlug = prefs.getString(KEY_TRAKT_SLUG, defaultSlug) ?: defaultSlug,
            traktAccessToken = prefs.getString(KEY_TRAKT_ACCESS, "") ?: "",
            traktRefreshToken = prefs.getString(KEY_TRAKT_REFRESH, "") ?: "",
            tmdbApiKey = prefs.getString(KEY_TMDB_API_KEY, BuildConfig.DEFAULT_TMDB_API_KEY)
                ?: BuildConfig.DEFAULT_TMDB_API_KEY,
            tmdbReadToken = prefs.getString(KEY_TMDB_READ_TOKEN, BuildConfig.DEFAULT_TMDB_READ_TOKEN)
                ?: BuildConfig.DEFAULT_TMDB_READ_TOKEN,
            iptvPlaylists = loadPlaylists(),
            activeIptvPlaylistId = prefs.getString(KEY_IPTV_ACTIVE, "default") ?: "default",
            iptvShowEpgInList = prefs.getBoolean(KEY_IPTV_SHOW_EPG, true),
            iptvGuideSize = IptvGuideSize.fromStorage(prefs.getString(KEY_IPTV_GUIDE_SIZE, IptvGuideSize.Medium.name)),
            iptvCompactRows = prefs.getBoolean(KEY_IPTV_COMPACT, false),
            iptvOpenInVlc = prefs.getBoolean(KEY_IPTV_OPEN_VLC, true),
            iptvRecordingStorage = IptvRecordingStorage.fromStorage(
                prefs.getString(KEY_IPTV_RECORDING_STORAGE, IptvRecordingStorage.LOCAL.name)
            ),
            iptvRecordingLocalTreeUri = prefs.getString(KEY_IPTV_RECORDING_LOCAL_TREE, "") ?: "",
            iptvRecordingNasFolder = prefs.getString(KEY_IPTV_RECORDING_NAS_FOLDER, "") ?: "",
            iptvEpgAiApiKey = prefs.getString(KEY_IPTV_EPG_AI_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?: BuildConfig.DEFAULT_IPTV_EPG_AI_KEY,
            iptvEpgAiProvider = EpgAiProvider.fromStorage(
                prefs.getString(KEY_IPTV_EPG_AI_PROVIDER, EpgAiProvider.GEMINI.name)
            ),
            iptvEpgAiOpenAiBaseUrl = prefs.getString(
                KEY_IPTV_EPG_AI_OPENAI_BASE,
                EpgAiMatcher.DEFAULT_OPENAI_BASE
            ) ?: EpgAiMatcher.DEFAULT_OPENAI_BASE,
            musicPaths = loadMusicPaths(),
            musicUseHttps = prefs.getBoolean(KEY_MUSIC_USE_HTTPS, false),
            musicTrustSelfSigned = prefs.getBoolean(KEY_MUSIC_TRUST_SELF_SIGNED, true),
            customRadioStations = loadCustomRadioStations(),
            youtubePipedApiUrl = run {
                val raw = prefs.getString(KEY_YOUTUBE_PIPED_API, null)
                val normalized = YoutubeDefaults.normalizeApiUrl(
                    raw ?: YoutubeDefaults.DEFAULT_PIPED_API_URL
                )
                // Persist migration off the dead default so Settings shows the live URL.
                if (raw != normalized) {
                    prefs.edit().putString(KEY_YOUTUBE_PIPED_API, normalized).apply()
                }
                normalized
            },
            youtubePipedUsername = prefs.getString(KEY_YOUTUBE_PIPED_USER, "") ?: "",
            youtubePipedAuthToken = prefs.getString(KEY_YOUTUBE_PIPED_TOKEN, "") ?: "",
            youtubeTvDeviceId = prefs.getString(KEY_YOUTUBE_TV_DEVICE_ID, "") ?: "",
            youtubeRefreshToken = prefs.getString(KEY_YOUTUBE_REFRESH_TOKEN, "") ?: "",
            youtubeAccessToken = prefs.getString(KEY_YOUTUBE_ACCESS_TOKEN, "") ?: "",
            youtubeAccessTokenExpiresAtMs = prefs.getLong(KEY_YOUTUBE_ACCESS_EXPIRES, 0L),
            youtubeAccountName = prefs.getString(KEY_YOUTUBE_ACCOUNT_NAME, "") ?: "",
            clockCorner = ClockCorner.fromStorage(
                prefs.getString(KEY_CLOCK_CORNER, ClockCorner.BottomRight.name)
            ),
            liteVisuals = prefs.getBoolean(KEY_LITE_VISUALS, false),
            homeShowRadio = prefs.getBoolean(KEY_HOME_SHOW_RADIO, true),
            homeShowMusic = prefs.getBoolean(KEY_HOME_SHOW_MUSIC, true),
            homeShowLibrary = prefs.getBoolean(KEY_HOME_SHOW_LIBRARY, true),
            homeShowYouTube = prefs.getBoolean(KEY_HOME_SHOW_YOUTUBE, false),
            homeShowLiveTv = prefs.getBoolean(KEY_HOME_SHOW_LIVETV, true),
            homeShowPodcasts = prefs.getBoolean(KEY_HOME_SHOW_PODCASTS, true),
            podcastOpmlNasPath = prefs.getString(KEY_PODCAST_OPML_NAS_PATH, "") ?: "",
        )
    }

    private fun loadMusicPaths(): List<String> {
        val multi = prefs.getString(KEY_MUSIC_PATHS, null)
        if (!multi.isNullOrBlank()) {
            return AppSettings.normalizeMusicPaths(
                multi.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
        val legacy = prefs.getString(KEY_MUSIC_PATH, BuildConfig.DEFAULT_MUSIC_PATH)
            ?: BuildConfig.DEFAULT_MUSIC_PATH
        return AppSettings.normalizeMusicPaths(listOf(legacy))
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_CONNECTION_MODE, settings.connectionMode.name)
            .putString(KEY_USERNAME, settings.username.trim())
            .putString(KEY_PASSWORD, settings.password)
            .putString(KEY_SHARES, settings.shares.joinToString(",") { it.trim() })
            .putString(KEY_DEFAULT_SHARE, settings.defaultShare.trim())
            .putString(KEY_BACKGROUND_FOLDER, settings.backgroundFolderPath.trim())
            .putString(KEY_BACKUP_FOLDER, settings.backupFolderPath.trim())
            .putString(KEY_PLAYER_PACKAGE, settings.playerPackage.trim().ifBlank { "org.videolan.vlc" })
            .putString(KEY_DEVICE_ID, settings.deviceId.trim().lowercase())
            .putString(
                KEY_REMOTE_TOKEN,
                settings.remoteToken.trim().ifBlank { ensureRemoteToken() },
            )
            .putBoolean(KEY_ALLOW_REMOTE_CONTROL, settings.allowRemoteControl)
            .putString(KEY_HA_WEBHOOK, settings.haWebhookUrl.trim())
            .putBoolean(KEY_SLEEP_TIMER_HA_STANDBY, settings.sleepTimerHaStandby)
            .putBoolean(KEY_HUE_ENABLED, settings.hueEnabled)
            .putString(KEY_HUE_BRIDGE_IP, settings.hueBridgeIp.trim())
            .putString(KEY_HUE_USERNAME, settings.hueUsername.trim())
            .putString(
                KEY_HUE_LIGHT_IDS,
                settings.hueLightIds.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
            )
            .putString(KEY_TRAKT_CLIENT_ID, settings.traktClientId.trim())
            .putString(KEY_TRAKT_CLIENT_SECRET, settings.traktClientSecret.trim())
            .putString(KEY_TRAKT_USERNAME, settings.traktUsername.trim())
            .putString(KEY_TRAKT_SLUG, settings.traktSlug.trim())
            .putString(KEY_TRAKT_ACCESS, settings.traktAccessToken)
            .putString(KEY_TRAKT_REFRESH, settings.traktRefreshToken)
            .putString(KEY_TMDB_API_KEY, settings.tmdbApiKey.trim())
            .putString(KEY_TMDB_READ_TOKEN, settings.tmdbReadToken.trim())
            .putString(KEY_IPTV_PLAYLISTS, encodePlaylists(settings.iptvPlaylists))
            .putString(KEY_IPTV_ACTIVE, settings.activeIptvPlaylistId.trim().ifBlank { "default" })
            .putBoolean(KEY_IPTV_SHOW_EPG, settings.iptvShowEpgInList)
            .putString(KEY_IPTV_GUIDE_SIZE, settings.iptvGuideSize.name)
            .putBoolean(KEY_IPTV_COMPACT, settings.iptvCompactRows)
            .putBoolean(KEY_IPTV_OPEN_VLC, settings.iptvOpenInVlc)
            .putString(KEY_IPTV_RECORDING_STORAGE, settings.iptvRecordingStorage.name)
            .putString(KEY_IPTV_RECORDING_LOCAL_TREE, settings.iptvRecordingLocalTreeUri)
            .putString(KEY_IPTV_RECORDING_NAS_FOLDER, settings.iptvRecordingNasFolder.trim())
            .putString(KEY_IPTV_EPG_AI_KEY, settings.iptvEpgAiApiKey.trim())
            .putString(KEY_IPTV_EPG_AI_PROVIDER, settings.iptvEpgAiProvider.name)
            .putString(
                KEY_IPTV_EPG_AI_OPENAI_BASE,
                settings.iptvEpgAiOpenAiBaseUrl.trim().ifBlank { EpgAiMatcher.DEFAULT_OPENAI_BASE }
            )
            .putString(
                KEY_MUSIC_PATHS,
                AppSettings.normalizeMusicPaths(settings.musicPaths).joinToString(",") { it.trim() },
            )
            .putString(KEY_MUSIC_PATH, settings.musicPath.trim())
            .putBoolean(KEY_MUSIC_USE_HTTPS, settings.musicUseHttps)
            .putBoolean(KEY_MUSIC_TRUST_SELF_SIGNED, settings.musicTrustSelfSigned)
            .putString(KEY_CUSTOM_RADIO_STATIONS, encodeCustomRadioStations(settings.customRadioStations))
            .putString(
                KEY_YOUTUBE_PIPED_API,
                YoutubeDefaults.normalizeApiUrl(settings.youtubePipedApiUrl),
            )
            .putString(KEY_YOUTUBE_PIPED_USER, settings.youtubePipedUsername.trim())
            .putString(KEY_YOUTUBE_PIPED_TOKEN, settings.youtubePipedAuthToken)
            .putString(KEY_YOUTUBE_TV_DEVICE_ID, settings.youtubeTvDeviceId.trim())
            .putString(KEY_YOUTUBE_REFRESH_TOKEN, settings.youtubeRefreshToken)
            .putString(KEY_YOUTUBE_ACCESS_TOKEN, settings.youtubeAccessToken)
            .putLong(KEY_YOUTUBE_ACCESS_EXPIRES, settings.youtubeAccessTokenExpiresAtMs)
            .putString(KEY_YOUTUBE_ACCOUNT_NAME, settings.youtubeAccountName.trim())
            .putString(KEY_CLOCK_CORNER, settings.clockCorner.name)
            .putBoolean(KEY_LITE_VISUALS, settings.liteVisuals)
            .putBoolean(KEY_HOME_SHOW_RADIO, settings.homeShowRadio)
            .putBoolean(KEY_HOME_SHOW_MUSIC, settings.homeShowMusic)
            .putBoolean(KEY_HOME_SHOW_LIBRARY, settings.homeShowLibrary)
            .putBoolean(KEY_HOME_SHOW_YOUTUBE, settings.homeShowYouTube)
            .putBoolean(KEY_HOME_SHOW_LIVETV, settings.homeShowLiveTv)
            .putBoolean(KEY_HOME_SHOW_PODCASTS, settings.homeShowPodcasts)
            .putString(KEY_PODCAST_OPML_NAS_PATH, settings.podcastOpmlNasPath.trim())
            .apply()
        _revision.value = System.currentTimeMillis()
    }

    fun encodeForBackup(settings: AppSettings = load()): JSONObject =
        JSONObject()
            .put("host", settings.host)
            .put("port", settings.port)
            .put("connectionMode", settings.connectionMode.name)
            .put("username", settings.username)
            .put("password", settings.password)
            .put("shares", JSONArray(settings.shares))
            .put("defaultShare", settings.defaultShare)
            .put("backgroundFolderPath", settings.backgroundFolderPath)
            .put("backupFolderPath", settings.backupFolderPath)
            .put("playerPackage", settings.playerPackage)
            .put("deviceId", settings.deviceId)
            .put("remoteToken", settings.remoteToken)
            .put("allowRemoteControl", settings.allowRemoteControl)
            .put("haWebhookUrl", settings.haWebhookUrl)
            .put("sleepTimerHaStandby", settings.sleepTimerHaStandby)
            .put("hueEnabled", settings.hueEnabled)
            .put("hueBridgeIp", settings.hueBridgeIp)
            .put("hueUsername", settings.hueUsername)
            .put("hueLightIds", JSONArray(settings.hueLightIds))
            .put("traktClientId", settings.traktClientId)
            .put("traktClientSecret", settings.traktClientSecret)
            .put("traktUsername", settings.traktUsername)
            .put("traktSlug", settings.traktSlug)
            .put("traktAccessToken", settings.traktAccessToken)
            .put("traktRefreshToken", settings.traktRefreshToken)
            .put("tmdbApiKey", settings.tmdbApiKey)
            .put("tmdbReadToken", settings.tmdbReadToken)
            .put("iptvPlaylists", JSONArray(encodePlaylists(settings.iptvPlaylists)))
            .put("activeIptvPlaylistId", settings.activeIptvPlaylistId)
            .put("iptvShowEpgInList", settings.iptvShowEpgInList)
            .put("iptvGuideSize", settings.iptvGuideSize.name)
            .put("iptvCompactRows", settings.iptvCompactRows)
            .put("iptvOpenInVlc", settings.iptvOpenInVlc)
            .put("iptvRecordingStorage", settings.iptvRecordingStorage.name)
            .put("iptvRecordingLocalTreeUri", settings.iptvRecordingLocalTreeUri)
            .put("iptvRecordingNasFolder", settings.iptvRecordingNasFolder)
            .put("iptvEpgAiApiKey", settings.iptvEpgAiApiKey)
            .put("iptvEpgAiProvider", settings.iptvEpgAiProvider.name)
            .put("iptvEpgAiOpenAiBaseUrl", settings.iptvEpgAiOpenAiBaseUrl)
            .put("musicPaths", JSONArray(AppSettings.normalizeMusicPaths(settings.musicPaths)))
            .put("musicPath", settings.musicPath)
            .put("musicUseHttps", settings.musicUseHttps)
            .put("musicTrustSelfSigned", settings.musicTrustSelfSigned)
            .put("customRadioStations", encodeCustomRadioStationsJson(settings.customRadioStations))
            .put(
                "youtubePipedApiUrl",
                YoutubeDefaults.normalizeApiUrl(settings.youtubePipedApiUrl),
            )
            .put("youtubePipedUsername", settings.youtubePipedUsername)
            .put("youtubePipedAuthToken", settings.youtubePipedAuthToken)
            .put("youtubeTvDeviceId", settings.youtubeTvDeviceId)
            .put("youtubeRefreshToken", settings.youtubeRefreshToken)
            .put("youtubeAccessToken", settings.youtubeAccessToken)
            .put("youtubeAccessTokenExpiresAtMs", settings.youtubeAccessTokenExpiresAtMs)
            .put("youtubeAccountName", settings.youtubeAccountName)
            .put("clockCorner", settings.clockCorner.name)
            .put("liteVisuals", settings.liteVisuals)
            .put("homeShowRadio", settings.homeShowRadio)
            .put("homeShowMusic", settings.homeShowMusic)
            .put("homeShowLibrary", settings.homeShowLibrary)
            .put("homeShowYouTube", settings.homeShowYouTube)
            .put("homeShowLiveTv", settings.homeShowLiveTv)
            .put("homeShowPodcasts", settings.homeShowPodcasts)
            .put("podcastOpmlNasPath", settings.podcastOpmlNasPath)

    fun decodeBackup(obj: JSONObject): AppSettings {
        val defaults = AppSettings()
        val playlists = decodePlaylists(obj.optJSONArray("iptvPlaylists"))
        return AppSettings(
            host = obj.optString("host", defaults.host),
            port = obj.optInt("port", defaults.port),
            connectionMode = ConnectionMode.fromStorage(
                obj.optString("connectionMode", defaults.connectionMode.name)
            ),
            username = obj.optString("username", defaults.username),
            password = obj.optString("password", defaults.password),
            shares = obj.optJSONArray("shares")?.toStringList().orEmpty()
                .ifEmpty { defaults.shares },
            defaultShare = obj.optString("defaultShare", defaults.defaultShare),
            backgroundFolderPath = obj.optString(
                "backgroundFolderPath",
                defaults.backgroundFolderPath
            ),
            backupFolderPath = obj.optString("backupFolderPath", defaults.backupFolderPath),
            playerPackage = obj.optString("playerPackage", defaults.playerPackage),
            deviceId = obj.optString("deviceId", defaults.deviceId),
            remoteToken = obj.optString("remoteToken", defaults.remoteToken),
            allowRemoteControl = obj.optBoolean(
                "allowRemoteControl",
                defaults.allowRemoteControl
            ),
            haWebhookUrl = obj.optString("haWebhookUrl", defaults.haWebhookUrl),
            sleepTimerHaStandby = obj.optBoolean(
                "sleepTimerHaStandby",
                defaults.sleepTimerHaStandby
            ),
            hueEnabled = obj.optBoolean("hueEnabled", defaults.hueEnabled),
            hueBridgeIp = obj.optString("hueBridgeIp", defaults.hueBridgeIp),
            hueUsername = obj.optString("hueUsername", defaults.hueUsername),
            hueLightIds = obj.optJSONArray("hueLightIds")?.toStringList().orEmpty()
                .ifEmpty {
                    obj.optString("hueLightIds", "")
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                },
            traktClientId = obj.optString("traktClientId", defaults.traktClientId),
            traktClientSecret = obj.optString("traktClientSecret", defaults.traktClientSecret),
            traktUsername = obj.optString("traktUsername", defaults.traktUsername),
            traktSlug = obj.optString("traktSlug", defaults.traktSlug),
            traktAccessToken = obj.optString("traktAccessToken", defaults.traktAccessToken),
            traktRefreshToken = obj.optString("traktRefreshToken", defaults.traktRefreshToken),
            tmdbApiKey = obj.optString("tmdbApiKey", defaults.tmdbApiKey),
            tmdbReadToken = obj.optString("tmdbReadToken", defaults.tmdbReadToken),
            iptvPlaylists = playlists.ifEmpty { defaults.iptvPlaylists },
            activeIptvPlaylistId = obj.optString(
                "activeIptvPlaylistId",
                defaults.activeIptvPlaylistId
            ),
            iptvShowEpgInList = obj.optBoolean(
                "iptvShowEpgInList",
                defaults.iptvShowEpgInList
            ),
            iptvGuideSize = IptvGuideSize.fromStorage(
                obj.optString("iptvGuideSize", defaults.iptvGuideSize.name)
            ),
            iptvCompactRows = obj.optBoolean("iptvCompactRows", defaults.iptvCompactRows),
            iptvOpenInVlc = obj.optBoolean("iptvOpenInVlc", defaults.iptvOpenInVlc),
            iptvRecordingStorage = IptvRecordingStorage.fromStorage(
                obj.optString("iptvRecordingStorage", defaults.iptvRecordingStorage.name)
            ),
            iptvRecordingLocalTreeUri = obj.optString(
                "iptvRecordingLocalTreeUri",
                defaults.iptvRecordingLocalTreeUri
            ),
            iptvRecordingNasFolder = obj.optString(
                "iptvRecordingNasFolder",
                defaults.iptvRecordingNasFolder
            ),
            iptvEpgAiApiKey = obj.optString("iptvEpgAiApiKey", defaults.iptvEpgAiApiKey),
            iptvEpgAiProvider = EpgAiProvider.fromStorage(
                obj.optString("iptvEpgAiProvider", defaults.iptvEpgAiProvider.name)
            ),
            iptvEpgAiOpenAiBaseUrl = obj.optString(
                "iptvEpgAiOpenAiBaseUrl",
                defaults.iptvEpgAiOpenAiBaseUrl
            ),
            musicPaths = obj.optJSONArray("musicPaths")?.toStringList().orEmpty()
                .ifEmpty {
                    listOf(obj.optString("musicPath", defaults.musicPath))
                }
                .let { AppSettings.normalizeMusicPaths(it) },
            musicUseHttps = obj.optBoolean("musicUseHttps", defaults.musicUseHttps),
            musicTrustSelfSigned = obj.optBoolean(
                "musicTrustSelfSigned",
                defaults.musicTrustSelfSigned
            ),
            customRadioStations = if (obj.has("customRadioStations")) {
                decodeCustomRadioStations(obj.optJSONArray("customRadioStations"))
            } else {
                RadioDefaults.stations()
            },
            youtubePipedApiUrl = YoutubeDefaults.normalizeApiUrl(
                obj.optString("youtubePipedApiUrl", defaults.youtubePipedApiUrl)
            ),
            youtubePipedUsername = obj.optString(
                "youtubePipedUsername",
                defaults.youtubePipedUsername
            ),
            youtubePipedAuthToken = obj.optString(
                "youtubePipedAuthToken",
                defaults.youtubePipedAuthToken
            ),
            youtubeTvDeviceId = obj.optString("youtubeTvDeviceId", defaults.youtubeTvDeviceId),
            youtubeRefreshToken = obj.optString(
                "youtubeRefreshToken",
                defaults.youtubeRefreshToken
            ),
            youtubeAccessToken = obj.optString(
                "youtubeAccessToken",
                defaults.youtubeAccessToken
            ),
            youtubeAccessTokenExpiresAtMs = obj.optLong(
                "youtubeAccessTokenExpiresAtMs",
                defaults.youtubeAccessTokenExpiresAtMs
            ),
            youtubeAccountName = obj.optString(
                "youtubeAccountName",
                defaults.youtubeAccountName
            ),
            clockCorner = ClockCorner.fromStorage(
                obj.optString("clockCorner", defaults.clockCorner.name)
            ),
            liteVisuals = obj.optBoolean("liteVisuals", defaults.liteVisuals),
            homeShowRadio = obj.optBoolean("homeShowRadio", defaults.homeShowRadio),
            homeShowMusic = obj.optBoolean("homeShowMusic", defaults.homeShowMusic),
            homeShowLibrary = obj.optBoolean("homeShowLibrary", defaults.homeShowLibrary),
            homeShowYouTube = obj.optBoolean("homeShowYouTube", defaults.homeShowYouTube),
            homeShowLiveTv = obj.optBoolean("homeShowLiveTv", defaults.homeShowLiveTv),
            homeShowPodcasts = obj.optBoolean("homeShowPodcasts", defaults.homeShowPodcasts),
            podcastOpmlNasPath = obj.optString("podcastOpmlNasPath", defaults.podcastOpmlNasPath),
        )
    }

    private fun loadCustomRadioStations(): List<CustomRadioStationConfig> {
        val raw = prefs.getString(KEY_CUSTOM_RADIO_STATIONS, null)
        if (raw == null) {
            val defaults = RadioDefaults.stations()
            prefs.edit()
                .putString(KEY_CUSTOM_RADIO_STATIONS, encodeCustomRadioStations(defaults))
                .apply()
            return defaults
        }
        return decodeCustomRadioStationsFromString(raw)
    }

    private fun encodeCustomRadioStations(stations: List<CustomRadioStationConfig>): String =
        encodeCustomRadioStationsJson(stations).toString()

    private fun encodeCustomRadioStationsJson(
        stations: List<CustomRadioStationConfig>
    ): JSONArray {
        val arr = JSONArray()
        stations.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("tagline", s.tagline)
                    .put("streamUrl", s.streamUrl)
                    .put("bbcServiceId", s.bbcServiceId)
            )
        }
        return arr
    }

    private fun decodeCustomRadioStationsFromString(raw: String): List<CustomRadioStationConfig> =
        runCatching {
            decodeCustomRadioStations(JSONArray(raw))
        }.getOrElse { emptyList() }

    private fun decodeCustomRadioStations(arr: JSONArray?): List<CustomRadioStationConfig> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(
                    CustomRadioStationConfig(
                        id = item.optString("id").ifBlank {
                            java.util.UUID.randomUUID().toString()
                        },
                        name = item.optString("name", "Radio"),
                        tagline = item.optString("tagline", ""),
                        streamUrl = item.optString("streamUrl", ""),
                        bbcServiceId = item.optString("bbcServiceId", "")
                    )
                )
            }
        }
    }

    private fun loadPlaylists(): List<IptvPlaylistConfig> {
        val raw = prefs.getString(KEY_IPTV_PLAYLISTS, null)
        if (raw.isNullOrBlank()) return IptvDefaults.defaultPlaylists()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        IptvPlaylistConfig(
                            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            name = o.optString("name", "Playlist"),
                            m3uUrl = o.optString("m3uUrl"),
                            epgUrl = o.optString("epgUrl"),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }.ifEmpty { IptvDefaults.defaultPlaylists() }
        }.getOrElse { IptvDefaults.defaultPlaylists() }
    }

    private fun encodePlaylists(playlists: List<IptvPlaylistConfig>): String {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("m3uUrl", p.m3uUrl)
                    .put("epgUrl", p.epgUrl)
                    .put("enabled", p.enabled)
            )
        }
        return arr.toString()
    }

    private fun decodePlaylists(arr: JSONArray?): List<IptvPlaylistConfig> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(
                    IptvPlaylistConfig(
                        id = item.optString("id").ifBlank {
                            java.util.UUID.randomUUID().toString()
                        },
                        name = item.optString("name", "Playlist"),
                        m3uUrl = item.optString("m3uUrl"),
                        epgUrl = item.optString("epgUrl"),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    fun saveTraktTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_TRAKT_ACCESS, access)
            .putString(KEY_TRAKT_REFRESH, refresh)
            .apply()
    }

    fun clearTraktTokens() {
        prefs.edit()
            .remove(KEY_TRAKT_ACCESS)
            .remove(KEY_TRAKT_REFRESH)
            .apply()
    }

    fun clearYoutubeTvTokens() {
        prefs.edit()
            .remove(KEY_YOUTUBE_REFRESH_TOKEN)
            .remove(KEY_YOUTUBE_ACCESS_TOKEN)
            .remove(KEY_YOUTUBE_ACCESS_EXPIRES)
            .remove(KEY_YOUTUBE_ACCOUNT_NAME)
            .apply()
    }

    fun isHueSyncReady(): Boolean {
        val s = load()
        return s.hueUsername.isNotBlank() && s.hueLightIds.isNotEmpty()
    }

    fun isHueSyncEnabled(): Boolean = load().hueEnabled

    /** Flip Music/Radio Hue sync and persist. Returns new enabled state, or null if not set up. */
    fun toggleHueSync(): Boolean? {
        val s = load()
        if (s.hueUsername.isBlank() || s.hueLightIds.isEmpty()) return null
        val next = !s.hueEnabled
        save(s.copy(hueEnabled = next))
        return next
    }

    /** Persist a new UUID once if missing so LAN remotes can authenticate. */
    private fun ensureRemoteToken(): String {
        val existing = prefs.getString(KEY_REMOTE_TOKEN, null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_REMOTE_TOKEN, generated).apply()
        return generated
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SHARES = "shares"
        private const val KEY_DEFAULT_SHARE = "default_share"
        private const val KEY_BACKGROUND_FOLDER = "background_folder"
        private const val KEY_BACKUP_FOLDER = "backup_folder"
        private const val KEY_PLAYER_PACKAGE = "player_package"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REMOTE_TOKEN = "remote_token"
        private const val KEY_ALLOW_REMOTE_CONTROL = "allow_remote_control"
        private const val KEY_HA_WEBHOOK = "ha_webhook_url"
        private const val KEY_SLEEP_TIMER_HA_STANDBY = "sleep_timer_ha_standby"
        private const val KEY_HUE_ENABLED = "hue_enabled"
        private const val KEY_HUE_BRIDGE_IP = "hue_bridge_ip"
        private const val KEY_HUE_USERNAME = "hue_username"
        private const val KEY_HUE_LIGHT_IDS = "hue_light_ids"
        private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
        private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
        private const val KEY_TRAKT_USERNAME = "trakt_username"
        private const val KEY_TRAKT_SLUG = "trakt_slug"
        private const val KEY_TRAKT_ACCESS = "trakt_access"
        private const val KEY_TRAKT_REFRESH = "trakt_refresh"
        private const val KEY_TMDB_API_KEY = "tmdb_api_key"
        private const val KEY_TMDB_READ_TOKEN = "tmdb_read_token"
        private const val KEY_IPTV_PLAYLISTS = "iptv_playlists_json"
        private const val KEY_IPTV_ACTIVE = "iptv_active_playlist"
        private const val KEY_IPTV_SHOW_EPG = "iptv_show_epg"
        private const val KEY_IPTV_GUIDE_SIZE = "iptv_guide_size"
        private const val KEY_IPTV_COMPACT = "iptv_compact_rows"
        private const val KEY_IPTV_OPEN_VLC = "iptv_open_vlc"
        private const val KEY_IPTV_RECORDING_STORAGE = "iptv_recording_storage"
        private const val KEY_IPTV_RECORDING_LOCAL_TREE = "iptv_recording_local_tree"
        private const val KEY_IPTV_RECORDING_NAS_FOLDER = "iptv_recording_nas_folder"
        private const val KEY_IPTV_EPG_AI_KEY = "iptv_epg_ai_api_key"
        private const val KEY_IPTV_EPG_AI_PROVIDER = "iptv_epg_ai_provider"
        private const val KEY_IPTV_EPG_AI_OPENAI_BASE = "iptv_epg_ai_openai_base"
        private const val KEY_MUSIC_PATH = "music_path"
        private const val KEY_MUSIC_PATHS = "music_paths"
        private const val KEY_MUSIC_USE_HTTPS = "music_use_https"
        private const val KEY_MUSIC_TRUST_SELF_SIGNED = "music_trust_self_signed"
        private const val KEY_CUSTOM_RADIO_STATIONS = "custom_radio_stations_json"
        private const val KEY_YOUTUBE_PIPED_API = "youtube_piped_api_url"
        private const val KEY_YOUTUBE_PIPED_USER = "youtube_piped_username"
        private const val KEY_YOUTUBE_PIPED_TOKEN = "youtube_piped_auth_token"
        private const val KEY_YOUTUBE_TV_DEVICE_ID = "youtube_tv_device_id"
        private const val KEY_YOUTUBE_REFRESH_TOKEN = "youtube_refresh_token"
        private const val KEY_YOUTUBE_ACCESS_TOKEN = "youtube_access_token"
        private const val KEY_YOUTUBE_ACCESS_EXPIRES = "youtube_access_expires_ms"
        private const val KEY_YOUTUBE_ACCOUNT_NAME = "youtube_account_name"
        private const val KEY_CLOCK_CORNER = "clock_corner"
        private const val KEY_LITE_VISUALS = "lite_visuals"
        private const val KEY_HOME_SHOW_RADIO = "home_show_radio"
        private const val KEY_HOME_SHOW_MUSIC = "home_show_music"
        private const val KEY_HOME_SHOW_LIBRARY = "home_show_library"
        private const val KEY_HOME_SHOW_YOUTUBE = "home_show_youtube"
        private const val KEY_HOME_SHOW_LIVETV = "home_show_livetv"
        private const val KEY_HOME_SHOW_PODCASTS = "home_show_podcasts"
        private const val KEY_PODCAST_OPML_NAS_PATH = "podcast_opml_nas_path"
    }
}
