package com.amurcanov.tgwsproxy

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRoutingTest {
    @Test
    fun domainMasking_masksFirstLabel() {
        val masked = DomainMasking.mask("nameless-haze-b533.carkov195.workers.dev")
        assertTrue(masked.startsWith("n"))
        assertTrue(masked.contains("carkov195.workers.dev"))
        org.junit.Assert.assertFalse(masked.contains("nameless-haze-b533"))
    }

    @Test
    fun autoStrategy_fromPref_defaultsBalanced() {
        assertEquals(AutoStrategy.BALANCED, AutoStrategy.fromPref(null))
        assertEquals(AutoStrategy.CF_PREFERRED, AutoStrategy.fromPref("cf_preferred"))
        assertEquals(AutoStrategy.STRICT_FAST_FAILOVER, AutoStrategy.fromPref("strict_fast_failover"))
    }

    @Test
    fun mirrorValidation_networkProfileTypes() {
        assertEquals("wifi", NetworkProfileType.WIFI.prefValue)
        assertEquals("mobile", NetworkProfileType.MOBILE.prefValue)
    }

    @Test
    fun routeStatsRepository_persistsEncodedBlob() {
        val prefs = InMemorySharedPreferences()
        val repo = AdaptiveRouteStatsRepository(prefs)
        repo.saveEncodedStats("direct_ws:2:0:1:2:0:0:err:0:100:0:0:0:net1")
        val loaded = repo.loadEncodedStats()
        assertTrue(loaded.contains("direct_ws"))
        val snapshot = repo.snapshotForDisplay("net1")
        assertEquals(1, snapshot.size)
        assertEquals(1, snapshot.first().successCount)
        assertEquals(2, snapshot.first().failureCount)
    }

    @Test
    fun routeStatsRepository_limitsProfileCount() {
        val prefs = InMemorySharedPreferences()
        val repo = AdaptiveRouteStatsRepository(prefs)
        val entries = (1..25).joinToString(";") { i ->
            "direct_ws:2:0:1:0:0:0::0:0:0:0:0:profile_$i"
        }
        repo.saveEncodedStats(entries)
        val loaded = repo.loadEncodedStats()
        val profileIds = loaded.split(';')
            .mapNotNull { entry ->
                entry.split(':').getOrNull(13)?.takeIf { it.startsWith("profile_") }
            }
            .distinct()
        assertTrue(profileIds.size <= 20)
    }

    @Test
    fun routeStatsRepository_resetCurrentNetwork() {
        val prefs = InMemorySharedPreferences()
        val repo = AdaptiveRouteStatsRepository(prefs)
        repo.saveEncodedStats(
            "direct_ws:2:0:1:0:0:0::0:0:0:0:0:net_a;" +
                "direct_ws:2:0:2:0:0:0::0:0:0:0:0:net_b",
        )
        repo.resetCurrentNetwork("net_a")
        assertTrue(repo.snapshotForDisplay("net_a").isEmpty())
        assertEquals(1, repo.snapshotForDisplay("net_b").size)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { pending[key!!] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) {
                    values.clear()
                }
                pending.forEach { (k, v) ->
                    if (v == null) {
                        values.remove(k)
                    } else {
                        values[k] = v
                    }
                }
            }
        }
    }
}
