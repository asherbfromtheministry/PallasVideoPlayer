package com.vizvag.shieldvideo.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.IptvRecordingStorage
import com.vizvag.shieldvideo.ui.components.SleepTimerCustomDialog
import com.vizvag.shieldvideo.ui.components.chromeFor
import com.vizvag.shieldvideo.ui.theme.ScreenTheme

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
    showRail: Boolean = true,
    /** When false, rail stays visible but is not in the D-pad focus tree. */
    railFocusEnabled: Boolean = true,
    players: RailPlayerVisibility = RailPlayerVisibility(),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    var showCustomSleep by remember { mutableStateOf(false) }

    ScreenTheme(chromeFor(destination)) {
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
                    onCustomSleepTimer = { showCustomSleep = true },
                    onSettings = onSettings,
                    destination = destination,
                    focusEnabled = railFocusEnabled && !showCustomSleep,
                    players = players,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().fillMaxSize()) {
                content()
            }
        }
        if (showCustomSleep) {
            SleepTimerCustomDialog(
                onConfirmMinutes = { mins ->
                    app.settingsRepository.saveSleepTimerLastCustomMinutes(mins)
                    app.sleepTimer.setMinutes(mins)
                },
                onClear = { app.sleepTimer.clear() },
                onDismiss = { showCustomSleep = false },
                initialMinutes = app.settingsRepository.loadSleepTimerLastCustomMinutes(),
            )
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
