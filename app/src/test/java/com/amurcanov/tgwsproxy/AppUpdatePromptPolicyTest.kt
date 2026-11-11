package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePromptPolicyTest {
    @Test
    fun disabledRemindersNeverDisplayLaunchPrompt() {
        assertFalse(AppUpdatePromptPolicy.shouldShow(false, 0L, 100L))
    }

    @Test
    fun laterPostponesFor24Hours() {
        val now = 50_000L
        val later = AppUpdatePromptPolicy.nextReminderTime(now)
        assertFalse(AppUpdatePromptPolicy.shouldShow(true, later, now))
        assertTrue(AppUpdatePromptPolicy.shouldShow(true, later, later))
    }
}
