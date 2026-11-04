package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDefaultsMigrationIssue135Test {
    @Test
    fun oldSavedProfileIsNeverReplacedByAutomaticPresetUpgrade() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("route_policy_wifi_v1", "routes=worker_ws|direct_ws;preferred=worker_ws;strategy=balanced;fallback=0")
            .apply()
        val repository = NetworkRoutePolicyRepository(prefs)

        RouteDefaultsMigration.applyIfNeeded(prefs, repository)

        val wifi = repository.load(NetworkProfileType.WIFI)
        assertEquals(listOf(RouteKind.WORKER_WS), wifi.activeRoutes)
        assertFalse(wifi.allowFallback)
        assertTrue(repository.hasSavedPolicy(NetworkProfileType.WIFI))
        assertFalse(repository.hasSavedPolicy(NetworkProfileType.MOBILE))
        assertEquals(RouteDefaultsMigration.DEFAULTS_VERSION_1800, prefs.getInt("route_policy_defaults_version", 0))
    }

    @Test
    fun freshInstallStillInitializesRecommendedDefaults() {
        val prefs = InMemorySharedPreferences()
        val repository = NetworkRoutePolicyRepository(prefs)

        RouteDefaultsMigration.applyIfNeeded(prefs, repository)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), repository.load(NetworkProfileType.WIFI))
        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE), repository.load(NetworkProfileType.MOBILE))
        assertTrue(repository.hasSavedPolicy(NetworkProfileType.WIFI))
        assertTrue(repository.hasSavedPolicy(NetworkProfileType.MOBILE))
    }
}
