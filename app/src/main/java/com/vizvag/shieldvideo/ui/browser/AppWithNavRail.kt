package com.vizvag.shieldvideo.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage

@Composable
fun AppWithNavRail(
    destination: RailDestination,
    shares: List<String>,
    selectedShare: String,
    onSelectShare: (String) -> Unit,
    recordingFolder: String?,
    onLiveTv: () -> Unit,
    onYouTube: () -> Unit,
    onRadio: () -> Unit,
    onMusic: () -> Unit,
    onPodcasts: () -> Unit = {},
    sleepTimerActive: Boolean,
    sleepTimerLabel: String?,
    onCycleSleepTimer: () -> Unit,
    onSettings: () -> Unit,
    canGoUp: Boolean = false,
    onGoUp: () -> Unit = {},
    showRail: Boolean = true,
    /** When false, rail stays visible but is not in the D-pad focus tree. */
    railFocusEnabled: Boolean = true,
    players: RailPlayerVisibility = RailPlayerVisibility(),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        if (showRail) {
            BrowserNavRail(
                shares = shares,
                selectedShare = selectedShare,
                onSelectShare = onSelectShare,
                recordingFolder = recordingFolder,
                onLiveTv = onLiveTv,
                onYouTube = onYouTube,
                onRadio = onRadio,
                onMusic = onMusic,
                onPodcasts = onPodcasts,
                sleepTimerActive = sleepTimerActive,
                sleepTimerLabel = sleepTimerLabel,
                onCycleSleepTimer = onCycleSleepTimer,
                onSettings = onSettings,
                canGoUp = canGoUp,
                onGoUp = onGoUp,
                destination = destination,
                focusEnabled = railFocusEnabled,
                players = players,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight().fillMaxSize()) {
            content()
        }
    }
}

/** Ordered share list with default share first (matches BrowserScreen / home landing). */
fun orderedSharesForRail(settings: AppSettings): List<String> {
    val shares = settings.shares
    if (shares.isEmpty()) return shares
    val configured = settings.defaultShare.trim().trimEnd('/').lowercase()
    if (configured.isBlank()) return shares
    val default = shares.firstOrNull { share ->
        val n = share.trim().trimEnd('/').lowercase()
        n == configured || n.equals(settings.defaultShare, ignoreCase = true)
    } ?: return shares
    return listOf(default) + shares.filterNot { it.equals(default, ignoreCase = true) }
}

fun recordingFolderForRail(settings: AppSettings): String? =
    settings.iptvRecordingNasFolder
        .takeIf { settings.iptvRecordingStorage == IptvRecordingStorage.NAS }

@Composable
fun rememberOrderedShares(settings: AppSettings): List<String> =
    remember(settings.shares, settings.defaultShare) { orderedSharesForRail(settings) }
