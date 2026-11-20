package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentLoggingPrefsStoreTest {
    @Test
    fun debugBuildDefaultsToEnabledDebugLogging() {
        val prefs = PersistentLoggingPrefsStore.load(
            InMemorySharedPreferences(),
            isDebugBuild = true,
        )

        assertTrue(prefs.enabled)
        assertEquals(PersistentLogVerbosity.DEBUG, prefs.verbosity)
    }

    @Test
    fun releaseBuildDefaultsToDisabledImportantLogging() {
        val prefs = PersistentLoggingPrefsStore.load(
            InMemorySharedPreferences(),
            isDebugBuild = false,
        )

        assertFalse(prefs.enabled)
        assertEquals(PersistentLogVerbosity.IMPORTANT, prefs.verbosity)
    }

    @Test
    fun signedSmokeOrReleaseCanExplicitlyEnablePersistentLogs() {
        val storage = InMemorySharedPreferences()
        storage.edit().putBoolean("persistent_logs_enabled", true).apply()

        val prefs = PersistentLoggingPrefsStore.load(storage, isDebugBuild = false)

        assertTrue(prefs.enabled)
        assertEquals(PersistentLogVerbosity.IMPORTANT, prefs.verbosity)
    }

    @Test
    fun savedPreferencesOverrideBuildDefaults() {
        val storage = InMemorySharedPreferences()
        storage.edit()
            .putBoolean("persistent_logs_enabled", false)
            .putString("persistent_logs_level", PersistentLogVerbosity.ERRORS_ONLY.name)
            .putInt("persistent_logs_retention_days", 12)
            .putInt("persistent_logs_max_size_mb", 80)
            .apply()

        val debug = PersistentLoggingPrefsStore.load(storage, isDebugBuild = true)
        val release = PersistentLoggingPrefsStore.load(storage, isDebugBuild = false)

        for (result in listOf(debug, release)) {
            assertFalse(result.enabled)
            assertEquals(PersistentLogVerbosity.ERRORS_ONLY, result.verbosity)
            assertEquals(12, result.retentionDays)
            assertEquals(80L * 1024L * 1024L, result.maxTotalSizeBytes)
        }
    }

    @Test
    fun invalidVerbosityFallsBackToBuildSpecificDefault() {
        val storage = InMemorySharedPreferences()
        storage.edit()
            .putString("persistent_logs_level", "INVALID")
            .apply()

        assertEquals(
            PersistentLogVerbosity.DEBUG,
            PersistentLoggingPrefsStore.load(storage, isDebugBuild = true).verbosity,
        )
        assertEquals(
            PersistentLogVerbosity.IMPORTANT,
            PersistentLoggingPrefsStore.load(storage, isDebugBuild = false).verbosity,
        )
    }
}
