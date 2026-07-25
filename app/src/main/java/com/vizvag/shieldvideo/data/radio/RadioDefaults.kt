package com.vizvag.shieldvideo.data.radio

/**
 * Default radio stations seeded on first install. Users can delete or edit any of these
 * in Settings → Radio; they are not hard-coded into the player.
 */
object RadioDefaults {
    fun stations(): List<CustomRadioStationConfig> = listOf(
        CustomRadioStationConfig(
            id = "bbc_radio_one",
            name = "BBC Radio 1",
            tagline = "New music and entertainment",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_01505109/live/ww/bbc_radio_one/bbc_radio_one.isml/dash/bbc_radio_one-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_one"
        ),
        CustomRadioStationConfig(
            id = "bbc_1xtra",
            name = "BBC 1Xtra",
            tagline = "The home of new black music",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_92079267/live/ww/bbc_1xtra/bbc_1xtra.isml/dash/bbc_1xtra-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_1xtra"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_one_dance",
            name = "BBC Radio 1 Dance",
            tagline = "Non-stop dance and electronic",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_62063831/live/ww/bbc_radio_one_dance/bbc_radio_one_dance.isml/dash/bbc_radio_one_dance-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_one_dance"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_two",
            name = "BBC Radio 2",
            tagline = "The home of entertaining popular music",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_74208725/live/ww/bbc_radio_two/bbc_radio_two.isml/dash/bbc_radio_two-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_two"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_three",
            name = "BBC Radio 3",
            tagline = "Classical, jazz, world and arts",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_23461179/live/ww/bbc_radio_three/bbc_radio_three.isml/dash/bbc_radio_three-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_three"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_four",
            name = "BBC Radio 4",
            tagline = "Intelligent speech, drama and comedy",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/dash/bbc_radio_fourfm-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_fourfm"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_four_extra",
            name = "BBC Radio 4 Extra",
            tagline = "Comedy, drama and the spoken word",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_26173715/live/ww/bbc_radio_four_extra/bbc_radio_four_extra.isml/dash/bbc_radio_four_extra-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_four_extra"
        ),
        CustomRadioStationConfig(
            id = "bbc_radio_five_live",
            name = "BBC Radio 5 Live",
            tagline = "Live news and sport",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_89021708/live/ww/bbc_radio_five_live/bbc_radio_five_live.isml/dash/bbc_radio_five_live-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_radio_five_live"
        ),
        CustomRadioStationConfig(
            id = "bbc_6music",
            name = "BBC 6 Music",
            tagline = "The alternative soundtrack to your life",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_81827798/live/ww/bbc_6music/bbc_6music.isml/dash/bbc_6music-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_6music"
        ),
        CustomRadioStationConfig(
            id = "bbc_world_service",
            name = "BBC World Service",
            tagline = "International news and analysis",
            streamUrl = "http://as-hls-ww.live.cf.md.bbci.co.uk/pool_07364996/live/ww/bbc_world_service_news_internet/bbc_world_service_news_internet.isml/bbc_world_service_news_internet-audio=320000.norewind.m3u8",
            bbcServiceId = "bbc_world_service_news_internet"
        )
    )
}
