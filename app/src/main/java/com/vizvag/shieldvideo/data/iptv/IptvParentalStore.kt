package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import java.security.MessageDigest

class IptvParentalStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_parental", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hash(pin.trim())).apply()
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin.trim())
    }

    fun lockedGroups(): Set<String> =
        prefs.getStringSet(KEY_LOCKED_GROUPS, emptySet())?.toSet().orEmpty()

    fun setLockedGroups(groups: Set<String>) {
        prefs.edit().putStringSet(KEY_LOCKED_GROUPS, groups).apply()
    }

    fun isGroupLocked(group: String): Boolean =
        lockedGroups().any { it.equals(group, ignoreCase = true) }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_LOCKED_GROUPS = "locked_groups"
    }
}
