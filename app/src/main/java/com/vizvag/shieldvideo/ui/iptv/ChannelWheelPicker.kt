package com.vizvag.shieldvideo.ui.iptv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vizvag.shieldvideo.data.iptv.ChannelQuality
import com.vizvag.shieldvideo.data.iptv.GroupChannelOrder
import com.vizvag.shieldvideo.data.iptv.IptvChannel
import com.vizvag.shieldvideo.data.iptv.IptvProgramme
import com.vizvag.shieldvideo.data.iptv.XmltvParser
import com.vizvag.shieldvideo.data.settings.IptvGuideMetrics
import com.vizvag.shieldvideo.data.settings.IptvGuideSize
import com.vizvag.shieldvideo.data.settings.metrics
import com.vizvag.shieldvideo.ui.theme.AccentWarm
import com.vizvag.shieldvideo.ui.theme.CyanAccent
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val EpgSlotMs = TimeUnit.MINUTES.toMillis(30)
private val EpgSnapMs = TimeUnit.MINUTES.toMillis(15)
private const val MaxEpgSlots = 48

/** Floor to the previous :00 / :15 / :30 / :45 (local time). */
private fun floorToQuarterHour(ms: Long): Long {
    val remainder = ms % EpgSnapMs
    return if (remainder == 0L) ms else ms - remainder
}

@Composable
fun GroupWheelPicker(
    groups: List<String>,
    selectedGroup: String?,
    onConfirm: (String) -> Unit,
    displayNames: Map<String, String> = emptyMap(),
    hiddenGroups: Set<String> = emptySet(),
    onLongPressOptions: ((String) -> Unit)? = null,
    /** Group picked up for reordering — Up/Down carry it, OK drops it. */
    movingGroupKey: String? = null,
    onMoveStep: ((groupKey: String, delta: Int) -> Unit)? = null,
    onMoveDone: (() -> Unit)? = null,
    guideSize: IptvGuideSize = IptvGuideSize.Medium,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true
) {
    val metrics = remember(guideSize) { guideSize.metrics() }
    CylinderWheel(
        itemCount = groups.size,
        listIdentity = groups.size to groups.firstOrNull(),
        initialIndex = groups.indexOfFirst { it.equals(selectedGroup, true) }.takeIf { it >= 0 } ?: 0,
        footerHint = if (movingGroupKey != null) {
            "UP/DOWN move group · OK / BACK drop it here"
        } else {
            "SCROLL · OK open channels · HOLD OK options"
        },
        title = "GROUPS",
        moveMode = if (movingGroupKey != null && onMoveStep != null && onMoveDone != null) {
            WheelMoveMode(
                onStep = { delta -> onMoveStep(movingGroupKey, delta) },
                onDone = onMoveDone
            )
        } else {
            null
        },
        modifier = modifier,
        requestFocus = requestFocus,
        externalFocusRequester = focusRequester,
        dockToBottom = false,
        itemHeight = metrics.wheelItemHeight,
        keyFor = { index -> groups.getOrElse(index) { "g-$index" } },
        onConfirmIndex = { index -> groups.getOrNull(index)?.let(onConfirm) },
        onLongPressIndex = { index ->
            groups.getOrNull(index)?.let { onLongPressOptions?.invoke(it) }
        },
        onBack = null,
        itemContent = { index, highlighted, onClick ->
            val key = groups.getOrNull(index).orEmpty()
            val name = displayNames[key] ?: key
            val active = key.equals(selectedGroup, ignoreCase = true)
            val hidden = key in hiddenGroups
            WheelTextItem(
                title = name,
                subtitle = when {
                    hidden -> "Hidden — hold OK to show"
                    highlighted -> "Open channel wheel"
                    active -> "Current group"
                    else -> null
                },
                highlighted = highlighted,
                active = active,
                dimmed = hidden,
                metrics = metrics,
                onClick = onClick
            )
        }
    )
}

