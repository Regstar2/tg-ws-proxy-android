package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreferencesTest {
    @Test
    fun displayMode_fromPref() {
        assertEquals(NotificationDisplayMode.NORMAL, NotificationDisplayMode.fromPref("normal"))
        assertEquals(NotificationDisplayMode.COMPACT, NotificationDisplayMode.fromPref("compact"))
        assertEquals(NotificationDisplayMode.MINIMAL, NotificationDisplayMode.fromPref("minimal"))
        assertEquals(NotificationDisplayMode.NORMAL, NotificationDisplayMode.fromPref(null))
    }

    @Test
    fun defaults_showMetricsAndActions() {
        val prefs = NotificationPreferences()
        assertEquals(NotificationDisplayMode.NORMAL, prefs.displayMode)
        assertTrue(prefs.showMetricsInNotification)
        assertTrue(prefs.notificationActionsEnabled)
    }
}
