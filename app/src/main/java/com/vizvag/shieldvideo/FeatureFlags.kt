package com.vizvag.shieldvideo

/**
 * Compile-time feature gates. Public GitHub ("clean") builds flip these without
 * deleting source — debug/personal builds keep everything enabled.
 */
object FeatureFlags {
    /** In-app YouTube viewer (home tile, rail, settings, remote play). */
    val youtube: Boolean get() = BuildConfig.FEATURE_YOUTUBE
}
