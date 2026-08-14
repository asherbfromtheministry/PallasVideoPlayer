package com.vizvag.shieldvideo.data.youtube

import com.vizvag.shieldvideo.BuildConfig

/** Innertube / BotGuard client keys — loaded from gitignored personal.defaults.properties (debug only). */
internal object YoutubeClientKeys {
    val innertube: String get() = BuildConfig.DEFAULT_YOUTUBE_INNERTUBE_API_KEY
    val webEmbedded: String get() = BuildConfig.DEFAULT_YOUTUBE_WEB_EMBEDDED_API_KEY
    val tv: String get() = BuildConfig.DEFAULT_YOUTUBE_TV_API_KEY
    val ios: String get() = BuildConfig.DEFAULT_YOUTUBE_IOS_API_KEY
    val poToken: String get() = BuildConfig.DEFAULT_YOUTUBE_POTOKEN_API_KEY
}
