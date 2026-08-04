package com.vizvag.shieldvideo.ui.iptv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.data.iptv.GroupChannelOrder
import com.vizvag.shieldvideo.data.iptv.IptvDefaults
import com.vizvag.shieldvideo.ui.theme.FocusRing
import com.vizvag.shieldvideo.ui.theme.Motion
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.TextCream
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Horizontal category cards. [selectedGroup] is the only source of truth for which
 * card is focused — always the ViewModel group the user is browsing.
 */
@Composable
fun GroupHeroGallery(
    groups: List<String>,
    selectedGroup: String?,
    displayNames: Map<String, String> = emptyMap(),
    hiddenGroups: Set<String> = emptySet(),
    channelCounts: Map<String, Int> = emptyMap(),
    orderModes: Map<String, GroupChannelOrder> = emptyMap(),
    restoreEpoch: Int = 0,
    onConfirm: (String) -> Unit,
    onLongPressOptions: ((String) -> Unit)? = null,
    movingGroupKey: String? = null,
    onMoveStep: ((groupKey: String, delta: Int) -> Unit)? = null,
    onMoveJumpToEdge: ((groupKey: String, toTop: Boolean) -> Unit)? = null,
    onMoveDone: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val density = LocalDensity.current
    val cardWidth = 300.dp
    val cardGap = 22.dp
    val selectedIndex = indexOfGroup(groups, selectedGroup)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    // Always start on the selected group — never default to index 0 / Favorites.
    var focusedIndex by remember { mutableIntStateOf(selectedIndex) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val onConfirmState = rememberUpdatedState(onConfirm)
    val onLongPressState = rememberUpdatedState(onLongPressOptions)
    val moveMode = movingGroupKey != null && onMoveStep != null && onMoveDone != null
    val sidePad = remember(rowWidthPx, density) {
        if (rowWidthPx <= 0) {
            40.dp
        } else {
            with(density) {
                ((rowWidthPx - cardWidth.roundToPx()) / 2f)
                    .toDp()
                    .coerceAtLeast(24.dp)
            }
        }
    }

    // Pin scroll + focus to selectedGroup whenever we appear or the selection changes.
    LaunchedEffect(selectedGroup, restoreEpoch, groups, requestFocus, sidePad) {
        if (groups.isEmpty()) return@LaunchedEffect
        val idx = indexOfGroup(groups, selectedGroup)
        focusedIndex = idx
        if (!requestFocus) return@LaunchedEffect
        listState.scrollToItem(idx)
        delay(48)
        focusedIndex = idx
        listState.scrollToItem(idx)
    }

    LaunchedEffect(requestFocus, restoreEpoch, selectedGroup, groups.size) {
        if (requestFocus && groups.isNotEmpty()) {
            delay(80)
            runCatching { focus.requestFocus() }
        }
    }

    fun step(delta: Int) {
        if (groups.isEmpty()) return
        val target = (focusedIndex + delta).coerceIn(0, groups.lastIndex)
        if (target == focusedIndex) return
        focusedIndex = target
        scope.launch { listState.animateScrollToItem(target) }
    }

    fun jump(toStart: Boolean) {
        if (groups.isEmpty()) return
        val target = if (toStart) 0 else groups.lastIndex
        focusedIndex = target
        scope.launch { listState.scrollToItem(target) }
    }

    fun confirmFocused() {
        groups.getOrNull(focusedIndex)?.let { onConfirmState.value(it) }
    }

    fun optionsFocused() {
        val handler = onLongPressState.value ?: return
        groups.getOrNull(focusedIndex)?.let(handler)
    }

    Column(
        modifier = modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (moveMode) {
                    return@onPreviewKeyEvent when {
                        event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown -> {
                            onMoveStep!!(movingGroupKey!!, -1); step(-1); true
                        }
                        event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown -> {
                            onMoveStep!!(movingGroupKey!!, 1); step(1); true
                        }
                        event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                            onMoveJumpToEdge?.invoke(movingGroupKey!!, true); jump(true); true
                        }
                        event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                            onMoveJumpToEdge?.invoke(movingGroupKey!!, false); jump(false); true
                        }
                        (isSelect || event.key == Key.Back) && event.type == KeyEventType.KeyUp -> {
                            onMoveDone!!(); true
                        }
                        else -> true
                    }
                }
                when {
                    event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown -> {
                        // Consume Left on the first card so focus cannot escape into the nav rail
                        // behind the Live TV group overlay.
                        if (focusedIndex <= 0) true else { step(-1); true }
                    }
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown -> {
                        if (focusedIndex >= groups.lastIndex) true else { step(1); true }
                    }
                    event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                        if (onLongPressOptions != null) { optionsFocused(); true } else false
                    }
                    isSelect && event.type == KeyEventType.KeyDown -> {
                        if (onLongPressOptions != null && longPressJob == null) {
                            longPressFired = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                longPressFired = true
                            }
                        }
                        true
                    }
                    isSelect && event.type == KeyEventType.KeyUp -> {
                        val wasLong = longPressFired
                        longPressJob?.cancel()
                        longPressJob = null
                        longPressFired = false
                        if (wasLong) optionsFocused() else confirmFocused()
                        true
                    }
                    else -> false
                }
            }
            .fillMaxSize()
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        Text(
            text = if (moveMode) "REORDER GROUPS" else "CATEGORIES",
            color = TextCream.copy(alpha = 0.55f),
            fontFamily = PallasFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
        )

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePad),
            horizontalArrangement = Arrangement.spacedBy(cardGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { rowWidthPx = it.width },
            userScrollEnabled = !moveMode,
        ) {
            itemsIndexed(groups, key = { _, g -> g }) { index, key ->
                val name = displayNames[key] ?: key
                val count = channelCounts[key]
                val hidden = key in hiddenGroups
                val isFocusedCard = index == focusedIndex
                val isCarried = moveMode && key == movingGroupKey
                val orderMode = orderModes[key] ?: GroupChannelOrder.CUSTOM
                val kind = when {
                    key == IptvDefaults.FAVORITES_GROUP -> "FAVORITES"
                    key == IptvDefaults.ALL_GROUP -> "ALL CHANNELS"
                    hidden -> "HIDDEN"
                    else -> "GROUP"
                }
                GroupHeroCard(
                    groupKey = key,
                    kind = kind,
                    title = name,
                    channelCount = count,
                    orderLabel = when {
                        isCarried -> null
                        orderMode == GroupChannelOrder.ALPHABETICAL -> "A–Z"
                        orderMode == GroupChannelOrder.MOST_WATCHED -> "Most watched"
                        else -> null
                    },
                    statusLabel = when {
                        isCarried -> "Moving · OK drop"
                        hidden -> "Hidden"
                        else -> null
                    },
                    highlighted = isFocusedCard || isCarried,
                    dimmed = hidden && !isFocusedCard,
                    onClick = { onConfirm(key) },
                )
            }
        }

        Text(
            text = if (moveMode) {
                "LEFT/RIGHT step · UP first · DOWN last · OK drop"
            } else {
                "LEFT/RIGHT browse · OK open · HOLD OK options"
            },
            color = TextMuted.copy(alpha = 0.75f),
            fontFamily = PallasFontFamily,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

private fun indexOfGroup(groups: List<String>, selectedGroup: String?): Int {
    if (groups.isEmpty()) return 0
    val want = selectedGroup?.trim().orEmpty()
    if (want.isEmpty()) return 0
    return groups.indexOfFirst { it.equals(want, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
}

@Composable
private fun GroupHeroCard(
    groupKey: String,
    kind: String,
    title: String,
    channelCount: Int?,
    orderLabel: String?,
    statusLabel: String?,
    highlighted: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    val palette = remember(groupKey, kind) { groupCardPalette(groupKey, kind) }
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.08f else 0.94f,
        animationSpec = Motion.focusSpring(),
        label = "heroScale",
    )
    val glow by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = Motion.focusSpring(),
        label = "heroGlow",
    )
    val shape = RoundedCornerShape(18.dp)
    val accent = palette.accent
    val top = palette.top
    val mid = palette.mid
    val bottom = palette.bottom
    val meta = buildList {
        orderLabel?.let { add(it) }
        statusLabel?.let { add(it) }
    }.joinToString(" · ")

    Box(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight(0.86f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = when {
                    dimmed && !highlighted -> 0.42f
                    highlighted -> 1f
                    else -> 0.78f
                }
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to top.copy(alpha = 0.92f + 0.08f * glow),
                        0.42f to mid.copy(alpha = 0.88f),
                        1f to bottom.copy(alpha = 0.96f),
                    ),
                ),
            )
            .border(
                width = if (highlighted) 2.5.dp else 1.dp,
                color = if (highlighted) {
                    FocusRing
                } else {
                    accent.copy(alpha = 0.45f)
                },
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = {
                feedback.click()
                onClick()
            }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.55f + 0.25f * glow),
                            accent.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                        center = Offset(240f, 40f),
                        radius = 420f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC060608)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = kind,
                color = if (highlighted) accent.copy(alpha = 0.95f) else accent.copy(alpha = 0.75f),
                fontFamily = PallasFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                color = TextCream,
                fontFamily = PallasFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (highlighted) 24.sp else 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (highlighted) 28.sp else 24.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(top = 6.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                if (channelCount != null) {
                    Text(
                        text = buildString {
                            append(channelCount)
                            append(if (channelCount == 1) " channel" else " channels")
                        },
                        color = accent,
                        fontFamily = PallasFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (highlighted) 22.sp else 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = TextCream.copy(alpha = 0.65f),
                        fontFamily = PallasFontFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private data class GroupCardPalette(
    val accent: Color,
    val top: Color,
    val mid: Color,
    val bottom: Color,
)

private fun groupCardPalette(groupKey: String, kind: String): GroupCardPalette {
    when {
        groupKey == IptvDefaults.FAVORITES_GROUP || kind == "FAVORITES" ->
            return GroupCardPalette(
                accent = Color(0xFFFF7AB8),
                top = Color(0xFF5A1838),
                mid = Color(0xFF2A0F22),
                bottom = Color(0xFF0C070C),
            )
        groupKey == IptvDefaults.ALL_GROUP || kind == "ALL CHANNELS" ->
            return GroupCardPalette(
                accent = Color(0xFF5CE1FF),
                top = Color(0xFF123A52),
                mid = Color(0xFF0C2238),
                bottom = Color(0xFF060B12),
            )
        kind == "HIDDEN" ->
            return GroupCardPalette(
                accent = Color(0xFFB8A4FF),
                top = Color(0xFF2A2448),
                mid = Color(0xFF161228),
                bottom = Color(0xFF08060E),
            )
    }
    val palettes = listOf(
        GroupCardPalette(Color(0xFFC6F255), Color(0xFF2F4518), Color(0xFF16220E), Color(0xFF070A05)),
        GroupCardPalette(Color(0xFFFF8A4C), Color(0xFF5A2814), Color(0xFF2A140C), Color(0xFF0C0705)),
        GroupCardPalette(Color(0xFF7C9CFF), Color(0xFF1C2A5A), Color(0xFF101830), Color(0xFF060810)),
        GroupCardPalette(Color(0xFFFF5C8A), Color(0xFF5A1430), Color(0xFF2A0C1A), Color(0xFF0C0508)),
        GroupCardPalette(Color(0xFF3DFFC8), Color(0xFF0E3A32), Color(0xFF0A221E), Color(0xFF050C0A)),
        GroupCardPalette(Color(0xFFFFD166), Color(0xFF4A3810), Color(0xFF241C08), Color(0xFF0C0904)),
        GroupCardPalette(Color(0xFFB388FF), Color(0xFF2E1A58), Color(0xFF180E30), Color(0xFF080510)),
        GroupCardPalette(Color(0xFF4CD4FF), Color(0xFF0E3550), Color(0xFF0A1E2E), Color(0xFF050A10)),
        GroupCardPalette(Color(0xFFFF6B6B), Color(0xFF4A1418), Color(0xFF280C10), Color(0xFF0C0506)),
        GroupCardPalette(Color(0xFF9AE66E), Color(0xFF244018), Color(0xFF14220E), Color(0xFF060A05)),
    )
    val idx = abs(groupKey.lowercase().hashCode()) % palettes.size
    return palettes[idx]
}
