package com.vizvag.shieldvideo.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.music.data.LibraryRepository
import com.vizvag.shieldvideo.music.data.MusicIndexController
import com.vizvag.shieldvideo.music.data.MusicLibraryCache
import com.vizvag.shieldvideo.music.data.local.MusicDatabase
import com.vizvag.shieldvideo.music.data.lyrics.LyricsRepository
import com.vizvag.shieldvideo.music.data.metadata.AlbumArtLookup
import com.vizvag.shieldvideo.music.data.metadata.CoverArtCache
import com.vizvag.shieldvideo.music.data.settings.MusicSettingsBridge
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import com.vizvag.shieldvideo.music.player.PlayerController
import com.vizvag.shieldvideo.music.player.QueueManager
import com.vizvag.shieldvideo.music.player.StreamUrlBuilder
import com.vizvag.shieldvideo.music.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MusicModule(
    context: Context,
    settingsRepository: SettingsRepository,
    appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val database: MusicDatabase = Room.databaseBuilder(
        appContext,
        MusicDatabase::class.java,
        "pallas_music.db",
    ).fallbackToDestructiveMigration().build()

    val musicSettings = MusicSettingsBridge(settingsRepository)

    val synologyApiClient = SynologyApiClient(musicSettings, okHttpClient)

    val libraryRepository = LibraryRepository(
        context = appContext,
        artistDao = database.artistDao(),
        albumDao = database.albumDao(),
        trackDao = database.trackDao(),
        libraryIndexDao = database.libraryIndexDao(),
        playHistoryDao = database.playHistoryDao(),
        synologyApiClient = synologyApiClient,
        musicSettings = musicSettings,
        settingsRepository = settingsRepository,
        smbRepository = com.vizvag.shieldvideo.data.smb.SmbRepository(),
    )

    val musicIndex = MusicIndexController(libraryRepository, musicSettings, appScope)

    /** Warm Room library in RAM for the whole process lifetime. */
    val libraryCache = MusicLibraryCache(libraryRepository, appScope)

    val queueManager = QueueManager(appContext)
    val streamUrlBuilder = StreamUrlBuilder(synologyApiClient)
    val playerController = PlayerController(
        context = appContext,
        queueManager = queueManager,
        streamUrlBuilder = streamUrlBuilder,
        libraryRepository = libraryRepository,
    )

    val lyricsRepository = LyricsRepository(
        synologyApiClient = synologyApiClient,
        settingsRepository = settingsRepository,
        smbRepository = com.vizvag.shieldvideo.data.smb.SmbRepository(),
    )

    val albumArtLookup = AlbumArtLookup(okHttpClient)

    val coverArtCache = CoverArtCache(
        context = appContext,
        synologyApiClient = synologyApiClient,
        libraryRepository = libraryRepository,
    )
}

class MusicViewModelFactory(
    private val musicModule: MusicModule,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            return MusicViewModel(
                libraryRepository = musicModule.libraryRepository,
                libraryCache = musicModule.libraryCache,
                musicSettings = musicModule.musicSettings,
                synologyApiClient = musicModule.synologyApiClient,
                musicIndex = musicModule.musicIndex,
                streamUrlBuilder = musicModule.streamUrlBuilder,
                queueManager = musicModule.queueManager,
                playerController = musicModule.playerController,
                albumArtLookup = musicModule.albumArtLookup,
                coverArtCache = musicModule.coverArtCache,
                lyricsRepository = musicModule.lyricsRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
