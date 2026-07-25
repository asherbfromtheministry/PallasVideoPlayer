package com.vizvag.shieldvideo.music.ui

import androidx.compose.ui.graphics.Color

fun colorFromString(input: String): Color {
    val hash = input.hashCode()
    val r = ((hash shr 16) and 0xFF) / 255f * 0.6f + 0.2f
    val g = ((hash shr 8) and 0xFF) / 255f * 0.6f + 0.2f
    val b = (hash and 0xFF) / 255f * 0.6f + 0.2f
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

fun colorFromStringSecondary(input: String): Color {
    val base = colorFromString(input)
    return Color(
        red = (base.red * 0.7f).coerceIn(0f, 1f),
        green = (base.green * 0.7f).coerceIn(0f, 1f),
        blue = (base.blue * 0.85f).coerceIn(0f, 1f),
    )
}
