package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

/**
 * One-time migration to a single ordered source of truth before route UI state is created.
 * Existing network-specific v1/v2 settings take precedence over legacy global mode.
 */
internal object OrderedRoutePolicyMigration {
    private const val KEY_MIGRATED = "route_policy_ordered_migrated_v2"

    fun applyIfNeeded(prefs: SharedPreferences, repository: NetworkRoutePolicyRepository) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val hasLegacyMode = prefs.contains("connection_mode") ||
            prefs.contains("cfproxy_enabled") ||
            prefs.contains("cfproxy_priority") ||
            prefs.contains("cfproxy_only")
        val legacyMode = prefs.getString("connection_mode", null)?.let(ConnectionMode::fromPref)
            ?: ConnectionMode.fromLegacy(
                prefs.getBoolean("cfproxy_enabled", true),
                prefs.getBoolean("cfproxy_priority", true),
                prefs.getBoolean("cfproxy_only", false),
            )
        val strategy = AutoStrategy.fromPref(prefs.getString("auto_strategy", null))

        NetworkProfileType.entries.forEach { type ->
            val policy = when {
                repository.hasSavedPolicy(type) -> repository.load(type)
                hasLegacyMode && type != NetworkProfileType.UNKNOWN ->
                    NetworkRoutePolicyMapper.fromConnectionMode(type, legacyMode, strategy)
                else -> DefaultNetworkRoutePolicies.forType(type)
            }
            repository.save(NetworkRoutePolicyEditor.normalize(policy))
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}
