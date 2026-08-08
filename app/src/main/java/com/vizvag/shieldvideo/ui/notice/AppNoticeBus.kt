package com.vizvag.shieldvideo.ui.notice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class AppNoticeKind {
    Success,
    Error,
    Info,
    Progress,
}

data class AppNotice(
    val id: Long,
    val message: String,
    val kind: AppNoticeKind = AppNoticeKind.Info,
    /** Optional eyebrow above the body (e.g. "NAS", "Live TV"). */
    val title: String? = null,
    val durationMs: Long = defaultDuration(kind),
) {
    companion object {
        fun defaultDuration(kind: AppNoticeKind): Long = when (kind) {
            AppNoticeKind.Progress -> 0L // until replaced / dismissed
            AppNoticeKind.Error -> 4_500L
            AppNoticeKind.Success -> 3_200L
            AppNoticeKind.Info -> 3_200L
        }
    }
}

/**
 * App-wide transient notices. Prefer this over bare accent-colored Text overlays.
 * UI lives in [AppNoticeHost] (MainActivity).
 */
object AppNoticeBus {
    private val seq = AtomicLong(1L)
    private val _current = MutableStateFlow<AppNotice?>(null)
    val current: StateFlow<AppNotice?> = _current.asStateFlow()

    fun show(
        message: String,
        kind: AppNoticeKind = AppNoticeKind.Info,
        title: String? = null,
        durationMs: Long = AppNotice.defaultDuration(kind),
    ): Long {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return -1L
        val id = seq.getAndIncrement()
        _current.value = AppNotice(
            id = id,
            message = trimmed,
            kind = kind,
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs,
        )
        return id
    }

    fun success(message: String, title: String? = null) =
        show(message, AppNoticeKind.Success, title)

    fun error(message: String, title: String? = null) =
        show(message, AppNoticeKind.Error, title)

    fun info(message: String, title: String? = null) =
        show(message, AppNoticeKind.Info, title)

    fun progress(message: String, title: String? = null) =
        show(message, AppNoticeKind.Progress, title, durationMs = 0L)

    fun dismiss(id: Long? = null) {
        val cur = _current.value ?: return
        if (id == null || cur.id == id) {
            _current.value = null
        }
    }

    fun inferKind(message: String): AppNoticeKind {
        val m = message.lowercase()
        return when {
            m.contains("fail") ||
                m.contains("error") ||
                m.contains("incorrect") ||
                m.contains("unable") ||
                m.contains("not installed") ||
                m.contains("denied") ||
                m.startsWith("no ") && (m.contains("available") || m.contains("yet")) ->
                AppNoticeKind.Error
            m.contains("ok") ||
                m.contains("saved") ||
                m.contains("refreshed") ||
                m.contains("scheduled") ||
                m.contains("paired") ||
                m.contains("connected") ||
                m.contains("complete") ||
                m.contains("moved") ||
                m.contains("renamed") ||
                m.contains("assigned") ||
                m.contains("stopped") ||
                m.contains("recording") && !m.contains("fail") ->
                AppNoticeKind.Success
            m.endsWith("…") ||
                m.endsWith("...") ||
                m.contains("testing") ||
                m.contains("refreshing") ||
                m.contains("loading") ||
                m.contains("indexing") ||
                m.contains("packaging") ||
                m.contains("matching") ||
                m.contains("working") ||
                m.contains("pairing") ||
                m.contains("exporting") ||
                m.contains("importing") ||
                m.contains("downloading") ||
                m.contains("scanning") ->
                AppNoticeKind.Progress
            else -> AppNoticeKind.Info
        }
    }
}
