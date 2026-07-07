package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowsealDcPresetTest {
    @Test
    fun dcEntries_flowsealOnlyUsesSingleDc4Entry() {
        val entries = FlowsealDcPreset.dcEntries(
            DcIpTexts("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"),
            flowsealOnly = true,
        )
        assertEquals(listOf("4:149.154.167.220"), entries)
    }

    @Test
    fun enableAndDisable_roundTrip() {
        val prefs = InMemorySharedPreferences()
        val before = DcIpTexts("10.0.0.1", "10.0.0.2", "10.0.0.4", "10.0.0.203")
        val applied = FlowsealDcPreset.enable(prefs, before)
        assertEquals(FlowsealDcPreset.flowsealTexts(), applied)
        assertTrue(prefs.getBoolean(FlowsealDcPreset.PREF_ENABLED, false))

        val restored = FlowsealDcPreset.disable(prefs)
        assertEquals(before, restored)
    }

    @Test
    fun disable_withoutBackupUsesStandardDefaults() {
        val prefs = InMemorySharedPreferences()
        val restored = FlowsealDcPreset.disable(prefs)
        assertEquals(FlowsealDcPreset.standardTexts(), restored)
    }
}

private class InMemorySharedPreferences : android.content.SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor(data)
    override fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class Editor(private val data: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending[key!!] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            pending.forEach { (key, value) ->
                if (value == null) data.remove(key) else data[key] = value
            }
            pending.clear()
            clear = false
        }
    }
}
