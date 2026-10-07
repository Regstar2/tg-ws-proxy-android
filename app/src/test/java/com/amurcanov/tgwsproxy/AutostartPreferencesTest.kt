package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartPreferencesTest {
    @Test
    fun default_isDisabled() {
        val store = AutostartPreferences(InMemorySharedPreferences())

        assertFalse(store.isEnabled())
    }

    @Test
    fun setEnabled_persistsValue() {
        val prefs = InMemorySharedPreferences()
        val store = AutostartPreferences(prefs)

        store.setEnabled(true)
        assertTrue(store.isEnabled())

        store.setEnabled(false)
        assertFalse(store.isEnabled())
    }
}
