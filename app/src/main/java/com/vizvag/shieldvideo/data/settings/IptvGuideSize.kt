package com.vizvag.shieldvideo.data.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit

enum class IptvGuideSize {
    Small,
    Medium,
    Large;

    val label: String
        get() = when (this) {
            Small -> "Small"
            Medium -> "Medium"
            Large -> "Large"
        }

    fun next(): IptvGuideSize = when (this) {
        Small -> Medium
        Medium -> Large
        Large -> Small
    }

    companion object {
        fun fromStorage(raw: String?): IptvGuideSize =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Medium
    }
}

data class IptvGuideMetrics(
    val wheelItemHeight: Dp,
    val guideItemHeight: Dp,
    val logoSize: Dp,
    val nameColumnWidth: Dp,
    val channelTitleSp: TextUnit,
    val groupTitleSp: TextUnit,
    val epgTitleSp: TextUnit,
    val epgTimeSp: TextUnit,
    val headerSp: TextUnit,
    val epgWindowMs: Long,
    val groupWheelWidth: Dp,
    val channelWheelWidthNoEpg: Dp,
    val channelWheelHeightFraction: Float,
    val groupWheelHeightFraction: Float
)

fun IptvGuideSize.metrics(): IptvGuideMetrics = when (this) {
    // Groups keep readable row heights; EPG channel rows stay denser.
    IptvGuideSize.Small -> IptvGuideMetrics(
        wheelItemHeight = 48.dp,
        guideItemHeight = 32.dp,
        logoSize = 16.dp,
        nameColumnWidth = 150.dp,
        channelTitleSp = 10.sp,
        groupTitleSp = 14.sp,
        epgTitleSp = 10.sp,
        epgTimeSp = 8.sp,
        headerSp = 10.sp,
        epgWindowMs = TimeUnit.HOURS.toMillis(2),
        groupWheelWidth = 260.dp,
        channelWheelWidthNoEpg = 300.dp,
        channelWheelHeightFraction = 0.66f,
        groupWheelHeightFraction = 0.68f
    )
    IptvGuideSize.Medium -> IptvGuideMetrics(
        wheelItemHeight = 56.dp,
        guideItemHeight = 38.dp,
        logoSize = 18.dp,
        nameColumnWidth = 170.dp,
        channelTitleSp = 11.sp,
        groupTitleSp = 16.sp,
        epgTitleSp = 11.sp,
        epgTimeSp = 9.sp,
        headerSp = 11.sp,
        epgWindowMs = TimeUnit.HOURS.toMillis(2),
        groupWheelWidth = 300.dp,
        channelWheelWidthNoEpg = 360.dp,
        channelWheelHeightFraction = 0.74f,
        groupWheelHeightFraction = 0.78f
    )
    IptvGuideSize.Large -> IptvGuideMetrics(
        wheelItemHeight = 68.dp,
        guideItemHeight = 48.dp,
        logoSize = 22.dp,
        nameColumnWidth = 200.dp,
        channelTitleSp = 12.sp,
        groupTitleSp = 18.sp,
        epgTitleSp = 12.sp,
        epgTimeSp = 10.sp,
        headerSp = 12.sp,
        epgWindowMs = TimeUnit.HOURS.toMillis(3),
        groupWheelWidth = 360.dp,
        channelWheelWidthNoEpg = 420.dp,
        channelWheelHeightFraction = 0.82f,
        groupWheelHeightFraction = 0.88f
    )
}