@Composable
fun ChannelWheelPicker(
    rows: List<IptvChannelRow>,
    selectedChannelId: String?,
    onConfirm: (IptvChannelRow) -> Unit,
    onLongPressOptions: (IptvChannelRow) -> Unit,
    onBackToGroups: () -> Unit,
    groupTitle: String,
    programmesFor: (IptvChannel) -> List<IptvProgramme>,
    isProgrammeRecording: (IptvChannel, IptvProgramme) -> Boolean = { _, _ -> false },
    showEpg: Boolean = true,
    guideSize: IptvGuideSize = IptvGuideSize.Medium,
    /** Bump to invalidate cached programme strips after EPG data changes. */
    epgVersion: Int = 0,
    /** Change to re-center the wheel on [selectedChannelId] (e.g. after fullscreen zapping). */
    recenterKey: Any? = null,
    /** Channel picked up for reordering — Up/Down carry it, OK drops it. */
    movingChannelId: String? = null,
    onMoveStep: ((channelId: String, delta: Int) -> Unit)? = null,
    onMoveDone: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true
) {
    val metrics = remember(guideSize) { guideSize.metrics() }
    val rowsState = rememberUpdatedState(rows)
    var epgSlotOffset by remember { mutableIntStateOf(0) }
    val nowMs = remember(epgSlotOffset, rows.size, showEpg) { System.currentTimeMillis() }
    // Window always starts on :00 / :15 / :30 / :45 — never e.g. 23:09.
    val windowStartMs = floorToQuarterHour(nowMs) + epgSlotOffset * EpgSlotMs
    val windowMs = metrics.epgWindowMs
    val slotLabel = if (epgSlotOffset <= 0) "NOW" else "+${epgSlotOffset * 30}m"
    val stripStartInset = metrics.logoSize + 8.dp + metrics.nameColumnWidth + 8.dp

    LaunchedEffect(rows.firstOrNull()?.channel?.id, rows.size, groupTitle, showEpg, guideSize) {
        epgSlotOffset = 0
    }

    val moving = movingChannelId != null
    CylinderWheel(
        itemCount = rows.size,
        listIdentity = Triple(rows.firstOrNull()?.channel?.id, rows.size, recenterKey),
        initialIndex = rows.indexOfFirst { it.channel.id == selectedChannelId }.takeIf { it >= 0 } ?: 0,
        footerHint = when {
            moving -> "UP/DOWN move channel · OK / BACK drop it here"
            showEpg -> "SCROLL · OK play · RIGHT later · LEFT earlier / groups"
            else -> "SCROLL · OK play · LEFT groups · HOLD OK options"
        },
        moveMode = if (movingChannelId != null && onMoveStep != null && onMoveDone != null) {
            WheelMoveMode(
                onStep = { delta -> onMoveStep(movingChannelId, delta) },
                onDone = onMoveDone
            )
        } else {
            null
        },
        title = groupTitle.uppercase(),
        subheader = if (showEpg) {
            {
                WheelEpgTimeHeader(
                    title = groupTitle.uppercase(),
                    nowMs = nowMs,
                    windowStartMs = windowStartMs,
                    windowMs = windowMs,
                    slotLabel = slotLabel,
                    stripStartInset = stripStartInset,
                    headerSp = metrics.headerSp
                )
            }
        } else {
            null
        },
        modifier = modifier,
        requestFocus = requestFocus,
        externalFocusRequester = focusRequester,
        dockToBottom = true,
        itemHeight = if (showEpg) metrics.guideItemHeight else metrics.wheelItemHeight,
        keyFor = { index -> rowsState.value.getOrNull(index)?.channel?.id ?: "c-$index" },
        onConfirmIndex = { index ->
            rowsState.value.getOrNull(index)?.let(onConfirm)
        },
        onLongPressIndex = { index ->
            rowsState.value.getOrNull(index)?.let(onLongPressOptions)
        },
        onBack = onBackToGroups,
        onEpgForward = if (showEpg) {
            {
                if (epgSlotOffset < MaxEpgSlots) epgSlotOffset += 1
            }
        } else {
            null
        },
        onEpgBack = if (showEpg) {
            {
                if (epgSlotOffset > 0) {
                    epgSlotOffset -= 1
                    true
                } else {
                    false
                }
            }
        } else {
            null
        },
        itemContent = { index, highlighted, onClick ->
            val row = rowsState.value.getOrNull(index)
            if (row != null) {
                val quality = remember(row.badges, row.channel.name) {
                    row.badges.ifEmpty { ChannelQuality.labelsFor(row.channel.name) }
                }
                val active = row.channel.id == selectedChannelId
                if (showEpg) {
                    val programmes = remember(row.channel.id, windowStartMs, windowMs, epgVersion) {
                        XmltvParser.inWindow(programmesFor(row.channel), windowStartMs, windowMs)
                    }
                    WheelChannelGuideItem(
                        row = row,
                        quality = quality,
                        programmes = programmes,
                        recordingProgrammeStarts = programmes
                            .filter { isProgrammeRecording(row.channel, it) }
                            .mapTo(mutableSetOf()) { it.startMs },
                        windowStartMs = windowStartMs,
                        windowMs = windowMs,
                        nowMs = nowMs,
                        highlighted = highlighted,
                        active = active,
                        metrics = metrics,
                        onClick = onClick
                    )
                } else {
                    WheelChannelNameItem(
                        row = row,
                        quality = quality,
                        highlighted = highlighted,
                        active = active,
                        metrics = metrics,
                        onClick = onClick
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
/** Reorder mode: Up/Down carry the centered item, OK / Back drop it. */
data class WheelMoveMode(
    val onStep: (Int) -> Unit,
    val onDone: () -> Unit
)

@Composable
private fun CylinderWheel(
    itemCount: Int,
    listIdentity: Any?,
    initialIndex: Int,
    footerHint: String,
    title: String,
    keyFor: (Int) -> Any,
    onConfirmIndex: (Int) -> Unit,
    onLongPressIndex: ((Int) -> Unit)?,
    onBack: (() -> Unit)?,
    moveMode: WheelMoveMode? = null,
    onCenteredIndex: ((Int) -> Unit)? = null,
    onEpgForward: (() -> Unit)? = null,
    onEpgBack: (() -> Boolean)? = null,
    subheader: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
    externalFocusRequester: FocusRequester? = null,
    dockToBottom: Boolean = false,
    itemHeight: Dp,
    itemContent: @Composable (
        index: Int,
        highlighted: Boolean,
        onClick: () -> Unit
    ) -> Unit
) {
    val panelShape = if (dockToBottom) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    } else {
        RoundedCornerShape(18.dp)
    }
    val listState = rememberLazyListState()
    val snap = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val localFocusRequester = remember { FocusRequester() }
    val focusRequester = externalFocusRequester ?: localFocusRequester
    var wheelFocused by remember { mutableStateOf(false) }
    var centeredIndex by remember { mutableIntStateOf(initialIndex.coerceAtLeast(0)) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val onConfirmState = rememberUpdatedState(onConfirmIndex)
    val onLongPressState = rememberUpdatedState(onLongPressIndex)
    val onCenteredState = rememberUpdatedState(onCenteredIndex)
    val onBackState = rememberUpdatedState(onBack)
    val onEpgForwardState = rememberUpdatedState(onEpgForward)
    val onEpgBackState = rememberUpdatedState(onEpgBack)
    val itemCountState = rememberUpdatedState(itemCount)
    val moveModeState = rememberUpdatedState(moveMode)

    LaunchedEffect(listIdentity) {
        if (itemCount <= 0) return@LaunchedEffect
        // Reordering shuffles the first item id; don't yank the wheel off the carried channel.
        if (moveModeState.value != null) return@LaunchedEffect
        val idx = initialIndex.coerceIn(0, itemCount - 1)
        listState.scrollToItem(idx)
        centeredIndex = idx
    }

    LaunchedEffect(requestFocus, listIdentity) {
        if (requestFocus && itemCount > 0) {
            delay(40)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(listState, itemCount) {
        snapshotFlow { nearestCenterIndex(listState) }
            .distinctUntilChanged()
            .collect { index ->
                if (index != null) centeredIndex = index
            }
    }

    LaunchedEffect(listState, listIdentity) {
        if (onCenteredIndex == null) return@LaunchedEffect
        snapshotFlow {
            listState.isScrollInProgress to nearestCenterIndex(listState)
        }
            .filter { (scrolling, index) -> !scrolling && index != null }
            .map { (_, index) -> index!! }
            .distinctUntilChanged()
            .collect { index ->
                delay(120)
                if (listState.isScrollInProgress) return@collect
                if (nearestCenterIndex(listState) == index) {
                    onCenteredState.value?.invoke(index)
                }
            }
    }

    fun stepWheel(delta: Int) {
        val last = itemCountState.value - 1
        if (last < 0) return
        val current = nearestCenterIndex(listState) ?: centeredIndex
        val target = (current + delta).coerceIn(0, last)
        if (target == current) return
        centeredIndex = target
        scrollJob?.cancel()
        scrollJob = scope.launch {
            listState.animateScrollToItem(target)
            centeredIndex = nearestCenterIndex(listState) ?: target
        }
    }

    fun confirmCentered() {
        val index = nearestCenterIndex(listState) ?: centeredIndex
        if (index in 0 until itemCountState.value) onConfirmState.value(index)
    }

    fun optionsCentered() {
        val handler = onLongPressState.value ?: return
        val index = nearestCenterIndex(listState) ?: centeredIndex
        if (index in 0 until itemCountState.value) handler(index)
    }

    Column(
        modifier = modifier
            .clip(panelShape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xF0121410), Color(0xE61A1C16))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
            .focusRequester(focusRequester)
            .onFocusChanged { wheelFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                val move = moveModeState.value
                if (move != null) {
                    return@onPreviewKeyEvent when {
                        event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                            move.onStep(-1)
                            stepWheel(-1)
                            true
                        }
                        event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                            move.onStep(1)
                            stepWheel(1)
                            true
                        }
                        (isSelect || event.key == Key.Back) && event.type == KeyEventType.KeyUp -> {
                            move.onDone()
                            true
                        }
                        // Swallow everything else so the move cannot be interrupted mid-flight.
                        else -> true
                    }
                }
                when {
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                        if (centeredIndex <= 0) {
                            false
                        } else {
                            stepWheel(-1); true
                        }
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                        val last = itemCountState.value - 1
                        if (centeredIndex >= last) {
                            false
                        } else {
                            stepWheel(1); true
                        }
                    }
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown -> {
                        val forward = onEpgForwardState.value
                        if (forward != null) {
                            forward(); true
                        } else {
                            false
                        }
                    }
                    event.key == Key.DirectionLeft && event.type == KeyEventType.KeyUp -> {
                        val epgBack = onEpgBackState.value
                        if (epgBack != null && epgBack()) {
                            true
                        } else {
                            val back = onBackState.value
                            if (back != null) {
                                back(); true
                            } else {
                                false
                            }
                        }
                    }
                    event.key == Key.Back && event.type == KeyEventType.KeyUp -> {
                        val epgBack = onEpgBackState.value
                        if (epgBack != null && epgBack()) {
                            true
                        } else {
                            val back = onBackState.value
                            if (back != null) {
                                back(); true
                            } else {
                                false
                            }
                        }
                    }
                    event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        if (onLongPressIndex != null) {
                            optionsCentered(); true
                        } else {
                            false
                        }
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (onLongPressIndex != null && longPressJob == null) {
                            longPressFired = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressFired = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        val wasLongPress = longPressFired
                        longPressJob?.cancel()
                        longPressJob = null
                        longPressFired = false
                        if (wasLongPress) {
                            optionsCentered()
                        } else {
                            confirmCentered()
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Fixed compact header — never covered by list scrims
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 10.dp, end = 10.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (subheader != null) {
                subheader()
            } else {
                Text(
                    text = title,
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val sidePad = (maxHeight / 2 - itemHeight / 2).coerceAtLeast(0.dp)

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight + 2.dp)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            moveMode != null -> AccentWarm.copy(alpha = 0.20f)
                            wheelFocused -> CyanAccent.copy(alpha = 0.14f)
                            else -> Color.White.copy(alpha = 0.06f)
                        }
                    )
                    .border(
                        // Normal focus is shown by the subtle background only. Keep an
                        // outline solely while a channel is picked up for reordering.
                        width = if (moveMode != null) 2.dp else 0.dp,
                        color = if (moveMode != null) AccentWarm else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
            )

            if (itemCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here", color = TextMuted, modifier = Modifier.padding(24.dp))
                }
            } else {
                LazyColumn(
                    state = listState,
                    flingBehavior = snap,
                    userScrollEnabled = true,
                    contentPadding = PaddingValues(vertical = sidePad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        count = itemCount,
                        key = { index -> keyFor(index) },
                        contentType = { _ -> "wheel" }
                    ) { index ->
                        itemContent(
                            index,
                            index == centeredIndex
                        ) {
                            scope.launch {
                                scrollJob?.cancel()
                                listState.animateScrollToItem(index)
                                centeredIndex = index
                                onConfirmState.value(index)
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = footerHint,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            textAlign = TextAlign.Center
        )
    }
}

private fun nearestCenterIndex(listState: LazyListState): Int? {
    val info = listState.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index
}

private fun Modifier.wheelRowChrome(highlighted: Boolean, active: Boolean): Modifier {
    val shape = RoundedCornerShape(8.dp)
    // Highlighted rows sit inside the wheel's center focus band — no extra border
    // (that was reading as a double white frame on the playing channel).
    return this
        .clip(shape)
        .background(
            when {
                highlighted -> CyanAccent.copy(alpha = 0.22f)
                active -> AccentWarm.copy(alpha = 0.24f)
                else -> Color.Transparent
            }
        )
        .border(
            width = if (!highlighted && active) 2.dp else 0.dp,
            color = if (!highlighted && active) AccentWarm else Color.Transparent,
            shape = shape
        )
}

@Composable
private fun WheelTextItem(
    title: String,
    subtitle: String?,
    highlighted: Boolean,
    active: Boolean,
    metrics: IptvGuideMetrics,
    onClick: () -> Unit,
    dimmed: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.wheelItemHeight)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .wheelRowChrome(highlighted, active)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = when {
                dimmed -> TextMuted.copy(alpha = 0.55f)
                highlighted -> TextCream
                else -> Color.White
            },
            fontSize = metrics.groupTitleSp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = if (active && !highlighted) AccentWarm else TextMuted,
                fontSize = metrics.headerSp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun WheelEpgTimeHeader(
    title: String,
    nowMs: Long,
    windowStartMs: Long,
    windowMs: Long,
    slotLabel: String,
    stripStartInset: Dp,
    headerSp: TextUnit
) {
    val tickCount = (windowMs / EpgSlotMs).toInt().coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = CyanAccent,
                fontSize = headerSp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1.2f)
            )
            Text(
                text = "EPG $slotLabel · RIGHT later",
                color = CyanAccent,
                fontSize = headerSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        // Tick labels on the snapped window (:00 / :30 …) + now needle.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stripStartInset, top = 4.dp, end = 8.dp)
                .height(26.dp)
        ) {
            val stripW = maxWidth
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(tickCount) { i ->
                    val tickMs = windowStartMs + i * EpgSlotMs
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(8.dp)
                                .background(Color.White.copy(alpha = 0.55f))
                        )
                        Text(
                            text = formatWheelClock(tickMs),
                            color = TextCream.copy(alpha = 0.9f),
                            fontSize = headerSp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
            val windowEndMs = windowStartMs + windowMs
            if (nowMs in windowStartMs until windowEndMs && windowMs > 0L) {
                val nowFrac = (nowMs - windowStartMs).toFloat() / windowMs.toFloat()
                Box(
                    modifier = Modifier
                        .offset(x = stripW * nowFrac.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(CyanAccent)
                )
            }
        }
    }
}

@Composable
private fun WheelChannelGuideItem(
    row: IptvChannelRow,
    quality: List<String>,
    programmes: List<IptvProgramme>,
    recordingProgrammeStarts: Set<Long>,
    windowStartMs: Long,
    windowMs: Long,
    nowMs: Long,
    highlighted: Boolean,
    active: Boolean,
    metrics: IptvGuideMetrics,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.guideItemHeight)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .wheelRowChrome(highlighted, active)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(metrics.logoSize)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (!row.channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = row.channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            } else {
                Text(
                    row.channel.name.take(2),
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = metrics.headerSp
                )
            }
        }

        Row(
            modifier = Modifier.width(metrics.nameColumnWidth),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = row.channel.name,
                color = if (highlighted) TextCream else Color.White,
                fontSize = metrics.channelTitleSp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (row.locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(11.dp)
                )
            }
            if (active && !highlighted) {
                Text(
                    text = "LIVE",
                    color = AccentWarm,
                    fontSize = metrics.epgTimeSp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            } else if (quality.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    quality.take(2).forEach { label ->
                        QualityChip(label, compact = true, confirmed = row.badgesConfirmed)
                    }
                }
            }
        }

        WheelRowEpgStrip(
            programmes = programmes,
            recordingProgrammeStarts = recordingProgrammeStarts,
            windowStartMs = windowStartMs,
            windowMs = windowMs,
            nowMs = nowMs,
            titleSp = metrics.epgTitleSp,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 1.dp, bottom = 1.dp, end = 4.dp)
        )
    }
}

@Composable
private fun WheelChannelNameItem(
    row: IptvChannelRow,
    quality: List<String>,
    highlighted: Boolean,
    active: Boolean,
    metrics: IptvGuideMetrics,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.wheelItemHeight)
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .wheelRowChrome(highlighted, active)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(metrics.logoSize)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (!row.channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = row.channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            } else {
                Text(
                    row.channel.name.take(2),
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = metrics.headerSp
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.channel.name,
                    color = if (highlighted) TextCream else Color.White,
                    fontSize = metrics.groupTitleSp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.locked) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp)
                    )
                }
            }
            if (active && !highlighted) {
                Text(
                    text = "Playing",
                    color = AccentWarm,
                    fontSize = metrics.headerSp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 1.dp)
                )
            } else if (quality.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.padding(top = 1.dp)
                ) {
                    quality.take(2).forEach { label ->
                        QualityChip(label, compact = true, confirmed = row.badgesConfirmed)
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelRowEpgStrip(
    programmes: List<IptvProgramme>,
    recordingProgrammeStarts: Set<Long>,
    windowStartMs: Long,
    windowMs: Long,
    nowMs: Long,
    titleSp: TextUnit,
    modifier: Modifier = Modifier
) {
    val windowEndMs = windowStartMs + windowMs
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        val totalW = maxWidth
        programmes.forEach { programme ->
            val start = max(programme.startMs, windowStartMs)
            val end = min(programme.stopMs, windowEndMs)
            if (end <= start) return@forEach
            val leftFrac = (start - windowStartMs).toFloat() / windowMs.toFloat()
            val widthFrac = (end - start).toFloat() / windowMs.toFloat()
            val airing = programme.isAiringAt(nowMs)
            val recording = programme.startMs in recordingProgrammeStarts
            val blockW = totalW * widthFrac.coerceIn(0.04f, 1f)
            val showTimes = blockW >= 72.dp
            val label = if (showTimes) {
                buildString {
                    if (recording) append("● REC  ")
                    append(formatWheelClock(programme.startMs))
                    append("  ")
                    append(programme.title)
                }
            } else {
                if (recording) "● ${programme.title}" else programme.title
            }
            Box(
                modifier = Modifier
                    .offset(x = totalW * leftFrac.coerceIn(0f, 1f))
                    .width(blockW)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = 0.5.dp,
                        color = when {
                            recording -> Color(0xFFFF5252).copy(alpha = 0.85f)
                            airing -> CyanAccent.copy(alpha = 0.28f)
                            else -> Color.White.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = label,
                    color = when {
                        recording -> Color(0xFFFF6E6E)
                        airing -> CyanAccent.copy(alpha = 0.92f)
                        else -> Color.White.copy(alpha = 0.64f)
                    },
                    fontSize = titleSp,
                    fontWeight = if (airing || recording) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
        if (nowMs in windowStartMs until windowEndMs && windowMs > 0L) {
            val nowFrac = (nowMs - windowStartMs).toFloat() / windowMs.toFloat()
            Box(
                modifier = Modifier
                    .offset(x = totalW * nowFrac.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(CyanAccent.copy(alpha = 0.42f))
            )
        }
        if (programmes.isEmpty()) {
            Text(
                text = "No EPG",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            )
        }
    }
}

private fun formatWheelClock(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun QualityChip(
    label: String,
    compact: Boolean = false,
    confirmed: Boolean = true
) {
    val kindColor = qualityBadgeColor(label)
    val chipColor = if (confirmed) kindColor else TextMuted
    Text(
        text = label,
        color = Color.White,
        fontSize = if (compact) 6.sp else 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = if (compact) 0.1.sp else 0.sp,
        lineHeight = if (compact) 7.sp else TextUnit.Unspecified,
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 2.dp else 4.dp))
            .background(chipColor.copy(alpha = if (compact) 0.22f else 0.28f))
            .border(
                if (compact) 0.5.dp else 1.dp,
                chipColor.copy(alpha = if (compact) 0.45f else 0.55f),
                RoundedCornerShape(if (compact) 2.dp else 4.dp)
            )
            .padding(
                horizontal = if (compact) 2.dp else 5.dp,
                vertical = if (compact) 0.dp else 1.dp
            )
    )
}

/** Resolution = cyan, frame rate = amber, HDR/SDR = violet, codec = green. */
private fun qualityBadgeColor(label: String): Color {
    val upper = label.uppercase()
    return when {
        upper.endsWith("FPS") -> Color(0xFFFFB74D)
        upper == "HDR" || upper == "SDR" || upper == "HLG" || upper == "DV" -> Color(0xFFCE93D8)
        upper == "HEVC" || upper == "H265" || upper == "AV1" || upper == "AVC" -> Color(0xFF81C784)
        upper == "4K" || upper == "UHD" || upper == "FHD" || upper == "HD" || upper == "SD" -> CyanAccent
        else -> CyanAccent
    }
}

@Composable
fun ChannelOptionsSheet(
    row: IptvChannelRow,
    canMove: Boolean,
    onAssignEpg: () -> Unit,
    onRecord: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onFullscreen: () -> Unit,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    // Ignore OK/click for a beat after open in case a late KeyUp still arrives.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(row.channel.id) {
        armed = false
        delay(280)
        armed = true
    }
    fun run(action: () -> Unit) {
        if (armed) action()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(row.channel.name, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OptionRow("Assign EPG…") { run(onAssignEpg) }
                OptionRow("Record…") { run(onRecord) }
                OptionRow(if (row.favorite) "Remove favorite" else "Add favorite") { run(onFavorite) }
                OptionRow("Rename channel") { run(onRename) }
                if (canMove) {
                    OptionRow("Move channel…") { run(onMove) }
                }
                OptionRow("Fullscreen") { run(onFullscreen) }
                OptionRow("Open in VLC") { run(onOpenExternal) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { run(onDismiss) }) {
                Text("Close", color = CyanAccent)
            }
        },
        containerColor = Color(0xF022261E)
    )
}

@Composable
fun GroupOptionsSheet(
    groupName: String,
    currentOrder: GroupChannelOrder,
    hidden: Boolean,
    onOrder: (GroupChannelOrder) -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onToggleHidden: () -> Unit,
    onDismiss: () -> Unit
) {
    // A long-press release must not immediately activate the first option.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(groupName) {
        armed = false
        delay(280)
        armed = true
    }
    fun run(action: () -> Unit) {
        if (armed) action()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(groupName, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Channel order",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                GroupChannelOrder.entries.forEach { mode ->
                    val marker = if (mode == currentOrder) "✓  " else "    "
                    OptionRow("$marker${mode.label}") {
                        run {
                            onOrder(mode)
                            onDismiss()
                        }
                    }
                }
                OptionRow("Rename group…") { run(onRename) }
                OptionRow("Move group…") { run(onMove) }
                OptionRow(if (hidden) "Show group" else "Hide group") {
                    run {
                        onToggleHidden()
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { run(onDismiss) }) {
                Text("Close", color = CyanAccent)
            }
        },
        containerColor = Color(0xFF2E342A)
    )
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    )
}
