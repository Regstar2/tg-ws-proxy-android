package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconfigureStatusStoreTest {
    @Test
    fun load_returnsNoneWhenEmpty() {
        val status = ReconfigureStatusStore.load(InMemorySharedPreferences())

        assertEquals(ReconfigureStatusType.NONE, status.type)
        assertNull(status.networkType)
        assertEquals("", status.message)
        assertEquals(0L, status.updatedAtMs)
    }

    @Test
    fun saveAndLoad_success() {
        val prefs = InMemorySharedPreferences()
        val expected = ReconfigureStatus(
            type = ReconfigureStatusType.SUCCESS,
            networkType = NetworkProfileType.MOBILE,
            message = "action_reconfigure_sent",
            updatedAtMs = 42L,
        )

        ReconfigureStatusStore.save(prefs, expected)

        assertEquals(expected, ReconfigureStatusStore.load(prefs))
    }

    @Test
    fun load_handlesBrokenType() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("last_reconfigure_type_v1", "broken").apply()

        val status = ReconfigureStatusStore.load(prefs)

        assertEquals(ReconfigureStatusType.NONE, status.type)
    }

    @Test
    fun clear_removesStatus() {
        val prefs = InMemorySharedPreferences()
        ReconfigureStatusStore.save(
            prefs,
            ReconfigureStatus(ReconfigureStatusType.SKIPPED, NetworkProfileType.WIFI, "throttled", 10L),
        )

        ReconfigureStatusStore.clear(prefs)

        assertEquals(ReconfigureStatusType.NONE, ReconfigureStatusStore.load(prefs).type)
    }

    @Test
    fun save_doesNotRequireNetworkTypeForFailed() {
        val prefs = InMemorySharedPreferences()
        val expected = ReconfigureStatus(
            type = ReconfigureStatusType.FAILED,
            networkType = null,
            message = "config_build_failed",
            updatedAtMs = 7L,
        )

        ReconfigureStatusStore.save(prefs, expected)

        assertEquals(expected, ReconfigureStatusStore.load(prefs))
    }
}
