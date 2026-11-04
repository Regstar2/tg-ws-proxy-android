package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedRoutePolicyMigrationTest {
    @Test fun legacyWorkerOnlyRemainsStrictOnBothNetworks() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("connection_mode", "worker_only").apply()
        val repository = NetworkRoutePolicyRepository(prefs)
        RouteDefaultsMigration.applyIfNeeded(prefs, repository)
        OrderedRoutePolicyMigration.applyIfNeeded(prefs, repository)
        for (network in listOf(NetworkProfileType.WIFI, NetworkProfileType.MOBILE)) {
            val policy = repository.load(network)
            assertEquals(listOf(RouteKind.WORKER_WS), policy.activeRoutes)
            assertFalse(policy.allowFallback)
            assertTrue(repository.hasSavedPolicy(network))
        }
        assertEquals(listOf(RouteKind.CF_PROXY_WS),
            repository.load(NetworkProfileType.UNKNOWN).activeRoutes)
    }

    @Test fun existingWifiV1WinsOverGlobalMode() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("connection_mode", "direct_only")
            .putString("route_policy_wifi_v1",
                "routes=cf_proxy_ws|worker_ws;preferred=worker_ws;fallback=1;strategy=balanced")
            .apply()
        val repo = NetworkRoutePolicyRepository(prefs)
        OrderedRoutePolicyMigration.applyIfNeeded(prefs, repo)
        assertEquals(listOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS),
            repo.load(NetworkProfileType.WIFI).activeRoutes)
        assertEquals(listOf(RouteKind.DIRECT_WS),
            repo.load(NetworkProfileType.MOBILE).activeRoutes)
        assertTrue(prefs.contains("route_policy_wifi_v2"))
    }

    @Test fun alreadyMigratedPoliciesAreNotOverwritten() {
        val prefs = InMemorySharedPreferences()
        val repo = NetworkRoutePolicyRepository(prefs)
        OrderedRoutePolicyMigration.applyIfNeeded(prefs, repo)
        val custom = NetworkRoutePolicyEditor.moveEnabledRoute(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), 2, 0)
        repo.save(custom)
        OrderedRoutePolicyMigration.applyIfNeeded(prefs, repo)
        assertEquals(custom.activeRoutes, repo.load(NetworkProfileType.WIFI).activeRoutes)
    }
}
