package com.vizvag.shieldvideo.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

data class InstalledVideoPlayer(
    val packageName: String,
    val label: String,
    val isVlc: Boolean = packageName == MediaPlayerLauncher.VLC_PACKAGE
)

sealed class PlayerLaunchResult {
    data object Success : PlayerLaunchResult()
    data object NotInstalled : PlayerLaunchResult()
    data class Failed(val message: String) : PlayerLaunchResult()
}

/** @deprecated Use [PlayerLaunchResult] */
typealias VlcLaunchResult = PlayerLaunchResult

class MediaPlayerLauncher(private val context: Context) {
    /**
     * Only returns packages that are actually installed on this device and can handle
     * a local video VIEW intent. Does not invent a catalog of known players.
     */
    fun listInstalledPlayers(): List<InstalledVideoPlayer> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()

        // Discover apps that claim to play video files (actually present on device).
        for (probe in videoProbeIntents()) {
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (isRealInstalledPlayer(pm, pkg, info)) {
                    packages += pkg
                }
            }
        }

        // Always consider VLC if it is genuinely installed (default player).
        if (isPackageInstalled(VLC_PACKAGE) && canHandleVideo(pm, VLC_PACKAGE)) {
            packages += VLC_PACKAGE
        }

        return packages.mapNotNull { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            }.getOrNull()?.ifBlank { null } ?: return@mapNotNull null
            InstalledVideoPlayer(packageName = pkg, label = label)
        }.sortedWith(
            compareByDescending<InstalledVideoPlayer> { it.isVlc }
                .thenBy { it.label.lowercase() }
        )
    }

    fun isInstalled(packageName: String = VLC_PACKAGE): Boolean = isPackageInstalled(packageName)

    fun play(
        playbackUri: Uri,
        relativePath: String,
        title: String,
        playerPackage: String = VLC_PACKAGE,
        startPositionMs: Long? = null,
        onLaunched: (() -> Unit)? = null
    ): PlayerLaunchResult {
        val pkg = playerPackage.ifBlank { VLC_PACKAGE }
        if (!isPackageInstalled(pkg)) return PlayerLaunchResult.NotInstalled

        val mime = mimeFor(relativePath, playbackUri)
        val resumeMs = startPositionMs?.takeIf { it > 5_000L }

        return try {
            context.startActivity(buildIntent(playbackUri, mime, pkg, title, resumeMs))
            onLaunched?.invoke()
            PlayerLaunchResult.Success
        } catch (_: ActivityNotFoundException) {
            PlayerLaunchResult.NotInstalled
        } catch (error: Exception) {
            PlayerLaunchResult.Failed(error.message ?: "Unable to open media in player")
        }
    }

    fun playStream(
        streamUrl: String,
        title: String,
        playerPackage: String = VLC_PACKAGE,
        onLaunched: (() -> Unit)? = null
    ): PlayerLaunchResult {
        val uri = Uri.parse(streamUrl)
        return play(
            playbackUri = uri,
            relativePath = streamUrl.substringAfterLast('/').ifBlank { "stream.ts" },
            title = title,
            playerPackage = playerPackage,
            startPositionMs = null,
            onLaunched = onLaunched
        )
    }

    private fun videoProbeIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("file://localhost/sample.mp4"), "video/mp4")
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("file://localhost/sample.mkv"), "video/x-matroska")
        },
        Intent(Intent.ACTION_VIEW).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    )

    private fun isRealInstalledPlayer(
        pm: PackageManager,
        packageName: String,
        resolveInfo: ResolveInfo
    ): Boolean {
        if (packageName.equals(context.packageName, true)) return false
        if (packageName in EXCLUDED_PACKAGES) return false
        if (EXCLUDED_PREFIXES.any { packageName.startsWith(it, ignoreCase = true) }) return false
        if (!isPackageInstalled(packageName)) return false
        val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return false
        if ((appInfo.flags and ApplicationInfo.FLAG_INSTALLED) == 0 &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        ) {
            // Older flag semantics; still require enabled
        }
        if (!appInfo.enabled) return false
        if (resolveInfo.activityInfo?.exported == false) return false
        return canHandleVideo(pm, packageName)
    }

    private fun canHandleVideo(pm: PackageManager, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("file://localhost/sample.mp4"), "video/mp4")
            setPackage(packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        @Suppress("DEPRECATION")
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    private fun buildIntent(
        playbackUri: Uri,
        mime: String,
        packageName: String,
        title: String,
        resumeMs: Long?
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(playbackUri, mime)
        setPackage(packageName)
        putExtra("title", title)
        putExtra("itemTitle", title)
        // VLC extras used by some builds
        putExtra("from_start", resumeMs == null)
        if (resumeMs != null) {
            putExtra("position", resumeMs)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info.applicationInfo?.enabled == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun mimeFor(path: String, uri: Uri = Uri.EMPTY): String {
        val lowerPath = path.lowercase()
        val lowerUri = uri.toString().lowercase()
        return when {
            lowerPath.endsWith(".m3u8") || lowerUri.contains(".m3u8") -> "application/x-mpegURL"
            lowerPath.endsWith(".mpd") || lowerUri.contains(".mpd") -> "application/dash+xml"
            else -> when (path.substringAfterLast('.', "").lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "wmv" -> "video/x-ms-wmv"
                "webm" -> "video/webm"
                "ts", "m2ts" -> "video/mp2t"
                else -> "video/*"
            }
        }
    }

    companion object {
        const val VLC_PACKAGE = "org.videolan.vlc"

        /** Streaming / browser / system apps that are not local file players. */
        private val EXCLUDED_PACKAGES = setOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.videos",
            "com.android.chrome",
            "com.chrome.beta",
            "com.android.browser",
            "com.android.tv.settings",
            "com.google.android.apps.photos",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.vending",
            "com.nvidia.tegrazone3"
        )

        private val EXCLUDED_PREFIXES = listOf(
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller"
        )
    }
}

/** Back-compat name used by existing call sites. */
typealias VlcLauncher = MediaPlayerLauncher
