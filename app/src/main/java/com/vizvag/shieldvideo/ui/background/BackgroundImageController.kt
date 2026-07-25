package com.vizvag.shieldvideo.ui.background

import android.content.Context
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BackgroundImageController(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "backgrounds").apply { mkdirs() }

    private val _imageModel = MutableStateFlow<Any?>(null)
    val imageModel: StateFlow<Any?> = _imageModel.asStateFlow()

    private var loopJob: Job? = null
    private var lastPath: String? = null

    fun start(scope: CoroutineScope) {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshOnce()
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
    }

    fun reloadNow(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val settings = settingsRepository.load()
        if (settings.password.isBlank()) {
            _imageModel.value = null
            return
        }
        val folder = settings.backgroundFolderPath.ifBlank { NasPaths.DEFAULT_BACKGROUND_FOLDER }
        val parsed = NasPaths.parseFolderPath(folder) ?: run {
            _imageModel.value = null
            return
        }
        val (share, path) = parsed
        val listed = nasRepository.list(
            settings = settings,
            shareName = share,
            path = path,
            allowedExtensions = NasPaths.imageExtensions
        ).getOrElse {
            _imageModel.value = null
            return
        }
        val images = listed.filter { !it.isDirectory }
        if (images.isEmpty()) {
            _imageModel.value = null
            return
        }
        val candidates = images.filter { it.path != lastPath }.ifEmpty { images }
        val pick = candidates[Random.nextInt(candidates.size)]
        lastPath = pick.path

        val ext = pick.name.substringAfterLast('.', "jpg")
        val cacheFile = File(cacheDir, "bg_${pick.path.hashCode().toUInt().toString(16)}.$ext")
        val model = nasRepository.imageModel(
            settings = settings,
            shareName = share,
            relativePath = pick.path,
            cacheFile = cacheFile
        ).getOrNull()

        withContext(Dispatchers.Main.immediate) {
            _imageModel.value = model
        }

        // Keep only a few cached background files
        pruneCache(keep = cacheFile)
    }

    private fun pruneCache(keep: File) {
        val files = cacheDir.listFiles().orEmpty()
        files.filter { it != keep }
            .sortedByDescending { it.lastModified() }
            .drop(2)
            .forEach { it.delete() }
    }
}
