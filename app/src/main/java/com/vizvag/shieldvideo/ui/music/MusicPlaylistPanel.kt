package com.vizvag.shieldvideo.ui.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.music.ui.AlbumArt
import com.vizvag.shieldvideo.ui.theme.AudioAccent
import com.vizvag.shieldvideo.ui.theme.AudioTextMuted
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Playlist reorder highlight — ice blue, never accent (accent = now playing). */
private val PlaylistMoveAccent = Color(0xFF5EC8FF)
private val PlaylistFocusRing = PlaylistMoveAccent

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueDrawer(
    queue: List<TrackEntity>,
    queueIndex: Int,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    /** Album art for the playing track — same source as the blurred player background. */
    nowPlayingAlbumArt: Any?,
    resolveCoverUrl: suspend (TrackEntity) -> Any?,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onShuffle: () -> Unit,
    onClear: () -> Unit,
    onBrowse: () -> Unit,
    onClose: () -> Unit,
    listFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var moving by remember { mutableIntStateOf(-1) }
    val movingFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coverCache = remember { mutableStateMapOf<String, Any?>() }

    val remainingMs = remember(queue, queueIndex, positionMs, durationMs) {
        playlistRemainingMs(queue, queueIndex, positionMs, durationMs)
    }
    val queueProgress = remember(queue, queueIndex, positionMs, durationMs) {
        playlistProgress(queue, queueIndex, positionMs, durationMs)
    }
    val rows = remember(queue, queueIndex) { buildPlaylistRows(queue, queueIndex) }

    LaunchedEffect(moving) {
        if (moving < 0) return@LaunchedEffect
        delay(24)
        runCatching { movingFocus.requestFocus() }
    }

    LaunchedEffect(queue) {
        val keys = queue.map(::playlistAlbumKey).distinct()
        for (key in keys) {
            // Absent vs null: never treat a failed probe as "done", and never
            // clobber art that now-playing (or a parallel resolve) already set.
            if (coverCache[key] != null) continue
            val sample = queue.firstOrNull { playlistAlbumKey(it) == key } ?: continue
            val resolved = runCatching { resolveCoverUrl(sample) }.getOrNull() ?: continue
            if (coverCache[key] == null) {
                coverCache[key] = resolved
            }
        }
    }

    LaunchedEffect(nowPlayingAlbumArt, queueIndex, queue) {
        val track = queue.getOrNull(queueIndex) ?: return@LaunchedEffect
        val art = nowPlayingAlbumArt ?: return@LaunchedEffect
        coverCache[playlistAlbumKey(track)] = art
    }

    LaunchedEffect(queueIndex, rows.size, moving) {
        if (moving >= 0 || queue.isEmpty()) return@LaunchedEffect
        val target = rows.indexOfFirst { it is PlaylistRow.Track && it.index == queueIndex }
            .takeIf { it >= 0 }
            ?: return@LaunchedEffect
        runCatching {
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF0161218), Color(0xF50A0A0E), Color(0xF808080C)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.04f),
                    ),
                ),
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        PlaylistHeader(
            queueSize = queue.size,
            remainingMs = remainingMs,
            progress = queueProgress,
            moving = moving >= 0,
            onClose = onClose,
            actionsFocusable = moving < 0,
        )

        if (queue.isNotEmpty()) {
            PlaylistActions(
                queueSize = queue.size,
                focusRequester = listFocusRequester,
                enabled = moving < 0,
                onShuffle = onShuffle,
                onClear = onClear,
                onBrowse = onBrowse,
            )
        }

        when {
            queue.isEmpty() -> PlaylistEmptyState(
                onBrowse = onBrowse,
                focusRequester = listFocusRequester,
                modifier = Modifier.weight(1f),
            )
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(
                        count = rows.size,
                        key = { idx ->
                            when (val row = rows[idx]) {
                                is PlaylistRow.AlbumHeader ->
                                    "album|${row.albumKey}|$idx"
                                is PlaylistRow.DiscHeader ->
                                    "disc|${row.albumKey}|${row.disc}|$idx"
                                is PlaylistRow.SectionLabel ->
                                    "section|${row.label}|$idx"
                                is PlaylistRow.Track ->
                                    "${row.track.id}|${row.track.nasPath}|${row.index}"
                            }
                        },
                    ) { idx ->
                        when (val row = rows[idx]) {
                            is PlaylistRow.AlbumHeader -> {
                                PlaylistAlbumHeader(
                                    title = row.albumTitle,
                                    artist = row.artist,
                                    year = row.year,
                                    trackCount = row.trackCount,
                                    coverUrl = coverCache[row.albumKey],
                                )
                            }
                            is PlaylistRow.DiscHeader -> {
                                PlaylistDiscHeader(
                                    disc = row.disc,
                                    trackCount = row.trackCount,
                                )
                            }
                            is PlaylistRow.SectionLabel -> {
                                PlaylistSectionLabel(label = row.label)
                            }
                            is PlaylistRow.Track -> {
                                val index = row.index
                                val track = row.track
                                val isMoving = moving == index
                                val current = index == queueIndex
                                PlaylistTrackRow(
                                    index = index,
                                    title = playlistTrackTitle(track),
                                    artist = playlistTrackArtist(track),
                                    durationMs = track.durationMs,
                                    coverUrl = coverCache[playlistAlbumKey(track)],
                                    current = current,
                                    playing = current && isPlaying,
                                    moving = isMoving,
                                    focusRequester = if (isMoving) movingFocus else null,
                                    canFocus = moving < 0 || isMoving,
                                    onClick = {
                                        if (moving >= 0) {
                                            moving = -1
                                        } else {
                                            onPlayIndex(index)
                                        }
                                    },
                                    onLongClick = {
                                        if (moving == index) {
                                            moving = -1
                                            onRemove(index)
                                        } else {
                                            moving = index
                                        }
                                    },
                                    onMoveUp = {
                                        if (moving != index) return@PlaylistTrackRow
                                        val to = (index - 1).coerceAtLeast(0)
                                        if (to != index) {
                                            onMove(index, to)
                                            moving = to
                                        }
                                    },
                                    onMoveDown = {
                                        if (moving != index) return@PlaylistTrackRow
                                        val to = (index + 1).coerceAtMost(queue.lastIndex)
                                        if (to != index) {
                                            onMove(index, to)
                                            moving = to
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (moving in queue.indices) {
                    PlaylistMoveBanner()
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    queueSize: Int,
    remainingMs: Long,
    progress: Float,
    moving: Boolean,
    onClose: () -> Unit,
    actionsFocusable: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)
            .focusProperties { canFocus = actionsFocusable },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "QUEUE",
                    color = AudioAccent.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = PallasFontFamily,
                )
                Text(
                    when {
                        queueSize == 0 -> "Nothing queued"
                        moving -> "Up / Down moves · OK places"
                        else -> {
                            val left = formatDurationLong(remainingMs)
                            if (left.isNotBlank()) {
                                "$queueSize tracks · $left left"
                            } else {
                                "$queueSize tracks"
                            }
                        }
                    },
                    color = if (moving) PlaylistMoveAccent else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = PallasFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlaylistIconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (queueSize > 0) {
            Spacer(Modifier.height(10.dp))
            PlaylistProgressRail(progress = progress, moving = moving)
        }
    }
}

@Composable
private fun PlaylistProgressRail(progress: Float, moving: Boolean) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "queueProgress",
    )
    val accent = if (moving) PlaylistMoveAccent else AudioAccent
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val trackH = size.height
        val radius = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = radius,
        )
        val fillW = size.width * animated
        if (fillW > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.55f), accent),
                ),
                size = Size(fillW, trackH),
                cornerRadius = radius,
            )
            drawCircle(
                color = accent,
                radius = trackH * 1.35f,
                center = Offset(fillW.coerceAtMost(size.width), trackH / 2f),
            )
        }
    }
}

