package com.vizvag.shieldvideo.data.nas

import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.ConnectionMode

object NasConnectionErrors {
    fun friendly(error: Throwable, settings: AppSettings): String {
        val raw = error.message?.trim().orEmpty().ifBlank { "Connection failed" }
        val lower = raw.lowercase()
        val fromVpn = Regex("""from\s+/?(10\.|100\.)""")
            .containsMatchIn(lower)
        val toLan = settings.host.startsWith("192.168.") ||
            settings.host.startsWith("10.") ||
            Regex("""\b192\.168\.\d+\.\d+\b""").containsMatchIn(raw)
        val timedOut = "timeout" in lower || "timed out" in lower || "after 15000ms" in lower ||
            "failed to connect" in lower

        return buildString {
            when {
                fromVpn && toLan -> {
                    append("This Shield is using a VPN address (")
                    append(extractFromAddress(raw) ?: "10.x")
                    append(") to reach the NAS on the LAN (")
                    append(settings.host)
                    append("). Turn off Tailscale/VPN on this device, or allow local LAN access, then try again.")
                }
                timedOut && settings.connectionMode == ConnectionMode.HTTP -> {
                    append("Cannot reach DSM File Station at ")
                    append(settings.host)
                    append(":")
                    append(settings.port)
                    append(". Check the NAS is on, File Station is enabled, or switch Connection to SMB3.")
                }
                timedOut && settings.connectionMode == ConnectionMode.SMB3 -> {
                    append("Cannot reach the NAS SMB share at ")
                    append(settings.host)
                    append(":")
                    append(settings.port)
                    append(". Check Wi‑Fi/LAN and the Synology SMB service.")
                }
                else -> append(raw)
            }
            if (fromVpn && toLan) {
                append("\n\nDetail: ")
                append(raw)
            }
        }
    }

    private fun extractFromAddress(raw: String): String? =
        Regex("""from\s+/([^:\s]+)""").find(raw)?.groupValues?.getOrNull(1)
}
