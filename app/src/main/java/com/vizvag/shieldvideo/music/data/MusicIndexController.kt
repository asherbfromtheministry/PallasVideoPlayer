package com.vizvag.shieldvideo.music.data

import com.vizvag.shieldvideo.music.data.settings.MusicSettingsBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Runs music library indexing on an app-scoped coroutine so it keeps going
 * when the user leaves Music or Settings.
 *
 * Sync prefers Synology Audio Station's media index (tags already on the NAS),
 * then writes a local Room cache (`pallas_music.db`). Falls back to File Station
 * scan + tag download only if Audio Station is unavailable.
 *
 * Auto-syncs in the background on an interval and on app start if the cache is
 * empty or older than [STALE_AFTER_MS]. Opening Music does **not** trigger a sync.
 * Manual Sync still forces an immediate refresh.
 */
class MusicIndexController(
    private val libraryRepository: LibraryRepository,
    private val musicSettings: MusicSettingsBridge,
    private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var indexJob: Job? = null
    private var loopJob: Job? = null

    init {
        appScope.launch(Dispatchers.IO) {
            runCatching { libraryRepository.clearStaleIndexingFlag() }
        }
    }

    fun observeIndexState(): Flow<com.vizvag.shieldvideo.music.data.local.LibraryIndexStateEntity?> =
        libraryRepository.observeIndexState()

    /** Background loop: keep the local cache close to Audio Station. */
    fun start(scope: CoroutineScope = appScope) {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                ensureFresh(force = false)
                delay(STALE_AFTER_MS)
            }
        }
    }

    /** Pull latest if empty/stale, or always when [force] is true. */
    fun ensureFresh(force: Boolean = false) {
        if (indexJob?.isActive == true) return
        indexJob = appScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val settings = runCatching { musicSettings.currentSettings() }.getOrNull()
                    ?: return@withLock
                if (!settings.isConfigured) return@withLock
                if (!force) {
                    val state = libraryRepository.getIndexState()
                    val ageMs = if (state == null || state.lastIndexedAt <= 0L) {
                        Long.MAX_VALUE
                    } else {
                        System.currentTimeMillis() - state.lastIndexedAt
                    }
                    val empty = state == null || state.trackCount <= 0
                    if (!empty && ageMs < STALE_AFTER_MS) return@withLock
                }
                runCatching { libraryRepository.indexLibrary(forceFull = force) }
            }
        }
    }

    /** Manual Sync: always refresh from the NAS index. */
    fun syncNow() = ensureFresh(force = true)

    /** Settings rebuild: force full File Station re-parse when falling back. */
    fun rebuildNow() {
        if (indexJob?.isActive == true) return
        indexJob = appScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val settings = runCatching { musicSettings.currentSettings() }.getOrNull()
                    ?: return@withLock
                if (!settings.isConfigured) return@withLock
                runCatching { libraryRepository.indexLibrary(forceFull = true) }
            }
        }
    }

    fun isIndexing(): Boolean = indexJob?.isActive == true

    companion object {
        /** Local Room cache is enough for search — only refresh from NAS this often. */
        val STALE_AFTER_MS: Long = TimeUnit.HOURS.toMillis(24)
    }
}
