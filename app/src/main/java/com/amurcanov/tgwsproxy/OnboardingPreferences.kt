package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

internal class OnboardingPreferences(
    private val prefs: SharedPreferences,
) {
    fun shouldShowOnLaunch(): Boolean = !prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun rememberDismissal(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        }
    }

    companion object {
        // Preserve the key used by versions before v1.11.4.
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
