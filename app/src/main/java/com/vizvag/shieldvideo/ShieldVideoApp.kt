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
import com.vizvag.shieldvideo.playback.HaNowPlayingPublisher
import com.vizvag.shieldvideo.playback.LocalMediaProxy
import com.vizvag.shieldvideo.playback.IptvRecordingScheduler
import com.vizvag.shieldvideo.playback.LocalResumeStore
import com.vizvag.shieldvideo.playback.NasProgressSync
import com.vizvag.shieldvideo.playback.NasWatchHistoryStore
import com.vizvag.shieldvideo.playback.NowPlayingStore
import com.vizvag.shieldvideo.playback.ResumeMonitor
import com.vizvag.shieldvideo.playback.SleepTimerController
import com.vizvag.shieldvideo.playback.haSleepWebhookUrl
import com.vizvag.shieldvideo.ui.background.BackgroundImageController
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
    lateinit var nasWatchHistory: NasWatchHistoryStore
        private set
    lateinit var backgroundImages: BackgroundImageController
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
    lateinit var musicModule: com.vizvag.shieldvideo.music.MusicModule
        private set

    private val appJob = SupervisorJob()
    val appScope = CoroutineScope(appJob + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        val haPublisher = HaNowPlayingPublisher()
        sleepTimer = SleepTimerController(
            onExpireFallback = { resumeMonitor.stopPlayer() },
            onStandby = {
                val settings = settingsRepository.load()
                if (!settings.sleepTimerHaStandby) return@SleepTimerController
                haPublisher.requestSleepStandby(
                    haSleepWebhookUrl(settings.haWebhookUrl),
                    settings.deviceId
                )
            }
        )
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
        musicModule = com.vizvag.shieldvideo.music.MusicModule(this, settingsRepository, appScope)
        musicModule.musicIndex.start(appScope)
        videoIndex = VideoIndexController(
            settingsRepository,
            nasRepository,
            VideoIndexStore(this),
            musicModule.synologyApiClient,
        )
        backgroundImages = BackgroundImageController(this, settingsRepository, nasRepository)
        backgroundImages.start(appScope)
        videoIndex.start(appScope)
        // Prefetch Live TV catalog so opening the screen is instant from local cache.
        appScope.launch(Dispatchers.IO) {
            val playlist = settingsRepository.load().activeIptvPlaylist()
            // Manual channel→EPG assignments must be known before the EPG parse,
            // otherwise assigned channels are missing until a force refresh.
            iptvRepository.setExtraWantedEpgIds(iptvChannelCustom.allEpgIds(playlist.id))
            iptvRepository.ensureChannelsLoaded(playlist, forceRefresh = false)
            iptvRepository.ensureEpgLoaded(playlist, forceRefresh = false)
            iptvRepository.refreshChannelsIfStale(playlist)
            iptvRepository.refreshEpgIfStale(playlist)
        }
    }

    companion object {
        lateinit var instance: ShieldVideoApp
            private set
    }
}
