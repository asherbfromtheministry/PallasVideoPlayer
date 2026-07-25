package com.vizvag.shieldvideo.playback

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import java.io.File

/**
 * Saves a finished capture to the same Local / NAS recording folder configured
 * under Settings → Live TV → Recording storage (shared by IPTV and Radio).
 */
object RecordingDestination {
    suspend fun save(context: Context, source: File, fileName: String): String {
        val settings = SettingsRepository(context).load()
        return when (settings.iptvRecordingStorage) {
            IptvRecordingStorage.NAS -> {
                check(settings.iptvRecordingNasFolder.isNotBlank()) {
                    "Select a NAS recording folder in Settings → Live TV"
                }
                NasRepository().writeRecordingFile(
                    settings,
                    settings.iptvRecordingNasFolder,
                    fileName,
                    source
                ).getOrThrow()
                "${settings.iptvRecordingNasFolder.trimEnd('/')}/$fileName"
            }
            IptvRecordingStorage.LOCAL -> {
                if (settings.iptvRecordingLocalTreeUri.isNotBlank()) {
                    val tree = Uri.parse(settings.iptvRecordingLocalTreeUri)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree)
                    )
                    val mime = mimeForFileName(fileName)
                    val document = DocumentsContract.createDocument(
                        context.contentResolver,
                        parent,
                        mime,
                        fileName
                    ) ?: error("Unable to create recording in selected local folder")
                    context.contentResolver.openOutputStream(document, "w")!!.use { output ->
                        source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                    }
                    document.toString()
                } else {
                    val dir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                        "IPTV Recordings"
                    ).also { it.mkdirs() }
                    val target = File(dir, fileName)
                    source.copyTo(target, overwrite = true)
                    target.absolutePath
                }
            }
        }
    }

    fun safeFilePart(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)

    private fun mimeForFileName(fileName: String): String = when {
        fileName.endsWith(".mp4", true) -> "video/mp4"
        fileName.endsWith(".m4a", true) -> "audio/mp4"
        fileName.endsWith(".mp3", true) -> "audio/mpeg"
        fileName.endsWith(".aac", true) -> "audio/aac"
        fileName.endsWith(".ts", true) -> "video/mp2t"
        else -> "application/octet-stream"
    }
}
