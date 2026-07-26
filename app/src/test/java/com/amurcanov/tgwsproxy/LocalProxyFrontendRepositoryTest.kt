package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProxyFrontendRepositoryTest {
    @Test
    fun defaultFrontend_isMtProto() {
        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, LocalProxyFrontendType.DEFAULT)
    }

    @Test
    fun load_missingValueMigratesToMtProto() {
        val prefs = InMemorySharedPreferences()
        val repository = LocalProxyFrontendRepository(prefs)

        val frontendType = repository.load()

        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, frontendType)
        assertEquals(
            LocalProxyFrontendType.MTPROTO_EXPERIMENTAL.prefValue,
            prefs.getString(LocalProxyFrontendRepository.KEY_FRONTEND_TYPE, null),
        )
    }

    @Test
    fun load_unknownValueMigratesToMtProto() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString(LocalProxyFrontendRepository.KEY_FRONTEND_TYPE, "mystery")
            .apply()
        val repository = LocalProxyFrontendRepository(prefs)

        val frontendType = repository.load()

        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, frontendType)
        assertEquals(
            LocalProxyFrontendType.MTPROTO_EXPERIMENTAL.prefValue,
            prefs.getString(LocalProxyFrontendRepository.KEY_FRONTEND_TYPE, null),
        )
    }

    @Test
    fun load_mtprotoExperimentalValueIsRecognized() {
        val prefs = InMemorySharedPreferences()
        val repository = LocalProxyFrontendRepository(prefs)
        repository.save(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL)

        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, repository.load())
    }
}
