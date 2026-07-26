package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

internal class LocalProxyFrontendRepository(
    private val prefs: SharedPreferences,
) {
    fun load(): LocalProxyFrontendType {
        val raw = prefs.getString(KEY_FRONTEND_TYPE, null)
        val parsed = LocalProxyFrontendType.fromPref(raw)
        if (raw != parsed.prefValue) {
            save(parsed)
        }
        return parsed
    }

    fun save(type: LocalProxyFrontendType) {
        prefs.edit().putString(KEY_FRONTEND_TYPE, type.prefValue).apply()
    }

    companion object {
        const val KEY_FRONTEND_TYPE = "local_proxy_frontend_type_v1"
    }
}