@Composable
private fun PlaylistActions(
    queueSize: Int,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onShuffle: () -> Unit,
    onClear: () -> Unit,
    onBrowse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .focusProperties { canFocus = enabled },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            queueSize > 1 -> {
                PlaylistActionChip(
                    label = "Shuffle",
                    icon = Icons.Filled.Shuffle,
                    focusRequester = focusRequester,
                    emphasized = true,
                    onClick = onShuffle,
                )
                PlaylistActionChip(
                    label = "Clear",
                    icon = Icons.Filled.DeleteSweep,
                    onClick = onClear,
                )
                PlaylistActionChip(
                    label = "Browse",
                    icon = Icons.Filled.LibraryMusic,
                    onClick = onBrowse,
                )
            }
            else -> {
                PlaylistActionChip(
                    label = "Clear",
                    icon = Icons.Filled.DeleteSweep,
                    focusRequester = focusRequester,
                    onClick = onClear,
                )
                PlaylistActionChip(
                    label = "Browse",
                    icon = Icons.Filled.LibraryMusic,
                    onClick = onBrowse,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PlaylistActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    emphasized: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    emphasized -> AudioAccent.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> PlaylistFocusRing
                    emphasized -> AudioAccent.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                shape = RoundedCornerShape(20.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                focused -> Color(0xFF1A1206)
                emphasized -> AudioAccent
                else -> Color.White.copy(alpha = 0.85f)
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = when {
                focused -> Color(0xFF1A1206)
                emphasized -> AudioAccent
                else -> Color.White.copy(alpha = 0.88f)
            },
            fontSize = 12.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            fontFamily = PallasFontFamily,
        )
    }
}

@Composable
private fun PlaylistEmptyState(
    onBrowse: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(AudioAccent.copy(alpha = 0.22f), Color.Transparent),
                    ),
                )
                .border(1.dp, AudioAccent.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = AudioAccent,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Queue is empty",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = PallasFontFamily,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Hold OK on a track, album, or folder\nto build your setlist",
            color = AudioTextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            fontFamily = PallasFontFamily,
        )
        Spacer(Modifier.height(18.dp))
        PlaylistActionChip(
            label = "Browse library",
            icon = Icons.Filled.LibraryMusic,
            focusRequester = focusRequester,
            emphasized = true,
            onClick = onBrowse,
        )
    }
}

