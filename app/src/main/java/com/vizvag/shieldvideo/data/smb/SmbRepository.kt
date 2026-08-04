package com.vizvag.shieldvideo.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

class SmbRepository {
    private val videoExtensions = NasPaths.videoExtensions

    private val config = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withDialects(
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0
        )
        .build()

    suspend fun testConnection(settings: AppSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = settings.defaultShare.ifBlank { settings.shares.first() }
            val (share, folder) = NasPaths.parseFolderPath(root) ?: (root.trim('/') to "")
            withShare(settings, share) { disk ->
                disk.list(folder)
                Unit
            }
        }
    }

    suspend fun listShares(settings: AppSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            NasPaths.shareCandidates(settings.shares).mapNotNull { share ->
                val ok = runCatching {
                    list(settings, share, "", emptySet()).isSuccess
                }.getOrDefault(false)
                share.takeIf { ok }
            }
        }
    }

    suspend fun list(
        settings: AppSettings,
        shareName: String,
        path: String,
        allowedExtensions: Set<String> = videoExtensions
    ): Result<List<SmbEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = path.trim('/').replace('\\', '/')
                val listing = share.list(normalized)
                listing
                    .asSequence()
                    .filter { it.fileName != "." && it.fileName != ".." }
                    .map { info -> info.toEntry(normalized) }
                    .filter { entry ->
                        entry.isDirectory || entry.name.substringAfterLast('.', "")
                            .lowercase() in allowedExtensions
                    }
                    .sortedWith(
                        compareByDescending<SmbEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
                    .toList()
            }
        }
    }

    suspend fun copyFileTo(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        destination: java.io.File
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                destination.parentFile?.mkdirs()
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { remote ->
                    destination.outputStream().use { out ->
                        remote.read(out)
                    }
                }
                destination
            }
        }
    }

    suspend fun readBytes(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        maxBytes: Int = 512 * 1024,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { remote ->
                    remote.inputStream.use { input ->
                        val buf = ByteArray(maxBytes.coerceAtLeast(1))
                        var off = 0
                        while (off < buf.size) {
                            val n = input.read(buf, off, buf.size - off)
                            if (n <= 0) break
                            off += n
                        }
                        buf.copyOf(off)
                    }
                }
            }
        }
    }

    suspend fun listNames(
        settings: AppSettings,
        shareName: String,
        folderPath: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = folderPath.trim('/').replace('\\', '/')
                share.list(normalized)
                    .asSequence()
                    .map { it.fileName }
                    .filter { it != "." && it != ".." }
                    .toList()
            }
        }
    }

    suspend fun readText(
        settings: AppSettings,
        shareName: String,
        relativePath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { remote ->
                    remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
        }
    }

    suspend fun writeText(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        contents: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.FILE_WRITE_DATA),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                ).use { remote ->
                    remote.outputStream.use { out ->
                        out.write(contents.toByteArray(Charsets.UTF_8))
                    }
                }
                Unit
            }
        }
    }

    suspend fun copyLocalFileTo(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        source: java.io.File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.FILE_WRITE_DATA),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                ).use { remote ->
                    source.inputStream().use { input ->
                        remote.outputStream.use { output -> input.copyTo(output, 256 * 1024) }
                    }
                }
                Unit
            }
        }
    }

    /** Delete a file or folder on the share. Folders are removed recursively. */
    suspend fun delete(
        settings: AppSettings,
        shareName: String,
        relativePath: String,
        isDirectory: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShare(settings, shareName) { share ->
                val normalized = relativePath.trim('/').replace('\\', '/')
                if (normalized.isBlank()) {
                    throw IllegalArgumentException("Refusing to delete share root")
                }
                if (isDirectory) {
                    share.rmdir(normalized, true)
                } else {
                    share.rm(normalized)
                }
                Unit
            }
        }
    }

    private fun FileIdBothDirectoryInformation.toEntry(parent: String): SmbEntry {
        val isDir = (fileAttributes.toLong() and 0x10L) != 0L
        val childPath = if (parent.isBlank()) fileName else "$parent/$fileName"
        return SmbEntry(
            name = fileName,
            path = childPath,
            isDirectory = isDir,
            size = endOfFile
        )
    }

    private fun <T> withShare(
        settings: AppSettings,
        shareName: String,
        block: (DiskShare) -> T
    ): T {
        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            connection = client.connect(settings.host, settings.port)
            val auth = AuthenticationContext(
                settings.username,
                settings.password.toCharArray(),
                null
            )
            session = connection.authenticate(auth)
            share = session.connectShare(shareName) as DiskShare
            return block(share)
        } finally {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }
}
