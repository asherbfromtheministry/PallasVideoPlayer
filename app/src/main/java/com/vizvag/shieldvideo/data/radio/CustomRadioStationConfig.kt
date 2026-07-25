package com.vizvag.shieldvideo.data.radio

/** User-added radio station stored in app settings. */
data class CustomRadioStationConfig(
    val id: String,
    val name: String,
    val tagline: String = "",
    val streamUrl: String,
    /** Optional BBC Sounds service id (e.g. bbc_radio_one) for now-playing metadata. */
    val bbcServiceId: String = ""
)
