package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxySavedConfigStoreTest {
    @Test
    fun saveAndLoad_roundTripsServiceConfig() {
        val prefs = InMemorySharedPreferences()
        val store = ProxySavedConfigStore(prefs)
        val expected = ProxySavedConfig(
            port = 2443,
            runtimeConfig = "saved-runtime-config",
            poolSize = 6,
            frontendType = LocalProxyFrontendType.SOCKS5,
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    @Test
    fun load_migratesLegacyDefaultPort() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putInt("last_proxy_port", LEGACY_DEFAULT_LOCAL_PROXY_PORT)
            .putString(
                LocalProxyFrontendRepository.KEY_FRONTEND_TYPE,
                LocalProxyFrontendType.MTPROTO_EXPERIMENTAL.prefValue,
            )
            .apply()

        val loaded = ProxySavedConfigStore(prefs).load()

        assertEquals(DEFAULT_LOCAL_PROXY_PORT, loaded.port)
    }
}
