package com.vizvag.shieldvideo.music.data.metadata

import android.content.Context
import com.vizvag.shieldvideo.music.data.LibraryRepository
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads NAS folder covers through the authenticated Synology client and
 * caches them as real image files Coil can load (FileStation download URLs are
 * unreliable as Coil models — SID/SSL/content-type quirks).
 */
class CoverArtCache(
    context: Context,
    private val synologyApiClient: SynologyApiClient,
    private val libraryRepository: LibraryRepository,
) {
    private val dir = File(context.cacheDir, "music_covers").also { it.mkdirs() }
    private val memory = ConcurrentHashMap<String, File>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun coverFileForTrack(track: TrackEntity): File? {
        val folder = track.nasPath.replace('\\', '/').substringBeforeLast('/')
            .takeIf { it.isNotBlank() } ?: return null
        val albumCover = runCatching { libraryRepository.getAlbum(track.albumId)?.coverPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val sceneCover = findSceneCoverInFolder(folder)
        val candidates = buildList {
            if (!albumCover.isNullOrBlank()) add(albumCover)
            if (!sceneCover.isNullOrBlank()) add(sceneCover)
            add("$folder/folder.jpg")
            add("$folder/cover.jpg")
            add("$folder/Folder.jpg")
            add("$folder/Cover.jpg")
            add("$folder/folder.png")
            add("$folder/cover.png")
        }.distinct()
        for (path in candidates) {
            coverFileForPath(path)?.let { return it }
        }
        return null
    }

    /** Scene releases often ship `00-artist-album-web-year.jpg` instead of folder.jpg. */
    private suspend fun findSceneCoverInFolder(folder: String): String? {
        val listed = runCatching { synologyApiClient.listFolder(folder) }.getOrNull() ?: return null
        val image = listed.files
            .asSequence()
            .filter { !it.isdir }
            .filter { entry ->
                val n = entry.name.lowercase()
                (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) &&
                    (n.startsWith("00-") || n.startsWith("00_") ||
                        n == "folder.jpg" || n == "cover.jpg" || n == "cover.png")
            }
            .maxByOrNull { it.additional?.size ?: 0 }
        return image?.path
    }

    suspend fun coverFileForPath(nasPath: String): File? = withContext(Dispatchers.IO) {
        val key = nasPath.replace('\\', '/').trim().lowercase()
        if (key.isBlank()) return@withContext null
        memory[key]?.takeIf { it.exists() && it.length() > 64L }?.let { return@withContext it }

        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            memory[key]?.takeIf { it.exists() && it.length() > 64L }?.let { return@withContext it }
            val bytes = runCatching {
                synologyApiClient.downloadBytes(nasPath, maxBytes = MAX_BYTES)
            }.getOrNull() ?: return@withContext null
            if (!isImageMagic(bytes)) return@withContext null
            val ext = imageExtension(bytes)
            val out = File(dir, "${key.hashCode().toUInt().toString(16)}.$ext")
            runCatching {
                out.writeBytes(bytes)
            }.onFailure {
                out.delete()
                return@withContext null
            }
            memory[key] = out
            out
        }
    }

    companion object {
        private const val MAX_BYTES = 3_000_000L

        private fun imageExtension(bytes: ByteArray): String = when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
            bytes.size >= 6 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() -> "gif"
            bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "webp"
            else -> "img"
        }

        private fun isImageMagic(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            if (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) return false
            return when (imageExtension(bytes)) {
                "jpg", "png", "gif", "webp" -> true
                else -> false
            }
        }
    }
}
