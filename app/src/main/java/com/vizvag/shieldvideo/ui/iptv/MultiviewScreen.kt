package com.vizvag.shieldvideo.ui.iptv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vizvag.shieldvideo.R
import com.vizvag.shieldvideo.data.iptv.IptvChannel
import com.vizvag.shieldvideo.data.iptv.IptvFavoritesStore
import com.vizvag.shieldvideo.data.iptv.IptvRepository
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.ui.components.IconActionButton
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome
import com.vizvag.shieldvideo.ui.theme.TextMuted

@Composable
fun MultiviewScreen(
    settingsRepository: SettingsRepository,
    iptvRepository: IptvRepository,
    favorites: IptvFavoritesStore,
    onBack: () -> Unit
) {
    val catalog by iptvRepository.catalog.collectAsState()
    val settings = remember { settingsRepository.load() }
    val favIds = remember { favorites.getFavorites(settings.activeIptvPlaylistId) }
    val pickList = remember(catalog.channels, favIds) {
        val favs = catalog.channels.filter { it.id in favIds }
        if (favs.isNotEmpty()) favs else catalog.channels.take(40)
    }
    val slots = remember { mutableStateListOf<IptvChannel?>().apply { repeat(4) { add(null) } } }
    var assigningSlot by remember { mutableStateOf<Int?>(null) }
    var layout by remember { mutableStateOf(2) } // 1, 2, or 4 panes

    BackHandler {
        if (assigningSlot != null) assigningSlot = null else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconActionButton(selected = false, onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Multiview", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Tap a pane to assign a channel · $layout panes", color = TextMuted, fontSize = 13.sp)
            }
            listOf(1, 2, 4).forEach { n ->
                Text(
                    text = "${n}x",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (layout == n) LocalScreenChrome.current.accent.copy(alpha = 0.35f) else CardSurface)
                        .clickable { layout = n }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (assigningSlot != null) {
            ChannelPicker(
                channels = pickList,
                emptyHint = if (favIds.isEmpty()) "Showing first channels — favorite some for quicker picks" else "Favorites",
                onPick = { ch ->
                    slots[assigningSlot!!] = ch
                    assigningSlot = null
                },
                onCancel = { assigningSlot = null }
            )
        } else {
            MultiviewGrid(
                layout = layout,
                slots = slots,
                onAssign = { assigningSlot = it }
            )
        }
    }
}

@Composable
private fun MultiviewGrid(
    layout: Int,
    slots: List<IptvChannel?>,
    onAssign: (Int) -> Unit
) {
    when (layout) {
        1 -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                StreamPane(channel = slots.getOrNull(0), onClick = { onAssign(0) })
            }
        }
        2 -> {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    StreamPane(channel = slots.getOrNull(0), onClick = { onAssign(0) })
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    StreamPane(channel = slots.getOrNull(1), onClick = { onAssign(1) })
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StreamPane(channel = slots.getOrNull(0), onClick = { onAssign(0) })
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StreamPane(channel = slots.getOrNull(1), onClick = { onAssign(1) })
                    }
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StreamPane(channel = slots.getOrNull(2), onClick = { onAssign(2) })
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StreamPane(channel = slots.getOrNull(3), onClick = { onAssign(3) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamPane(channel: IptvChannel?, onClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(channel?.streamUrl) {
        if (channel == null) null
        else buildIptvExoPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            prepare()
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val p = player
        if (p != null) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        p.playWhenReady = false
                        p.pause()
                        p.stop()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (p.mediaItemCount > 0) {
                            p.prepare()
                            p.playWhenReady = true
                            p.play()
                        }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                p.playWhenReady = false
                p.stop()
                p.release()
            }
        } else {
            onDispose { }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .focusable()
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.iptv_player_view, null) as PlayerView).apply {
                        useController = false
                        this.player = player
                        // Keep every Android TV target out of ambient/screensaver mode
                        // while any multiview pane is playing.
                        keepScreenOn = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { it.player = player },
                onRelease = {
                    it.keepScreenOn = false
                    it.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = channel?.name.orEmpty(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select channel", color = TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChannelPicker(
    channels: List<IptvChannel>,
    emptyHint: String,
    onPick: (IptvChannel) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pick channel", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "Cancel",
                color = LocalScreenChrome.current.accent,
                modifier = Modifier.clickable(onClick = onCancel).padding(8.dp)
            )
        }
        Text(emptyHint, color = TextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        if (channels.isEmpty()) {
            Text("Load Live TV first so the playlist is cached.", color = TextMuted)
        } else {
            LazyColumn {
                items(channels, key = { it.id }) { ch ->
                    Text(
                        text = "${ch.name} · ${ch.group}",
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ch) }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}