@Composable
private fun PlaylistAlbumHeader(
    title: String,
    artist: String,
    year: Int?,
    trackCount: Int,
    coverUrl: Any?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AlbumArt(
            imageUrl = coverUrl,
            title = title,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Unknown album" },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append(artist.ifBlank { "Unknown artist" })
                if (year != null && year > 0) append(" · $year")
                append(" · $trackCount track${if (trackCount == 1) "" else "s"}")
            }
            Text(
                meta,
                color = AudioTextMuted,
                fontSize = 11.sp,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistDiscHeader(disc: Int, trackCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "DISC $disc",
            color = AudioAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            fontFamily = PallasFontFamily,
        )
        Spacer(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .weight(1f)
                .height(1.dp)
                .background(AudioAccent.copy(alpha = 0.28f)),
        )
        Text(
            "$trackCount",
            color = AudioTextMuted.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontFamily = PallasFontFamily,
        )
    }
}

@Composable
private fun PlaylistSectionLabel(label: String) {
    Text(
        label,
        color = AudioAccent.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
        fontFamily = PallasFontFamily,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaylistMoveBanner() {
    Text(
        "D-pad Up/Down · OK to place · Hold OK removes",
        color = PlaylistMoveAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = PallasFontFamily,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        PlaylistMoveAccent.copy(alpha = 0.08f),
                        PlaylistMoveAccent.copy(alpha = 0.18f),
                        PlaylistMoveAccent.copy(alpha = 0.08f),
                    ),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(
    index: Int,
    title: String,
    artist: String,
    durationMs: Long,
    coverUrl: Any?,
    current: Boolean,
    playing: Boolean,
    moving: Boolean,
    focusRequester: FocusRequester? = null,
    canFocus: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = when {
            moving -> 1.02f
            focused -> 1.015f
            else -> 1f
        },
        animationSpec = tween(140),
        label = "rowScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    moving -> PlaylistMoveAccent.copy(alpha = 0.18f)
                    current && focused -> AudioAccent.copy(alpha = 0.18f)
                    current -> AudioAccent.copy(alpha = 0.10f)
                    focused -> Color.White.copy(alpha = 0.09f)
                    else -> Color.Transparent
                },
            )
            .then(
                if (focused || moving) {
                    Modifier.border(
                        width = 2.dp,
                        color = if (moving) PlaylistMoveAccent else PlaylistFocusRing,
                        shape = RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (current && !moving) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = AudioAccent.copy(alpha = 0.85f),
                            topLeft = Offset(0f, size.height * 0.18f),
                            size = Size(3.dp.toPx(), size.height * 0.64f),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    moving && event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }
                    moving && event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    moving && event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
                        true
                    }
                    event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        onLongClick()
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            longPressHandled = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressHandled = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressHandled) {
                            longPressHandled = false
                            onLongClick()
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .focusable(canFocus, interaction)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AlbumArt(
                imageUrl = coverUrl,
                title = title,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .border(
                        width = 1.dp,
                        color = when {
                            moving -> PlaylistMoveAccent.copy(alpha = 0.7f)
                            current -> AudioAccent.copy(alpha = 0.55f)
                            else -> Color.White.copy(alpha = 0.10f)
                        },
                        shape = RoundedCornerShape(5.dp),
                    ),
            )
            if (playing) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MiniEqualizer(active = true, color = AudioAccent)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = when {
                    moving -> Color.White
                    current -> Color.White
                    else -> Color.White.copy(alpha = 0.94f)
                },
                fontSize = 13.sp,
                fontWeight = if (current || moving || focused) FontWeight.Bold else FontWeight.Medium,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist,
                color = when {
                    moving -> PlaylistMoveAccent.copy(alpha = 0.95f)
                    current -> AudioAccent.copy(alpha = 0.85f)
                    else -> AudioTextMuted
                },
                fontSize = 11.sp,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            moving -> Text(
                "MOVE",
                color = PlaylistMoveAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = PallasFontFamily,
            )
            else -> Text(
                if (durationMs > 0) formatTrackTime(durationMs) else "%02d".format(index + 1),
                color = when {
                    current -> AudioAccent.copy(alpha = 0.9f)
                    focused -> Color.White.copy(alpha = 0.75f)
                    else -> AudioTextMuted.copy(alpha = 0.7f)
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = PallasFontFamily,
            )
        }
    }
}

@Composable
private fun MiniEqualizer(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "miniEq")
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { i ->
            val h by infinite.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(260 + i * 95, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 70),
                ),
                label = "eq$i",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((14 * if (active) h else 0.32f).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun PlaylistIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) PlaylistFocusRing else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        content()
    }
}

