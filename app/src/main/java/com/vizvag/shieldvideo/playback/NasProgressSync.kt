package com.vizvag.shieldvideo.playback

import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Syncs per-video resume / watched state via a tiny NAS sidecar next to each file
 * (`Movie.mkv.pallas.json`). Invisible in browse (non-video extension).
 */
class NasProgressSync(
    private val nasRepository: NasRepository,
    private val resumeStore: LocalResumeStore,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val lastNasWriteAt = ConcurrentHashMap<String, Long>()
    private val writeMutex = Mutex()

    /**
     * Read the sidecar for [videoPath], merge into [resumeStore] when newer.
     * Returns the effective local record after merge (may be null).
     */
    suspend fun readAndMerge(
        settings: AppSettings,
        shareName: String,
        videoPath: String,
    ): LocalResume? {
        val remote = readSidecar(settings, shareName, videoPath) ?: return resumeStore.getIncludingWatched(videoPath)
        resumeStore.mergeRemote(remote.copy(path = videoPath))
        return resumeStore.getIncludingWatched(videoPath)
    }

    /**
     * Discover `*.pallas.json` in [folderPath] and merge any that match videos in [videoPaths].
     */
    suspend fun mergeFolder(
        settings: AppSettings,
        shareName: String,
        folderPath: String,
        videoPaths: Collection<String>,
    ) {
        if (videoPaths.isEmpty()) {
            mergeAllSidecarsInFolder(settings, shareName, folderPath)
            return
        }
        val byName = videoPaths.associateBy { it.substringAfterLast('/') }
        val names = nasRepository.listProgressSidecarNames(settings, shareName, folderPath)
            .getOrNull()
            ?: return
        for (sidecarName in names) {
            val videoName = NasPaths.videoNameFromProgressSidecar(sidecarName) ?: continue
            val videoPath = byName[videoName]
                ?: run {
                    val parent = folderPath.trim('/').replace('\\', '/')
                    if (parent.isBlank()) videoName else "$parent/$videoName"
                }
            readAndMerge(settings, shareName, videoPath)
        }
    }

    /**
     * Merge every `*.pallas.json` in [folderPath] into the local resume store.
     * Returns watched vs in-progress counts from those sidecars.
     */
    suspend fun mergeAllSidecarsInFolder(
        settings: AppSettings,
        shareName: String,
        folderPath: String,
    ): SidecarFolderProgress {
        val parent = folderPath.trim('/').replace('\\', '/')
        val names = nasRepository.listProgressSidecarNames(settings, shareName, parent)
            .getOrNull()
            ?: return SidecarFolderProgress(0, 0)
        var watched = 0
        var inProgress = 0
        for (sidecarName in names) {
            val videoName = NasPaths.videoNameFromProgressSidecar(sidecarName) ?: continue
            val videoPath = if (parent.isBlank()) videoName else "$parent/$videoName"
            val merged = readAndMerge(settings, shareName, videoPath) ?: continue
            when {
                merged.watched -> watched++
                merged.isMeaningful -> inProgress++
            }
        }
        return SidecarFolderProgress(watched = watched, inProgress = inProgress)
    }

    data class SidecarFolderProgress(
        val watched: Int,
        val inProgress: Int,
    )

    /** Merge sidecars next to each video (groups by parent folder). */
    suspend fun mergeVideos(
        settings: AppSettings,
        shareName: String,
        videoPaths: Collection<String>,
    ) {
        if (videoPaths.isEmpty()) return
        val byParent = videoPaths
            .map { it.trim('/').replace('\\', '/') }
            .filter { it.isNotBlank() }
            .groupBy { it.substringBeforeLast('/', missingDelimiterValue = "") }
        for ((parent, paths) in byParent) {
            mergeFolder(settings, shareName, parent, paths)
        }
    }

    /**
     * Persist progress to the NAS. When [force] is false, writes at most once per
     * [NAS_WRITE_INTERVAL_MS] per path. Always updates local prefs first via caller.
     */
    fun scheduleWrite(
        videoPath: String,
        shareName: String?,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false,
    ) {
        val settings = settingsRepository.load()
        val share = shareName?.takeIf { it.isNotBlank() } ?: return
        if (settings.host.isBlank() || settings.password.isBlank()) return

        val finished = durationMs > 0L && positionMs >= durationMs * LocalResume.WATCHED_FRACTION
        val meaningful = positionMs > LocalResume.MIN_RESUME_POSITION_MS &&
            (durationMs <= 0L || positionMs < durationMs * LocalResume.WATCHED_FRACTION)
        if (!finished && !meaningful) return

        val now = System.currentTimeMillis()
        if (!force && !finished) {
            val last = lastNasWriteAt[videoPath] ?: 0L
            if (now - last < NAS_WRITE_INTERVAL_MS) return
        }

        val entry = if (finished) {
            LocalResume(
                path = videoPath,
                positionMs = 0L,
                durationMs = durationMs,
                updatedAt = now,
                watched = true,
            )
        } else {
            LocalResume(
                path = videoPath,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = now,
                watched = false,
            )
        }

        lastNasWriteAt[videoPath] = now
        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    writeSidecar(settings, share, entry)
                }
            }
        }
    }

    /** Flush whatever is in the local store for [videoPath] to the NAS (e.g. on stop). */
    fun flushLocal(videoPath: String, shareName: String?) {
        val local = resumeStore.getIncludingWatched(videoPath) ?: return
        val share = shareName?.takeIf { it.isNotBlank() } ?: return
        val settings = settingsRepository.load()
        if (settings.host.isBlank() || settings.password.isBlank()) return
        if (!local.watched && !local.isMeaningful) return

        lastNasWriteAt[videoPath] = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    writeSidecar(settings, share, local)
                }
            }
        }
    }

    private suspend fun readSidecar(
        settings: AppSettings,
        shareName: String,
        videoPath: String,
    ): LocalResume? {
        val sidecarPath = NasPaths.progressSidecarPath(videoPath)
        val raw = nasRepository.readTextAt(settings, shareName, sidecarPath).getOrNull()
            ?: return null
        return parse(raw, videoPath)
    }

    private suspend fun writeSidecar(
        settings: AppSettings,
        shareName: String,
        entry: LocalResume,
    ) {
        val sidecarPath = NasPaths.progressSidecarPath(entry.path)
        val body = JSONObject()
            .put("v", 1)
            .put("positionMs", entry.positionMs)
            .put("durationMs", entry.durationMs)
            .put("watched", entry.watched)
            .put("updatedAt", entry.updatedAt)
            .put("deviceId", settings.deviceId.trim())
            .toString()
        nasRepository.writeTextAt(settings, shareName, sidecarPath, body)
    }

    private fun parse(raw: String, videoPath: String): LocalResume? =
        runCatching {
            val json = JSONObject(raw)
            LocalResume(
                path = videoPath,
                positionMs = json.optLong("positionMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                watched = json.optBoolean("watched", false),
            )
        }.getOrNull()

    companion object {
        const val NAS_WRITE_INTERVAL_MS = 30_000L
    }
}
