package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPreferencesTest {
    @Test
    fun showsOnboardingWhenNoDismissalPreferenceExists() {
        val prefs = InMemorySharedPreferences()

        assertTrue(OnboardingPreferences(prefs).shouldShowOnLaunch())
    }

    @Test
    fun dismissalWithoutOptOutStillAllowsAutomaticOnboarding() {
        val prefs = InMemorySharedPreferences()
        OnboardingPreferences(prefs).rememberDismissal(dontShowAgain = false)

        assertTrue(OnboardingPreferences(prefs).shouldShowOnLaunch())
        assertFalse(prefs.contains("onboarding_completed"))
    }

    @Test
    fun optedOutDismissalRemainsHiddenAfterReload() {
        val prefs = InMemorySharedPreferences()
        OnboardingPreferences(prefs).rememberDismissal(dontShowAgain = true)

        assertFalse(OnboardingPreferences(prefs).shouldShowOnLaunch())
        assertTrue(prefs.getBoolean("onboarding_completed", false))
    }

    @Test
    fun existingOptOutFromPreviousVersionsIsHonored() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putBoolean("onboarding_completed", true).commit()

        assertFalse(OnboardingPreferences(prefs).shouldShowOnLaunch())
    }

    @Test
    fun laterDismissalWithoutOptOutCannotRestoreAutomaticDisplay() {
        val prefs = InMemorySharedPreferences()
        val onboarding = OnboardingPreferences(prefs)
        onboarding.rememberDismissal(dontShowAgain = true)
        onboarding.rememberDismissal(dontShowAgain = false)

        assertFalse(OnboardingPreferences(prefs).shouldShowOnLaunch())
    }
}
