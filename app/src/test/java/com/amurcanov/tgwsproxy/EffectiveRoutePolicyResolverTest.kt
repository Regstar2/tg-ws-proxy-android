package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveRoutePolicyResolverTest {
    @Test
    fun resolve_usesLegacyConnectionMode_whenNoSavedPolicy() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("connection_mode", "worker_first").apply()
        val resolver = resolver(prefs)

        val effective = resolver.resolve(profile(NetworkProfileType.MOBILE))

        assertEquals(EffectiveRoutePolicySource.LEGACY_CONNECTION_MODE, effective.source)
        assertEquals(RouteKind.WORKER_WS, effective.policy.preferredRoute)
        assertEquals(ConnectionMode.WorkerFirst, effective.legacyMode)
    }

    @Test
    fun resolve_usesSavedWifiPolicy_whenPresent() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("connection_mode", "direct_only").apply()
        val repository = NetworkRoutePolicyRepository(prefs)
        val saved = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
            preferredRoute = RouteKind.CF_PROXY_WS,
            autoStrategy = AutoStrategy.CF_PREFERRED,
            allowFallback = false,
        )
        repository.save(saved)
        val resolver = EffectiveRoutePolicyResolver(repository, prefs)

        val effective = resolver.resolve(profile(NetworkProfileType.WIFI))

        assertEquals(EffectiveRoutePolicySource.SAVED_NETWORK_POLICY, effective.source)
        assertEquals(saved, effective.policy)
        assertEquals(ConnectionMode.CFOnly, effective.legacyMode)
    }

    @Test
    fun resolve_mobileWithoutSavedPolicy_keepsLegacyDirectOnly() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("connection_mode", "direct_only").apply()
        val resolver = resolver(prefs)

        val effective = resolver.resolve(profile(NetworkProfileType.MOBILE))

        assertEquals(EffectiveRoutePolicySource.LEGACY_CONNECTION_MODE, effective.source)
        assertEquals(setOf(RouteKind.DIRECT_WS), effective.policy.enabledRoutes)
        assertEquals(RouteKind.DIRECT_WS, effective.policy.preferredRoute)
        assertEquals(ConnectionMode.DirectOnly, effective.legacyMode)
    }

    @Test
    fun resolve_unknownLegacyFallsBackSafely() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("connection_mode", "broken").apply()
        val resolver = resolver(prefs)

        val effective = resolver.resolve(profile(NetworkProfileType.UNKNOWN))

        assertEquals(EffectiveRoutePolicySource.LEGACY_CONNECTION_MODE, effective.source)
        assertTrue(effective.policy.enabledRoutes.isNotEmpty())
    }

    @Test
    fun resolve_strategyFromPrefs() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("connection_mode", "direct_with_fallback")
            .putString("auto_strategy", "worker_preferred")
            .apply()
        val resolver = resolver(prefs)

        val effective = resolver.resolve(profile(NetworkProfileType.WIFI))

        assertEquals(AutoStrategy.WORKER_PREFERRED, effective.policy.autoStrategy)
    }

    private fun resolver(prefs: InMemorySharedPreferences): EffectiveRoutePolicyResolver {
        return EffectiveRoutePolicyResolver(NetworkRoutePolicyRepository(prefs), prefs)
    }

    private fun profile(type: NetworkProfileType): NetworkProfile {
        return NetworkProfile(
            id = "test-${type.prefValue}",
            type = type,
            label = type.prefValue,
        )
    }
}