private sealed class PlaylistRow {
    data class AlbumHeader(
        val albumKey: String,
        val albumTitle: String,
        val artist: String,
        val year: Int?,
        val trackCount: Int,
    ) : PlaylistRow()

    data class DiscHeader(
        val disc: Int,
        val trackCount: Int,
        val albumKey: String,
    ) : PlaylistRow()

    data class SectionLabel(val label: String) : PlaylistRow()

    data class Track(
        val index: Int,
        val track: TrackEntity,
    ) : PlaylistRow()
}

private fun playlistAlbumKey(track: TrackEntity): String {
    val title = track.albumTitle.trim().lowercase()
    if (title.isNotBlank()) return "t:$title"
    val folder = track.nasPath.replace('\\', '/')
        .substringBeforeLast('/')
        .trimEnd('/')
        .lowercase()
    return if (folder.isNotBlank()) "f:$folder" else "id:${track.albumId}"
}

/**
 * Album headers on every album change; disc headers for multi-disc sets;
 * UP NEXT label after the currently playing track.
 */
private fun buildPlaylistRows(queue: List<TrackEntity>, queueIndex: Int): List<PlaylistRow> {
    if (queue.isEmpty()) return emptyList()
    val multiDiscKeys = queue
        .groupBy(::playlistAlbumKey)
        .filterValues { tracks -> tracks.any { (it.discNumber ?: 1) > 1 } }
        .keys

    val out = ArrayList<PlaylistRow>(queue.size + 16)
    var i = 0
    var lastKey: String? = null
    while (i < queue.size) {
        val t = queue[i]
        val key = playlistAlbumKey(t)

        if (key != lastKey) {
            var albumEnd = i + 1
            while (albumEnd < queue.size && playlistAlbumKey(queue[albumEnd]) == key) {
                albumEnd++
            }
            out += PlaylistRow.AlbumHeader(
                albumKey = key,
                albumTitle = t.albumTitle.trim().ifBlank { "Unknown album" },
                artist = albumHeaderArtist(queue.subList(i, albumEnd)),
                year = queue.subList(i, albumEnd).mapNotNull { it.year }.maxOrNull(),
                trackCount = albumEnd - i,
            )
            lastKey = key

            if (key in multiDiscKeys) {
                var j = i
                while (j < albumEnd) {
                    val disc = queue[j].discNumber ?: 1
                    var discEnd = j + 1
                    while (
                        discEnd < albumEnd &&
                        (queue[discEnd].discNumber ?: 1) == disc
                    ) {
                        discEnd++
                    }
                    out += PlaylistRow.DiscHeader(
                        disc = disc,
                        trackCount = discEnd - j,
                        albumKey = key,
                    )
                    for (k in j until discEnd) {
                        out += PlaylistRow.Track(k, queue[k])
                    }
                    j = discEnd
                }
                i = albumEnd
                continue
            }
        }

        out += PlaylistRow.Track(i, t)
        i++
    }

    if (queueIndex in 0 until queue.lastIndex) {
        val insertAt = out.indexOfFirst { it is PlaylistRow.Track && it.index == queueIndex + 1 }
        if (insertAt >= 0) {
            out.add(insertAt, PlaylistRow.SectionLabel("UP NEXT"))
        }
    }
    return out
}

