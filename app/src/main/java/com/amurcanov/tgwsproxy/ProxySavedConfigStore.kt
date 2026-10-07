package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

internal const val PROXY_PREFS_NAME = "ProxyPrefs"

internal data class ProxySavedConfig(
    val port: Int = DEFAULT_LOCAL_PROXY_PORT,
    val runtimeConfig: String = "",
    val poolSize: Int = 4,
    val frontendType: LocalProxyFrontendType = LocalProxyFrontendType.DEFAULT,
) {
    fun isSufficientForStart(): Boolean {
        if (port !in 1..65535 || poolSize <= 0) {
            return false
        }
        return when (frontendType) {
            LocalProxyFrontendType.SOCKS5 -> runtimeConfig.isNotBlank()
            LocalProxyFrontendType.MTPROTO_EXPERIMENTAL -> true
        }
    }
}

internal class ProxySavedConfigStore(
    private val prefs: SharedPreferences,
) {
    fun load(): ProxySavedConfig {
        val savedPort = prefs.getInt(KEY_LAST_PORT, DEFAULT_LOCAL_PROXY_PORT)
        val migrationDone = prefs.getBoolean(KEY_LAST_PORT_DEFAULT_MIGRATED, false)
        val port = if (!migrationDone && savedPort == LEGACY_DEFAULT_LOCAL_PROXY_PORT) {
            DEFAULT_LOCAL_PROXY_PORT
        } else {
            savedPort
        }

        if (savedPort != port || !migrationDone) {
            prefs.edit()
                .putInt(KEY_LAST_PORT, port)
                .putBoolean(KEY_LAST_PORT_DEFAULT_MIGRATED, true)
                .apply()
        }

        return ProxySavedConfig(
            port = port,
            runtimeConfig = prefs.getString(KEY_LAST_IPS, "").orEmpty(),
            poolSize = prefs.getInt(KEY_LAST_POOL, 4),
            frontendType = LocalProxyFrontendRepository(prefs).load(),
        )
    }

    fun save(config: ProxySavedConfig) {
        prefs.edit()
            .putInt(KEY_LAST_PORT, config.port)
            .putBoolean(KEY_LAST_PORT_DEFAULT_MIGRATED, true)
            .putString(KEY_LAST_IPS, config.runtimeConfig)
            .putInt(KEY_LAST_POOL, config.poolSize)
            .apply()
        LocalProxyFrontendRepository(prefs).save(config.frontendType)
    }

    companion object {
        private const val KEY_LAST_PORT = "last_proxy_port"
        private const val KEY_LAST_PORT_DEFAULT_MIGRATED = "last_proxy_port_default_1443_migrated"
        private const val KEY_LAST_IPS = "last_runtime_ips"
        private const val KEY_LAST_POOL = "last_proxy_pool"
    }
}
