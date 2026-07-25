package com.vizvag.shieldvideo.data.settings

enum class ConnectionMode {
    SMB3,
    HTTP;

    val label: String
        get() = when (this) {
            SMB3 -> "SMB3"
            HTTP -> "HTTP"
        }

    val defaultPort: Int
        get() = when (this) {
            SMB3 -> 445
            HTTP -> 5000
        }

    companion object {
        fun fromStorage(raw: String?): ConnectionMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SMB3
    }
}
