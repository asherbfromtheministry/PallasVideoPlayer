package com.vizvag.shieldvideo

import android.app.Application
import com.vizvag.shieldvideo.data.index.VideoIndexController
import com.vizvag.shieldvideo.data.index.VideoIndexStore
import com.vizvag.shieldvideo.data.iptv.IptvChannelCustomStore
import com.vizvag.shieldvideo.data.iptv.IptvFavoritesStore
import com.vizvag.shieldvideo.data.iptv.IptvParentalStore
import com.vizvag.shieldvideo.data.iptv.IptvRecordingStore
import com.vizvag.shieldvideo.data.iptv.IptvRepository
import com.vizvag.shieldvideo.data.iptv.IptvSearchHistoryStore
import com.vizvag.shieldvideo.data.iptv.IptvWatchHistoryStore
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.settings.SettingsBackupManager
import com.vizvag.shieldvideo.data.youtube.YoutubeNewPipeInit
import com.vizvag.shieldvideo.data.youtube.YoutubeRepository
import com.vizvag.shieldvideo.data.youtube.YoutubeWatchHistoryStore
import com.vizvag.shieldvideo.playback.HaNowPlayingPublisher
import com.vizvag.shieldvideo.playback.LocalMediaProxy
import com.vizvag.shieldvideo.playback.IptvRecordingScheduler
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.NowPlaying
import com.vizvag.shieldvideo.playback.NowPlayingStore
import com.vizvag.shieldvideo.playback.ResumeMonitor
import com.vizvag.shieldvideo.playback.SleepTimerController
import com.vizvag.shieldvideo.playback.BlackoutController
import com.vizvag.shieldvideo.playback.haPodcastEpisodesWebhookUrl
import com.vizvag.shieldvideo.playback.haRadioStationsWebhookUrl
import com.vizvag.shieldvideo.playback.haSleepWebhookUrl
import com.vizvag.shieldvideo.data.radio.RadioDefaults
import com.vizvag.shieldvideo.playback.iptv.IptvPlaybackController
import com.vizvag.shieldvideo.data.hue.HueMusicSync
import com.vizvag.shieldvideo.data.podcast.PodcastRepository
import com.vizvag.shieldvideo.playback.radio.RadioPlaybackController
import com.vizvag.shieldvideo.playback.podcast.PodcastPlaybackController
import com.vizvag.shieldvideo.playback.remote.PlaybackCommandRouter
import com.vizvag.shieldvideo.playback.remote.RemoteControlClient
import com.vizvag.shieldvideo.playback.remote.RemoteDeviceDiscovery
import com.vizvag.shieldvideo.playback.youtube.YoutubePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShieldVideoApp : Application() {
    lateinit var resumeStore: LocalResumeStore
        private set
    lateinit var progressSync: NasProgressSync
        private set
    lateinit var resumeMonitor: ResumeMonitor
        private set
    lateinit var sleepTimer: SleepTimerController
        private set
    lateinit var blackout: BlackoutController
        private set
    lateinit var nasWatchHistory: NasWatchHistoryStore
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var settingsBackupManager: SettingsBackupManager
        private set
    lateinit var nasRepository: NasRepository
        private set
    lateinit var videoIndex: VideoIndexController
        private set
    lateinit var localMediaProxy: LocalMediaProxy
        private set
    lateinit var iptvRepository: IptvRepository
        private set
    lateinit var iptvFavorites: IptvFavoritesStore
        private set
    lateinit var iptvChannelCustom: IptvChannelCustomStore
        private set
    lateinit var iptvParental: IptvParentalStore
        private set
    lateinit var iptvRecordings: IptvRecordingStore
        private set
    lateinit var iptvWatchHistory: IptvWatchHistoryStore
        private set
    lateinit var iptvSearchHistory: IptvSearchHistoryStore
        private set
    lateinit var youtubeRepository: YoutubeRepository
        private set
    lateinit var youtubeWatchHistory: YoutubeWatchHistoryStore
        private set
    lateinit var youtubeResolutionCache: com.vizvag.shieldvideo.data.youtube.YoutubeResolutionCache
        private set
    lateinit var musicModule: com.vizvag.shieldvideo.music.MusicModule
        private set
    lateinit var radioPlayback: RadioPlaybackController
        private set
    lateinit var podcastRepository: PodcastRepository
        private set
    lateinit var podcastPlayback: PodcastPlaybackController
        private set
    lateinit var hueSync: HueMusicSync
        private set
    lateinit var iptvPlayback: IptvPlaybackController
        private set
    lateinit var youtubePlayback: YoutubePlaybackController
        private set
    lateinit var playbackRouter: PlaybackCommandRouter
        private set
    lateinit var remoteClient: RemoteControlClient
        private set
    lateinit var remoteDiscovery: RemoteDeviceDiscovery
        private set
    private lateinit var haPublisher: HaNowPlayingPublisher

    private val appJob = SupervisorJob()
    val appScope = CoroutineScope(appJob + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        YoutubeNewPipeInit.ensureInitialized(this)
        resumeStore = LocalResumeStore(this)
        nasWatchHistory = NasWatchHistoryStore(this)
        settingsRepository = SettingsRepository(this)
        nasRepository = NasRepository()
        progressSync = NasProgressSync(
            nasRepository = nasRepository,
            resumeStore = resumeStore,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
        resumeMonitor = ResumeMonitor(
            context = this,
            resumeStore = resumeStore,
            nowPlayingStore = NowPlayingStore(this),
            settingsRepository = settingsRepository,
            progressSync = progressSync,
        )
        haPublisher = HaNowPlayingPublisher()
        settingsBackupManager = SettingsBackupManager(this, settingsRepository, nasRepository)
        localMediaProxy = LocalMediaProxy()
        iptvRepository = IptvRepository(this)
        iptvFavorites = IptvFavoritesStore(this)
        iptvChannelCustom = IptvChannelCustomStore(this)
        iptvParental = IptvParentalStore(this)
        iptvRecordings = IptvRecordingStore(this)
        IptvRecordingScheduler.restore(this)
        iptvWatchHistory = IptvWatchHistoryStore(this)
        iptvSearchHistory = IptvSearchHistoryStore(this)
        youtubeWatchHistory = YoutubeWatchHistoryStore(this)
        youtubeResolutionCache = com.vizvag.shieldvideo.data.youtube.YoutubeResolutionCache(this)
        youtubeRepository = YoutubeRepository(
            resolveBaseUrl = { settingsRepository.load().youtubePipedApiUrl },
            cacheDir = { cacheDir },
            resolveAccessToken = {
                val s = settingsRepository.load()
                s.youtubeAccessToken.takeIf {
                    it.isNotBlank() && s.youtubeAccessTokenExpiresAtMs > System.currentTimeMillis() + 15_000L
                }
            },
        )
        musicModule = com.vizvag.shieldvideo.music.MusicModule(this, settingsRepository, appScope)
        musicModule.musicIndex.start(appScope)
        radioPlayback = RadioPlaybackController(this)
        podcastRepository = PodcastRepository(this, settingsRepository, nasRepository)
        podcastPlayback = PodcastPlaybackController(this)
        hueSync = HueMusicSync(
            settingsRepository = settingsRepository,
            playerController = musicModule.playerController,
            musicProbe = musicModule.playerController.energyProbe,
            radioPlayback = radioPlayback,
            radioProbe = radioPlayback.energyProbe,
            appScope = appScope,
        )
        iptvPlayback = IptvPlaybackController(this)
        youtubePlayback = YoutubePlaybackController(this, settingsRepository)
        blackout = BlackoutController()
        // After all players exist so expiry can silence every source (not only the bound screen).
        sleepTimer = SleepTimerController(
            blackout = blackout,
            onApplyVolume = { applySleepVolume(it) },
            onRestoreVolume = { restoreSleepVolume() },
            onExpireFallback = {
                runCatching { resumeMonitor.stopPlayer() }
                runCatching { musicModule.playerController.stop() }
                runCatching { radioPlayback.stop() }
                runCatching { podcastPlayback.stop() }
                runCatching { youtubePlayback.stop() }
                runCatching { iptvPlayback.stop() }
                runCatching {
                    if (com.vizvag.shieldvideo.playback.RadioRecordingService.state.value.recording) {
                        com.vizvag.shieldvideo.playback.RadioRecordingService.finish(this)
                    }
                }
            },
            onStandby = { onResult ->
                val settings = settingsRepository.load()
                val url = haSleepWebhookUrl(settings.haWebhookUrl)
                val device = settings.deviceId.trim()
                if (!settings.sleepTimerHaStandby || url.isBlank() || device.isBlank()) {
                    onResult(false)
                    return@SleepTimerController
                }
                appScope.launch(Dispatchers.IO) {
                    val ok = haPublisher.requestSleepStandby(
                        url,
                        device,
                        blackout = blackout.isActive(),
                    )
                    launch(Dispatchers.Main.immediate) { onResult(ok) }
                }
            },
        )
        playbackRouter = PlaybackCommandRouter(this)
        remoteClient = RemoteControlClient()
        remoteDiscovery = RemoteDeviceDiscovery(this)
        videoIndex = VideoIndexController(
            settingsRepository,
            nasRepository,
            VideoIndexStore(this),
            musicModule.synologyApiClient,
        )

        val isTv = run {
            val uiMode = getSystemService(android.content.Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager
            uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature("android.software.leanback")
        }
        // Phones/tablets are remotes — skip heavy NAS video index + IPTV/EPG hydrate at cold start.
        if (isTv) {
            YoutubeNewPipeInit.warmPoToken(this)
            videoIndex.start(appScope)
            appScope.launch(Dispatchers.IO) {
                val playlist = settingsRepository.load().activeIptvPlaylist()
                iptvRepository.setExtraWantedEpgIds(iptvChannelCustom.allEpgIds(playlist.id))
                iptvRepository.ensureChannelsLoaded(playlist, forceRefresh = false)
                launch {
                    iptvRepository.ensureEpgLoaded(playlist, forceRefresh = false)
                    iptvRepository.refreshEpgIfStale(playlist)
                }
                iptvRepository.refreshChannelsIfStale(playlist)
            }
            publishRadioStationsToHa()
            publishPodcastEpisodesToHa()
        }
    }

    /** Push radio catalog to HA webhook `pallas_radio_stations` (derived from now-playing URL). */
    fun publishRadioStationsToHa() {
        val settings = settingsRepository.load()
        val url = haRadioStationsWebhookUrl(settings.haWebhookUrl)
        if (url.isBlank()) return
        val stations = settings.customRadioStations.ifEmpty { RadioDefaults.stations() }
        haPublisher.publishRadioStations(
            webhookUrl = url,
            deviceId = settings.deviceId,
            stations = stations.map { Triple(it.id, it.name, it.tagline) },
        )
    }

    /** Push recent podcast episodes to HA webhook `pallas_podcast_episodes`. */
    fun publishPodcastEpisodesToHa() {
        val settings = settingsRepository.load()
        val url = haPodcastEpisodesWebhookUrl(settings.haWebhookUrl)
        if (url.isBlank()) return
        appScope.launch(Dispatchers.IO) {
            // Warm disk caches (network only when stale/missing) so HA sees recent episodes.
            podcastRepository.subscriptions.value.forEach { show ->
                runCatching { podcastRepository.episodesForShow(show, forceRefresh = false) }
            }
            val episodes = podcastRepository.recentEpisodesForHa()
            if (episodes.isEmpty()) return@launch
            haPublisher.publishPodcastEpisodes(
                webhookUrl = url,
                deviceId = settings.deviceId,
                episodes = episodes.map { ep ->
                    mapOf(
                        "guid" to ep.guid,
                        "showId" to ep.showId,
                        "showTitle" to ep.showTitle,
                        "title" to ep.title,
                        "label" to ep.label,
                    )
                },
            )
        }
    }

    /** Push current radio station/title to the shared now-playing webhook. */
    fun publishRadioNowPlayingToHa() {
        val settings = settingsRepository.load()
        val url = settings.haWebhookUrl.trim()
        if (url.isBlank()) return
        val radio = radioPlayback.state.value
        val title = radio.title.ifBlank { radio.stationName }.trim()
        if (radio.stationId.isBlank() || title.isBlank()) return
        haPublisher.publish(
            webhookUrl = url,
            session = NowPlaying(
                deviceId = settings.deviceId,
                uri = "",
                path = "radio:${radio.stationId}",
                share = "radio",
                host = "",
                title = title,
                positionMs = 0L,
                durationMs = 0L,
            ),
            force = true,
        )
    }

    fun clearRadioNowPlayingToHa() {
        val settings = settingsRepository.load()
        val url = settings.haWebhookUrl.trim()
        if (url.isBlank()) return
        haPublisher.clear(url, settings.deviceId)
    }

    /** Push current podcast episode to the shared now-playing webhook. */
    fun publishPodcastNowPlayingToHa() {
        val settings = settingsRepository.load()
        val url = settings.haWebhookUrl.trim()
        if (url.isBlank()) return
        val podcast = podcastPlayback.state.value
        if (podcast.episodeGuid.isBlank()) return
        val title = buildString {
            if (podcast.showTitle.isNotBlank()) append(podcast.showTitle)
            if (podcast.episodeTitle.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(podcast.episodeTitle)
            }
        }.ifBlank { return }
        haPublisher.publish(
            webhookUrl = url,
            session = NowPlaying(
                deviceId = settings.deviceId,
                uri = podcast.audioUrl,
                path = "podcast:${podcast.episodeGuid}",
                share = "podcast",
                host = "",
                title = title,
                positionMs = podcast.positionMs,
                durationMs = podcast.durationMs,
            ),
            force = true,
        )
    }

    fun clearPodcastNowPlayingToHa() {
        val settings = settingsRepository.load()
        val url = settings.haWebhookUrl.trim()
        if (url.isBlank()) return
        haPublisher.clear(url, settings.deviceId)
    }

    /** Sleep timer volume fade — applied to whichever in-app player is active. */
    private fun applySleepVolume(volume: Float) {
        fun androidx.media3.common.Player.applyIfActive() {
            if (mediaItemCount > 0 && (isPlaying || playWhenReady)) {
                this.volume = volume
            }
        }
        runCatching { musicModule.playerController.player.applyIfActive() }
        runCatching { radioPlayback.player.applyIfActive() }
        runCatching { podcastPlayback.player.applyIfActive() }
        runCatching { youtubePlayback.player.applyIfActive() }
        runCatching { iptvPlayback.player.applyIfActive() }
    }

    private fun restoreSleepVolume() {
        runCatching { musicModule.playerController.player.volume = 1f }
        runCatching { radioPlayback.player.volume = 1f }
        runCatching { podcastPlayback.player.volume = 1f }
        runCatching { youtubePlayback.player.volume = 1f }
        runCatching { iptvPlayback.player.volume = 1f }
    }

    companion object {
        lateinit var instance: ShieldVideoApp
            private set
    }
}
