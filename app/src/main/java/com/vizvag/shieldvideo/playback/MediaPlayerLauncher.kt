package com.vizvag.shieldvideo.playback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

data class InstalledVideoPlayer(
    val packageName: String,
    val label: String,
    val isVlc: Boolean = packageName == MediaPlayerLauncher.VLC_PACKAGE,
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
        // X Player is picky: generic video/* resolves to PlayerActivity filters more reliably
        // than video/x-matroska for network handoffs.
        val launchMime =
            if (!pkg.equals(VLC_PACKAGE, true) && mime.startsWith("video/")) "video/*" else mime
        val resumeMs = startPositionMs?.takeIf { it > 5_000L }
        val uriForPlayer = resolveHandoffUri(playbackUri, pkg)

        return try {
            context.startActivity(buildIntent(uriForPlayer, launchMime, pkg, title, resumeMs))
            afterPlayerLaunched(pkg)
            onLaunched?.invoke()
            PlayerLaunchResult.Success
        } catch (first: ActivityNotFoundException) {
            // Concrete component may be missing on some builds — retry package-only.
            return try {
                context.startActivity(buildPackageIntent(uriForPlayer, launchMime, pkg, title, resumeMs))
                afterPlayerLaunched(pkg)
                onLaunched?.invoke()
                PlayerLaunchResult.Success
            } catch (_: ActivityNotFoundException) {
                PlayerLaunchResult.NotInstalled
            } catch (error: Exception) {
                PlayerLaunchResult.Failed(error.message ?: "Unable to open media in player")
            }
        } catch (error: Exception) {
            PlayerLaunchResult.Failed(error.message ?: "Unable to open media in player")
        }
    }

    /**
     * Clear a sticky media-button receiver left by another player (e.g. X Player)
     * so Shield remote play/pause reaches the active session.
     *
     * Do **not** [Activity.moveTaskToBack] here: that buries Pallas under the TV
     * launcher, so leaving VLC returns to Shield home instead of the DVR/browser.
     */
    private fun afterPlayerLaunched(playerPackage: String) {
        Log.i(TAG, "Launched external player pkg=$playerPackage")
        clearStickyMediaButtonReceiver()
    }

    private fun clearStickyMediaButtonReceiver() {
        try {
            Settings.Secure.putString(context.contentResolver, "media_button_receiver", null)
        } catch (_: SecurityException) {
            // Optional: adb pm grant … WRITE_SECURE_SETTINGS
        } catch (_: Exception) {
        }
    }

    /**
     * VLC: Range HTTP on the LAN IP (TV VLC expands foreign content:// to null).
     * MX / X Player: loopback HTTP + concrete PlayerActivity (http WebDelegate disables TV
     * controls; ProxyFileDescriptor content:// is opened but never read by X Player).
     */
    private fun resolveHandoffUri(playbackUri: Uri, packageName: String): Uri {
        val scheme = playbackUri.scheme?.lowercase().orEmpty()
        val proxy = (context.applicationContext as? com.vizvag.shieldvideo.ShieldVideoApp)
            ?.localMediaProxy

        if (packageName.equals(VLC_PACKAGE, true)) {
            if (scheme == "content") {
                return proxy?.activeHttpUri() ?: playbackUri
            }
            return playbackUri
        }

        // Prefer live proxy HTTP (loopback) over content:// for third-party players.
        val http = proxy?.activeHttpUri()
        if (http != null) {
            return uriForPlayer(http, packageName)
        }
        if (scheme == "content" || scheme == "file") return playbackUri
        return uriForPlayer(playbackUri, packageName)
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

    /**
     * Prefer content:// handoffs as-is (local-style controls). For legacy http handoffs,
     * VLC keeps the LAN host; MX / XPlayer rewrite to loopback and unwrap WebDelegate.
     */
    private fun uriForPlayer(playbackUri: Uri, packageName: String): Uri {
        val scheme = playbackUri.scheme?.lowercase() ?: return playbackUri
        if (scheme == "content" || scheme == "file") return playbackUri
        if (packageName.equals(VLC_PACKAGE, true)) return playbackUri
        if (scheme != "http" && scheme != "https") return playbackUri
        val host = playbackUri.host ?: return playbackUri
        if (host == "127.0.0.1" || host.equals("localhost", true)) return playbackUri
        val port = playbackUri.port
        val authority = if (port > 0) "127.0.0.1:$port" else "127.0.0.1"
        return playbackUri.buildUpon().encodedAuthority(authority).build()
    }

    private fun buildIntent(
        playbackUri: Uri,
        mime: String,
        packageName: String,
        title: String,
        resumeMs: Long?
    ): Intent {
        val scheme = playbackUri.scheme?.lowercase().orEmpty()
        // content/file resolve to the real player activity — package targeting is enough.
        if (scheme == "content" || scheme == "file" || packageName.equals(VLC_PACKAGE, true)) {
            return buildPackageIntent(playbackUri, mime, packageName, title, resumeMs)
        }

        val intent = buildPackageIntent(playbackUri, mime, packageName, title, resumeMs)
        val concrete = resolveConcretePlayerComponent(packageName, playbackUri, mime)
            ?: return intent
        intent.component = concrete
        intent.setPackage(null)
        return intent
    }

    private fun buildPackageIntent(
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
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra("from_start", resumeMs == null)
        if (resumeMs != null) {
            putExtra("position", resumeMs)
            if (packageName.startsWith("com.mxtech.videoplayer", ignoreCase = true)) {
                putExtra("position", resumeMs.toInt().coerceAtLeast(0))
            }
        }
        // NEW_TASK only when started from a non-Activity context (e.g. remote FGS).
        // From MainActivity, keep VLC in our task so Back returns to Pallas.
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playbackUri.scheme.equals("content", ignoreCase = true)) {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // ClipData is required on newer Android so the grant sticks across the handoff.
            clipData = ClipData.newUri(context.contentResolver, title, playbackUri)
            try {
                context.grantUriPermission(
                    packageName,
                    playbackUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * HTTP/HTTPS VIEW intents resolve to *WebDelegate activity-aliases. Those aliases put
     * MX / XPlayer into network mode without seek/pause. Open the alias target activity instead.
     */
    private fun resolveConcretePlayerComponent(
        packageName: String,
        playbackUri: Uri,
        mime: String
    ): ComponentName? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(playbackUri, mime)
            setPackage(packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        @Suppress("DEPRECATION")
        val resolved = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val info = resolved.activityInfo ?: return null
        val className = when {
            !info.targetActivity.isNullOrBlank() -> info.targetActivity
            info.name.contains("\$WebDelegate", ignoreCase = true) ->
                info.name.substringBefore("\$WebDelegate")
            else -> return null // already a concrete activity — package targeting is fine
        }
        return try {
            val component = ComponentName(info.packageName, className)
            if (pm.getActivityInfo(component, 0).exported) component else null
        } catch (_: PackageManager.NameNotFoundException) {
            KNOWN_PLAYER_ACTIVITIES[packageName]?.let { ComponentName(packageName, it) }
        }
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
        private const val TAG = "MediaPlayerLauncher"
        const val VLC_PACKAGE = "org.videolan.vlc"
        const val XPLAYER_PACKAGE = "video.player.videoplayer"

        private val KNOWN_PLAYER_ACTIVITIES = mapOf(
            XPLAYER_PACKAGE to "com.inshot.xplayer.activities.PlayerActivity",
            "com.mxtech.videoplayer.ad" to "com.mxtech.videoplayer.ad.ActivityScreen",
            "com.mxtech.videoplayer.pro" to "com.mxtech.videoplayer.ad.ActivityScreen",
        )

        /** Streaming / browser / system / non-file players. */
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
            "com.nvidia.tegrazone3",
            "org.smarttube.stable",
            "org.schabi.newpipe",
            "ar.tvplayer.tv",
            "com.nvidia.bbciplayer",
            "com.nvidia.bbciplayer.launchsounds",
            "com.google.android.apps.nbu.smartconnect.tv",
        )

        private val EXCLUDED_PREFIXES = listOf(
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.nvidia.bbciplayer",
        )
    }
}

/** Back-compat name used by existing call sites. */
typealias VlcLauncher = MediaPlayerLauncher
