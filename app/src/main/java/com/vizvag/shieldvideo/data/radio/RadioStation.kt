package com.vizvag.shieldvideo.data.radio

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.vizvag.shieldvideo.R

/**
 * Runtime radio station used by the player UI. All stations come from user settings
 * ([CustomRadioStationConfig]); nothing is hard-coded in the player.
 *
 * [bbcServiceId] enables BBC Sounds RMS metadata when set.
 * [streamFallbackUrls] tries lower bitrates if the 320 kbps UK feed is geo-blocked.
 * [logoRes] is set for known BBC station ids when a bundled logo drawable exists.
 */
data class RadioStation(
    val id: String,
    val name: String,
    val shortName: String,
    val tagline: String,
    val streamUrl: String,
    val bbcServiceId: String,
    val accent: Color,
    val accentDeep: Color,
    @DrawableRes val logoRes: Int? = null
) {
    val streamFallbackUrls: List<String>
        get() {
            val urls = mutableListOf(streamUrl)
            if (streamUrl.contains("320000")) {
                urls += streamUrl.replace("320000", "96000")
                urls += streamUrl.replace("320000", "48000")
            }
            return urls.distinct()
        }
}

object RadioStations {
    fun all(configs: List<CustomRadioStationConfig>): List<RadioStation> =
        configs.map(::fromConfig)

    fun byId(id: String?, configs: List<CustomRadioStationConfig>): RadioStation? =
        all(configs).firstOrNull { it.id == id }

    fun fromConfig(config: CustomRadioStationConfig): RadioStation {
        val (accent, accentDeep) = accentForId(config.id)
        return RadioStation(
            id = config.id,
            name = config.name.ifBlank { "Radio" },
            shortName = shortLabel(config.name),
            tagline = config.tagline.ifBlank { "Internet radio" },
            streamUrl = config.streamUrl.trim(),
            bbcServiceId = config.bbcServiceId.trim(),
            accent = accent,
            accentDeep = accentDeep,
            logoRes = logoResForId(config.id)
        )
    }

    @DrawableRes
    private fun logoResForId(id: String): Int? = when (id) {
        "bbc_radio_one" -> R.drawable.radio_logo_bbc_radio_one
        "bbc_radio_one_dance" -> R.drawable.radio_logo_bbc_radio_one_dance
        "bbc_1xtra" -> R.drawable.radio_logo_bbc_1xtra
        "bbc_radio_two" -> R.drawable.radio_logo_bbc_radio_two
        "bbc_radio_three" -> R.drawable.radio_logo_bbc_radio_three
        "bbc_radio_four" -> R.drawable.radio_logo_bbc_radio_four
        "bbc_radio_four_extra" -> R.drawable.radio_logo_bbc_radio_four_extra
        "bbc_radio_five_live" -> R.drawable.radio_logo_bbc_radio_five_live
        "bbc_6music" -> R.drawable.radio_logo_bbc_6music
        "bbc_world_service" -> R.drawable.radio_logo_bbc_world_service
        "bbc_asian_network" -> R.drawable.radio_logo_bbc_asian_network
        else -> null
    }

    private fun shortLabel(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "•"

        val rest = trimmed.removePrefix("BBC ").trim()

        Regex("^Radio (\\d+)(?:\\s+(\\w+))?").find(rest)?.let { match ->
            val num = match.groupValues[1]
            val suffix = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.first()?.uppercaseChar()
            return if (suffix != null) "R$num$suffix" else "R$num"
        }

        Regex("^(\\d+)\\s*(\\w+)").find(rest)?.let { match ->
            val num = match.groupValues[1]
            val letter = match.groupValues[2].first().uppercaseChar()
            return "$num$letter"
        }

        val words = rest.split(Regex("\\s+"))
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            rest.length >= 2 -> rest.take(2).uppercase()
            else -> rest.uppercase()
        }
    }

    /** Brand accents aligned with official BBC station logo colours. */
    private fun accentForId(id: String): Pair<Color, Color> = when (id) {
        "bbc_radio_one", "bbc_radio_one_dance", "bbc_1xtra" ->
            Color(0xFFE8E8E8) to Color(0xFF1A1A1A)
        "bbc_radio_two" ->
            Color(0xFFFF8A1F) to Color(0xFF4A2808)
        "bbc_radio_three" ->
            Color(0xFFE53935) to Color(0xFF3A0A08)
        "bbc_radio_four", "bbc_radio_four_extra" ->
            Color(0xFF1A3A8A) to Color(0xFF0A1528)
        "bbc_radio_five_live" ->
            Color(0xFF2AA8B8) to Color(0xFF0A2A30)
        "bbc_6music" ->
            Color(0xFF39B54A) to Color(0xFF0F2A12)
        "bbc_world_service" ->
            Color(0xFFB33A2E) to Color(0xFF2A0E0C)
        "bbc_asian_network" ->
            Color(0xFF7B3FA0) to Color(0xFF1E0A28)
        else -> {
            val palette = listOf(
                Color(0xFFFF2D95) to Color(0xFF5A0A32),
                Color(0xFF39FF14) to Color(0xFF0F2A0A),
                Color(0xFFC8FF00) to Color(0xFF2A3200),
                Color(0xFFFF8A1F) to Color(0xFF4A2808),
                Color(0xFF7EC8E3) to Color(0xFF0E2A38),
                Color(0xFFE8E2D4) to Color(0xFF2A2218),
                Color(0xFFD4A574) to Color(0xFF2E2214),
                Color(0xFFFF3B30) to Color(0xFF3A0A08),
                Color(0xFFD4DC7A) to Color(0xFF1E2410),
                Color(0xFF8FA8B5) to Color(0xFF121820)
            )
            val idx = id.hashCode().and(0x7FFFFFFF) % palette.size
            palette[idx]
        }
    }
}
