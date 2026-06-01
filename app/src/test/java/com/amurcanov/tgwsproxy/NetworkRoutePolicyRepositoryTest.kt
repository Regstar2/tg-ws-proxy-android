package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRoutePolicyRepositoryTest {
    @Test
    fun load_wifiDefault_whenEmptyPrefs() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())

        val policy = repository.load(NetworkProfileType.WIFI)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), policy)
        assertTrue(RouteKind.DIRECT_WS in policy.enabledRoutes)
        assertEquals(RouteKind.DIRECT_WS, policy.preferredRoute)
        assertTrue(policy.allowFallback)
    }

    @Test
    fun load_mobileDefault_whenEmptyPrefs() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())

        val policy = repository.load(NetworkProfileType.MOBILE)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE), policy)
        assertFalse(RouteKind.DIRECT_WS in policy.enabledRoutes)
        assertTrue(RouteKind.WORKER_WS in policy.enabledRoutes)
        assertEquals(RouteKind.WORKER_WS, policy.preferredRoute)
        assertTrue(policy.allowFallback)
    }

    @Test
    fun saveAndLoad_wifiPolicy() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())
        val expected = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = linkedSetOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS),
            preferredRoute = RouteKind.CF_PROXY_WS,
            autoStrategy = AutoStrategy.CF_PREFERRED,
            allowFallback = false,
        )

        repository.save(expected)
        val actual = repository.load(NetworkProfileType.WIFI)

        assertEquals(expected.enabledRoutes, actual.enabledRoutes)
        assertEquals(expected.preferredRoute, actual.preferredRoute)
        assertEquals(expected.autoStrategy, actual.autoStrategy)
        assertEquals(expected.allowFallback, actual.allowFallback)
    }

    @Test
    fun load_returnsDefaultOnBrokenString() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(KEY_WIFI, "totally broken").apply()
        val repository = NetworkRoutePolicyRepository(prefs)

        val policy = repository.load(NetworkProfileType.WIFI)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), policy)
    }

    @Test
    fun load_ignoresUnknownRoutes() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString(
                KEY_WIFI,
                "routes=direct_ws|mystery|worker_ws;preferred=worker_ws;strategy=balanced;fallback=1",
            )
            .apply()
        val repository = NetworkRoutePolicyRepository(prefs)

        val policy = repository.load(NetworkProfileType.WIFI)

        assertEquals(linkedSetOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS), policy.enabledRoutes)
        assertEquals(RouteKind.WORKER_WS, policy.preferredRoute)
    }

    @Test
    fun load_normalizesInvalidPreferredRoute() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString(
                KEY_WIFI,
                "routes=worker_ws|cf_proxy_ws;preferred=direct_ws;strategy=balanced;fallback=1",
            )
            .apply()
        val repository = NetworkRoutePolicyRepository(prefs)

        val policy = repository.load(NetworkProfileType.WIFI)

        assertEquals(linkedSetOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS), policy.enabledRoutes)
        assertEquals(RouteKind.WORKER_WS, policy.preferredRoute)
    }

    @Test
    fun reset_removesOnlySelectedNetwork() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())
        val wifiPolicy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
            preferredRoute = RouteKind.CF_PROXY_WS,
            autoStrategy = AutoStrategy.CF_PREFERRED,
            allowFallback = false,
        )
        val mobilePolicy = NetworkRoutePolicy(
            networkType = NetworkProfileType.MOBILE,
            enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
            preferredRoute = RouteKind.CF_PROXY_WS,
            autoStrategy = AutoStrategy.STRICT_FAST_FAILOVER,
            allowFallback = false,
        )
        repository.save(wifiPolicy)
        repository.save(mobilePolicy)

        repository.reset(NetworkProfileType.WIFI)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), repository.load(NetworkProfileType.WIFI))
        assertEquals(mobilePolicy, repository.load(NetworkProfileType.MOBILE))
    }

    @Test
    fun resetAll_removesAllPolicies() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())
        NetworkProfileType.entries.forEach { type ->
            repository.save(
                NetworkRoutePolicy(
                    networkType = type,
                    enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
                    preferredRoute = RouteKind.CF_PROXY_WS,
                    autoStrategy = AutoStrategy.CF_PREFERRED,
                    allowFallback = false,
                ),
            )
        }

        repository.resetAll()

        NetworkProfileType.entries.forEach { type ->
            assertEquals(DefaultNetworkRoutePolicies.forType(type), repository.load(type))
        }
    }

    @Test
    fun hasSavedPolicy_falseWhenEmpty() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())

        assertFalse(repository.hasSavedPolicy(NetworkProfileType.WIFI))
        assertFalse(repository.hasSavedPolicy(NetworkProfileType.MOBILE))
        assertFalse(repository.hasSavedPolicy(NetworkProfileType.UNKNOWN))
    }

    @Test
    fun hasSavedPolicy_trueAfterSave() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())

        repository.save(
            NetworkRoutePolicy(
                networkType = NetworkProfileType.MOBILE,
                enabledRoutes = setOf(RouteKind.WORKER_WS),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.WORKER_PREFERRED,
                allowFallback = false,
            ),
        )

        assertTrue(repository.hasSavedPolicy(NetworkProfileType.MOBILE))
        assertFalse(repository.hasSavedPolicy(NetworkProfileType.WIFI))
    }

    @Test
    fun hasSavedPolicy_falseAfterReset() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())
        repository.save(
            NetworkRoutePolicy(
                networkType = NetworkProfileType.UNKNOWN,
                enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
                preferredRoute = RouteKind.CF_PROXY_WS,
                autoStrategy = AutoStrategy.CF_PREFERRED,
                allowFallback = false,
            ),
        )

        repository.reset(NetworkProfileType.UNKNOWN)

        assertFalse(repository.hasSavedPolicy(NetworkProfileType.UNKNOWN))
    }

    @Test
    fun save_marksPolicyAsSaved() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())

        repository.save(
            NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.DIRECT_WS),
                preferredRoute = RouteKind.DIRECT_WS,
                autoStrategy = AutoStrategy.DIRECT_PREFERRED,
                allowFallback = false,
            ),
        )

        assertTrue(repository.hasSavedPolicy(NetworkProfileType.WIFI))
    }

    @Test
    fun reset_returnsDefaultButNotSaved() {
        val repository = NetworkRoutePolicyRepository(InMemorySharedPreferences())
        repository.save(
            NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
                preferredRoute = RouteKind.CF_PROXY_WS,
                autoStrategy = AutoStrategy.CF_PREFERRED,
                allowFallback = false,
            ),
        )

        repository.reset(NetworkProfileType.WIFI)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), repository.load(NetworkProfileType.WIFI))
        assertFalse(repository.hasSavedPolicy(NetworkProfileType.WIFI))
    }

    private companion object {
        const val KEY_WIFI = "route_policy_wifi_v1"
    }
}
