package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

enum class PersistentLogVerbosity {
    ERRORS_ONLY,
    IMPORTANT,
    VERBOSE,
    DEBUG,
}

data class PersistentLoggingPrefs(
    val enabled: Boolean,
    val verbosity: PersistentLogVerbosity,
    val retentionDays: Int,
    val maxTotalSizeBytes: Long,
)

object PersistentLoggingPrefsStore {
    private const val KEY_ENABLED = "persistent_logs_enabled"
    private const val KEY_VERBOSITY = "persistent_logs_level"
    private const val KEY_RETENTION_DAYS = "persistent_logs_retention_days"
    private const val KEY_MAX_SIZE_MB = "persistent_logs_max_size_mb"

    fun load(prefs: SharedPreferences): PersistentLoggingPrefs {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val verbosityName = prefs.getString(KEY_VERBOSITY, PersistentLogVerbosity.IMPORTANT.name)
        val verbosity = runCatching { PersistentLogVerbosity.valueOf(verbosityName ?: "") }
            .getOrDefault(PersistentLogVerbosity.IMPORTANT)
        val retentionDays = prefs.getInt(KEY_RETENTION_DAYS, 7).coerceAtLeast(1)
        val maxMb = prefs.getInt(KEY_MAX_SIZE_MB, 50).coerceAtLeast(10)
        val maxBytes = maxMb.toLong() * 1024L * 1024L
        return PersistentLoggingPrefs(
            enabled = enabled,
            verbosity = verbosity,
            retentionDays = retentionDays,
            maxTotalSizeBytes = maxBytes,
        )
    }

    fun saveEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun saveVerbosity(prefs: SharedPreferences, v: PersistentLogVerbosity) {
        prefs.edit().putString(KEY_VERBOSITY, v.name).apply()
    }

    fun saveRetentionDays(prefs: SharedPreferences, days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    fun saveMaxSizeMb(prefs: SharedPreferences, mb: Int) {
        prefs.edit().putInt(KEY_MAX_SIZE_MB, mb).apply()
    }
}

