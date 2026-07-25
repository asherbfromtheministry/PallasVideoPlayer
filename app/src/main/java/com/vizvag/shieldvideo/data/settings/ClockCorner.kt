package com.vizvag.shieldvideo.data.settings

enum class ClockCorner(val label: String) {
    BottomRight("Bottom right"),
    BottomLeft("Bottom left"),
    TopRight("Top right"),
    TopLeft("Top left");

    companion object {
        fun fromStorage(value: String?): ClockCorner =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BottomRight
    }
}
