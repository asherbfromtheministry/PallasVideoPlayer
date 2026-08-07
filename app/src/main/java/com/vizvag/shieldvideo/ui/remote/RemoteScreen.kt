package com.vizvag.shieldvideo.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.ShieldVideoApp
import com.vizvag.shieldvideo.playback.remote.RemoteDevice
import com.vizvag.shieldvideo.playback.remote.RemoteLanProbe
import com.vizvag.shieldvideo.playback.remote.RemotePlaybackMode
import com.vizvag.shieldvideo.playback.remote.RemoteStatus
import com.vizvag.shieldvideo.playback.remote.RemoteStatusPoller
import com.vizvag.shieldvideo.playback.remote.RemoteTargetStore
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import kotlinx.coroutines.delay

private fun deviceKey(d: RemoteDevice) = "${d.host}:${d.port}"

/**
 * Room picker only — tap a TV to open the **same screen** that TV is showing
 * (landing, Music, Live TV, …). No separate remote transport UI.
 */
@Composable
fun RemoteScreen(
    onBack: () -> Unit,
    onOpenRoom: (RemoteStatus?) -> Unit,
) {
    val app = ShieldVideoApp.instance
    val discovery = app.remoteDiscovery
    val client = app.remoteClient
    val nsdDevices by discovery.devices.collectAsState()
    val target by RemoteTargetStore.target.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var probed by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var statusByKey by remember { mutableStateOf<Map<String, RemoteStatus>>(emptyMap()) }
    var scanTick by remember { mutableStateOf(0) }

    val devices = remember(nsdDevices, probed, statusByKey) {
        (nsdDevices + probed)
            .distinctBy { deviceKey(it) }
            .sortedWith(
                compareByDescending<RemoteDevice> {
                    val s = statusByKey[deviceKey(it)]
                    s != null && s.mode != RemotePlaybackMode.Idle
                }.thenBy { it.deviceId },
            )
    }

    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    LaunchedEffect(scanTick) {
        // First open: NSD only. Full /24 LAN probe is expensive — only on Refresh.
        if (scanTick == 0) {
            scanning = false
            return@LaunchedEffect
        }
        scanning = true
        probed = RemoteLanProbe.scan(context.applicationContext)
        scanning = false
    }

    LaunchedEffect(devices.map { deviceKey(it) }.joinToString()) {
        while (true) {
            val next = statusByKey.toMutableMap()
            for (device in devices) {
                client.status(device)
                    .onSuccess { next[deviceKey(device)] = it }
                    .onFailure { /* keep previous */ }
            }
            statusByKey = next
            delay(4_000)
        }
    }

    fun openRoom(device: RemoteDevice) {
        RemoteTargetStore.setTarget(device)
        RemoteStatusPoller.kick()
        onOpenRoom(statusByKey[deviceKey(device)])
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0D))
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Control a room", color = TextCream, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconActionButton(selected = false, onClick = { scanTick++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Scan", tint = TextCream)
                }
                IconActionButton(selected = false, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextCream)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (scanning) {
                "Scanning Wi‑Fi for Pallas TVs…"
            } else {
                "Tap a room to control that TV from the home screen"
            },
            color = TextMuted,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rooms on Wi‑Fi", color = TextMuted, fontWeight = FontWeight.Bold)
            if (scanning) {
                Spacer(modifier = Modifier.size(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Accent,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (devices.isEmpty() && !scanning) {
                item {
                    Text(
                        text = "Nothing found. Open Pallas on the TV, same Wi‑Fi, then refresh.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
            itemsIndexed(devices, key = { _, d -> deviceKey(d) }) { _, device ->
                val status = statusByKey[deviceKey(device)]
                val playing = status != null && status.mode != RemotePlaybackMode.Idle
                val selected = target != null && deviceKey(target!!) == deviceKey(device)
                DeviceRow(
                    label = device.deviceId.ifBlank { device.host },
                    subtitle = when {
                        status == null -> device.host
                        playing -> {
                            val title = status.title.ifBlank { "Playing" }
                            "${status.mode.name} · $title"
                        }
                        else -> {
                            val screen = when (status.uiRoute) {
                                "home" -> "Home"
                                "music" -> "Music"
                                "radio" -> "Radio"
                                "iptv" -> "Live TV"
                                "youtube" -> "YouTube"
                                "podcasts" -> "Podcasts"
                                "browser" -> "Browse"
                                "settings" -> "Settings"
                                else -> status.uiRoute.replaceFirstChar { it.uppercase() }
                            }
                            "$screen · ${device.host}"
                        }
                    },
                    playing = playing,
                    selected = selected,
                    onClick = { openRoom(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    label: String,
    subtitle: String,
    playing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(focused = focused, selected = selected || playing)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextCream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = when {
                selected -> "Open"
                playing -> "Playing"
                else -> "Tap"
            },
            color = if (selected || playing) Accent else TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
