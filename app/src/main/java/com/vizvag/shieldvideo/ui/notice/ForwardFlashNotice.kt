package com.vizvag.shieldvideo.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Forwards a screen-local flash string into [AppNoticeBus], then clears it.
 * Timed notices only — ongoing Progress toasts should call [AppNoticeBus.progress] directly.
 */
@Composable
fun ForwardFlashNotice(
    message: String?,
    title: String? = null,
    skip: Boolean = false,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message, skip) {
        if (skip) return@LaunchedEffect
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return@LaunchedEffect
        val inferred = AppNoticeBus.inferKind(msg)
        val kind = if (inferred == AppNoticeKind.Progress) AppNoticeKind.Info else inferred
        AppNoticeBus.show(
            message = msg,
            kind = kind,
            title = title,
            durationMs = AppNotice.defaultDuration(kind).coerceAtLeast(2_800L),
        )
        onConsumed()
    }
}
