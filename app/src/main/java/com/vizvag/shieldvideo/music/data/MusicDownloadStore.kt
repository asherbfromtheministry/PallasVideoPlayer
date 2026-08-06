package com.vizvag.shieldvideo.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves NAS audio into the device public Music folder (MediaStore), under Music/Pallas/.
 */
object MusicDownloadStore {
    private const val SUBDIR = "Pallas"

    fun mimeForFileName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            else -> "audio/*"
        }
    }

    fun displayNameFor(nasPath: String): String =
        nasPath.replace('\\', '/').substringAfterLast('/').ifBlank { "track" }

    /**
     * Download [nasPath] from the NAS into Music/Pallas/[fileName].
     * @return content [Uri] of the saved file
     */
    suspend fun downloadNasFile(
        context: Context,
        api: SynologyApiClient,
        nasPath: String,
        displayName: String = displayNameFor(nasPath),
    ): Uri = withContext(Dispatchers.IO) {
        val mime = mimeForFileName(displayName)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/$SUBDIR",
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Could not create Music file")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    api.downloadTo(nasPath, out)
                } ?: throw IOException("Could not open Music file for writing")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val destDir = File(musicRoot, SUBDIR).also { it.mkdirs() }
            val dest = uniqueFile(destDir, displayName)
            dest.outputStream().use { out -> api.downloadTo(nasPath, out) }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DATA, dest.absolutePath)
                put(MediaStore.Audio.Media.DISPLAY_NAME, dest.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, dest.nameWithoutExtension)
            }
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: Uri.fromFile(dest)
        }
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var candidate = File(dir, displayName)
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}
