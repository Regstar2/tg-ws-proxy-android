package com.amurcanov.tgwsproxy

import android.content.SharedPreferences
import android.util.Log

object RouteDefaultsMigration {
    private const val TAG = "TgWsProxy"

    private const val KEY_USER_MODIFIED = "route_policy_user_modified_v1"
    private const val KEY_DEFAULTS_VERSION = "route_policy_defaults_version"

    const val DEFAULTS_VERSION_1791 = 1791

    fun markUserModified(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_USER_MODIFIED, true).apply()
    }

    fun applyIfNeeded(
        prefs: SharedPreferences,
        repository: NetworkRoutePolicyRepository,
    ) {
        val currentVersion = prefs.getInt(KEY_DEFAULTS_VERSION, 0)
        if (currentVersion >= DEFAULTS_VERSION_1791) return

        val userModified = prefs.getBoolean(KEY_USER_MODIFIED, false)
        if (userModified) {
            Log.i(TAG, "Skipping route defaults migration: userModifiedRoutePolicy=true")
            prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION_1791).apply()
            return
        }

        Log.i(TAG, "Applying route defaults version=$DEFAULTS_VERSION_1791 userModified=false")
        val wifi = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val mobile = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        val unknown = DefaultNetworkRoutePolicies.forType(NetworkProfileType.UNKNOWN)

        repository.save(wifi)
        repository.save(mobile)
        repository.save(unknown)

        Log.i(TAG, "Default policy WIFI routes=${wifi.enabledRoutes.joinToString("|") { it.prefValue }} preferred=${wifi.preferredRoute?.prefValue} fastFailover=true")
        Log.i(TAG, "Default policy MOBILE routes=${mobile.enabledRoutes.joinToString("|") { it.prefValue }} preferred=${mobile.preferredRoute?.prefValue} fastFailover=true")
        Log.i(TAG, "Default policy UNKNOWN routes=${unknown.enabledRoutes.joinToString("|") { it.prefValue }} preferred=${unknown.preferredRoute?.prefValue} fastFailover=true")

        prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION_1791).apply()
    }

    fun applyRecommendedPreset1791(
        prefs: SharedPreferences,
        repository: NetworkRoutePolicyRepository,
    ) {
        Log.i(TAG, "Applying recommended route preset version=$DEFAULTS_VERSION_1791")
        val wifi = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val mobile = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        val unknown = DefaultNetworkRoutePolicies.forType(NetworkProfileType.UNKNOWN)

        repository.save(wifi)
        repository.save(mobile)
        repository.save(unknown)
        markUserModified(prefs)
        prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION_1791).apply()
    }
}

