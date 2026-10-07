package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

internal class AutostartPreferences(
    private val prefs: SharedPreferences,
) {
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_AUTOSTART_ON_BOOT, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART_ON_BOOT, enabled).apply()
    }

    companion object {
        const val KEY_AUTOSTART_ON_BOOT = "autostart_on_boot"
    }
}
