package com.vizvag.shieldvideo.data.nas

import com.vizvag.shieldvideo.BuildConfig

object NasPaths {
    val DEFAULT_BACKGROUND_FOLDER: String = BuildConfig.DEFAULT_BACKGROUND_FOLDER

    val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif"
    )

    val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm"
    )

    /** Archives shown in browse only when a folder has no video files. */
    val archiveExtensions = setOf("rar")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isVideoFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return extensionOf(base) in videoExtensions
    }

    /** Sidecar next to a video: `Movie.mkv` → `Movie.mkv.pallas.json` (hidden from browse). */
    const val PROGRESS_SIDECAR_SUFFIX = ".pallas.json"

    fun progressSidecarName(videoFileName: String): String =
        videoFileName.substringAfterLast('/') + PROGRESS_SIDECAR_SUFFIX

    fun isProgressSidecar(name: String): Boolean =
        name.substringAfterLast('/').endsWith(PROGRESS_SIDECAR_SUFFIX, ignoreCase = true)

    /** `Movie.mkv.pallas.json` → `Movie.mkv`, or null if not a progress sidecar. */
    fun videoNameFromProgressSidecar(sidecarName: String): String? {
        val base = sidecarName.substringAfterLast('/')
        if (!base.endsWith(PROGRESS_SIDECAR_SUFFIX, ignoreCase = true)) return null
        return base.dropLast(PROGRESS_SIDECAR_SUFFIX.length).takeIf { it.isNotEmpty() }
    }

    /** Relative path of the sidecar for a video path (same folder). */
    fun progressSidecarPath(videoRelativePath: String): String {
        val normalized = videoRelativePath.trim('/').replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        val name = normalized.substringAfterLast('/')
        val sidecar = progressSidecarName(name)
        return if (parent.isBlank()) sidecar else "$parent/$sidecar"
    }

    fun isArchiveFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return extensionOf(base) in archiveExtensions
    }

    /**
     * True for multi-volume RAR pieces: `.rar`, `.partN.rar`, `.r00` / `.r01`, …
     */
    fun isRarVolumeFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        val lower = base.lowercase()
        if (lower.endsWith(".rar")) return true
        return Regex("""\.r\d+$""").containsMatchIn(lower)
    }

    /**
     * Shared key for a multi-volume RAR set, or null if not a RAR volume name.
     * `Movie.part01.rar` … → `movie|part`; `Movie.rar` + `Movie.r00` → `movie|r`.
     */
    fun rarVolumeSetKey(name: String): String? {
        val base = name.substringAfterLast('/')
        Regex("""^(.+)\.part\d+\.rar$""", RegexOption.IGNORE_CASE).matchEntire(base)?.let {
            return it.groupValues[1].lowercase() + "|part"
        }
        Regex("""^(.+)\.r\d+$""", RegexOption.IGNORE_CASE).matchEntire(base)?.let {
            return it.groupValues[1].lowercase() + "|r"
        }
        if (base.endsWith(".rar", ignoreCase = true)) {
            return base.dropLast(4).lowercase() + "|r"
        }
        return null
    }

    /** How many volume files in [fileNames] belong to the same set as [selectedName]. */
    fun countRarVolumes(fileNames: Iterable<String>, selectedName: String): Int {
        val key = rarVolumeSetKey(selectedName) ?: return 1
        val count = fileNames.count { rarVolumeSetKey(it) == key }
        return count.coerceAtLeast(1)
    }

    private val commonShareCandidates = listOf(
        "download", "video", "docs", "photo", "music", "homes", "home",
        "multimedia", "media", "movies", "tv", "backup"
    )

    /** `/photo/Albums/…` → share=`photo`, folder=`Albums/…` */
    fun parseFolderPath(raw: String): Pair<String, String>? {
        val parts = raw.trim().trim('/').replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val share = parts.first()
        val folder = parts.drop(1).joinToString("/")
        return share to folder
    }

    fun toFolderPath(share: String, folder: String = ""): String {
        val s = share.trim().trim('/')
        val f = folder.trim().trim('/').replace('\\', '/')
        return if (f.isBlank()) "/$s" else "/$s/$f"
    }

    fun labelFor(path: String): String {
        val trimmed = path.trim().trim('/')
        return trimmed.substringAfterLast('/').ifBlank { trimmed.ifBlank { path } }
    }

    /**
     * Windows / Synology / macOS housekeeping directories — hide from browse, pickers, and indexes.
     * Matches `$RECYCLE.BIN`, `#recycle`, `@eaDir`, `System Volume Information`, etc.
     */
    fun isIgnoredDirectoryName(name: String): Boolean {
        val base = name.substringAfterLast('/').trim()
        if (base.isEmpty() || base == "." || base == "..") return false
        if (IGNORED_DIRECTORY_NAMES.any { it.equals(base, ignoreCase = true) }) return true
        // Windows recycle / volume metadata ($RECYCLE.BIN, …)
        if (base.startsWith("$")) return true
        // Synology internal (@eaDir, @tmp, …) and recycle/snapshots (#recycle, #snapshot)
        if (base.startsWith("@") || base.startsWith("#")) return true
        // Dot-directories (.Trash, .Spotlight-V100, …)
        if (base.startsWith(".")) return true
        return false
    }

    /** True when any path segment is an ignored system directory. */
    fun pathContainsIgnoredDirectory(path: String): Boolean =
        path.replace('\\', '/').split('/').any { isIgnoredDirectoryName(it) }

    fun shareCandidates(extra: List<String> = emptyList()): List<String> =
        (extra + commonShareCandidates)
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    private val IGNORED_DIRECTORY_NAMES = setOf(
        "screens",
        "RECYCLER",
        "Recycled",
        "System Volume Information",
        "lost+found",
        "Recovery",
        "\$RECYCLE.BIN", // also caught by '$' prefix; kept for clarity
    )
}
