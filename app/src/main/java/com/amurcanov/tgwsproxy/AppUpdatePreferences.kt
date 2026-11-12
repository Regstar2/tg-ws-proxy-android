package com.amurcanov.tgwsproxy

import android.content.Context

internal object AppUpdatePromptPolicy {
    private const val SNOOZE_MS = 24L * 60L * 60L * 1000L

    fun shouldShow(enabled: Boolean, snoozedUntil: Long, now: Long): Boolean =
        enabled && now >= snoozedUntil

    fun nextReminderTime(now: Long): Long = now + SNOOZE_MS
}

internal class AppUpdatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("update_prompts_enabled", true)
        set(value) {
            prefs.edit().putBoolean("update_prompts_enabled", value).apply()
        }

    fun shouldShow(now: Long = System.currentTimeMillis()): Boolean =
        AppUpdatePromptPolicy.shouldShow(
            enabled, prefs.getLong("update_prompt_snoozed_until", 0L), now,
        )

    fun remindLater(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(
            "update_prompt_snoozed_until",
            AppUpdatePromptPolicy.nextReminderTime(now),
        ).apply()
    }
}