private fun albumHeaderArtist(tracks: List<TrackEntity>): String {
    val artists = tracks.map { playlistTrackArtist(it) }.distinct()
    return when {
        artists.size == 1 -> artists.first()
        artists.isEmpty() -> "Various artists"
        else -> "Various artists"
    }
}

private fun playlistRemainingMs(
    queue: List<TrackEntity>,
    queueIndex: Int,
    positionMs: Long,
    durationMs: Long,
): Long {
    if (queue.isEmpty()) return 0L
    var total = 0L
    for (i in queue.indices) {
        if (i < queueIndex) continue
        val trackDur = queue[i].durationMs.coerceAtLeast(0L)
        if (i == queueIndex) {
            val liveDur = durationMs.takeIf { it > 0 } ?: trackDur
            total += (liveDur - positionMs).coerceAtLeast(0L)
        } else {
            total += trackDur
        }
    }
    return total
}

private fun playlistProgress(
    queue: List<TrackEntity>,
    queueIndex: Int,
    positionMs: Long,
    durationMs: Long,
): Float {
    if (queue.isEmpty()) return 0f
    val total = queue.sumOf { it.durationMs.coerceAtLeast(0L) }.takeIf { it > 0 }
        ?: return ((queueIndex + 1f) / queue.size).coerceIn(0f, 1f)
    var done = 0L
    for (i in queue.indices) {
        if (i < queueIndex) {
            done += queue[i].durationMs.coerceAtLeast(0L)
        } else if (i == queueIndex) {
            val liveDur = durationMs.takeIf { it > 0 } ?: queue[i].durationMs
            done += positionMs.coerceIn(0L, liveDur.coerceAtLeast(0L))
        }
    }
    return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun formatTrackTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun formatDurationLong(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "$m min"
        else -> "${totalSec % 60}s"
    }
}

private fun playlistTrackTitle(track: TrackEntity): String =
    MetadataResolver.fixTagText(track.title).trim().ifBlank { "Unknown track" }

private fun playlistTrackArtist(track: TrackEntity): String {
    val artist = MetadataResolver.fixTagText(track.artistName).trim()
    if (artist.isNotBlank() && !MetadataResolver.isPlaceholderArtist(artist)) return artist
    val albumArtist = track.albumArtist?.let { MetadataResolver.fixTagText(it).trim() }
        ?.takeIf { it.isNotBlank() && !MetadataResolver.isPlaceholderArtist(it) }
    return albumArtist ?: "Unknown artist"
}
