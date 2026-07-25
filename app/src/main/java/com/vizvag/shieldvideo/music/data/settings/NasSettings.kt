package com.vizvag.shieldvideo.music.data.settings

data class NasSettings(
    val host: String = "192.168.68.50",
    val port: Int = 5000,
    val useHttps: Boolean = false,
    val musicPaths: List<String> = listOf("/music"),
    val username: String = "",
    val password: String = "",
    val trustSelfSigned: Boolean = true,
) {
    /** Primary music folder (first configured path). */
    val musicPath: String
        get() = musicPaths.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "/music"

    val normalizedHost: String
        get() = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trimEnd('/')

    val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val effectivePort = portForProtocol(useHttps, port)
            return "$scheme://$normalizedHost:$effectivePort"
        }

    val isConfigured: Boolean
        get() = normalizedHost.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        const val HTTP_PORT = 5000
        const val HTTPS_PORT = 5001

        fun portForProtocol(useHttps: Boolean, port: Int): Int = when (port) {
            HTTP_PORT, HTTPS_PORT -> if (useHttps) HTTPS_PORT else HTTP_PORT
            else -> port
        }

        fun normalizePaths(paths: List<String>): List<String> =
            paths.map { path ->
                val trimmed = path.trim().replace('\\', '/')
                when {
                    trimmed.isBlank() || trimmed == "/" -> "/music"
                    trimmed.startsWith("/") -> trimmed.trimEnd('/')
                    else -> "/${trimmed.trimEnd('/')}"
                }
            }
                .filter { it.isNotBlank() && it != "/" }
                .distinctBy { it.lowercase() }
                .ifEmpty { listOf("/music") }

        /** Longest configured root that contains [path], or the primary root. */
        fun rootForPath(path: String, roots: List<String>): String {
            val normalized = path.replace('\\', '/').trimEnd('/')
            val candidates = normalizePaths(roots)
            return candidates
                .filter {
                    normalized.equals(it, ignoreCase = true) ||
                        normalized.startsWith("$it/", ignoreCase = true)
                }
                .maxByOrNull { it.length }
                ?: candidates.first()
        }
    }
}
