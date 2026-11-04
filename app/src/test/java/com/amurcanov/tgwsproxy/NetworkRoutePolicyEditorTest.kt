package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRoutePolicyEditorTest {
    @Test
    fun setRouteEnabled_disablesRoute() {
        val policy = policy(
            routes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
            preferred = RouteKind.WORKER_WS,
        )

        val updated = NetworkRoutePolicyEditor.setRouteEnabled(policy, RouteKind.DIRECT_WS, false)

        assertFalse(RouteKind.DIRECT_WS in updated.enabledRoutes)
        assertTrue(RouteKind.WORKER_WS in updated.enabledRoutes)
    }

    @Test
    fun setRouteEnabled_doesNotDisableLastRoute() {
        val policy = policy(
            routes = setOf(RouteKind.WORKER_WS),
            preferred = RouteKind.WORKER_WS,
        )

        val updated = NetworkRoutePolicyEditor.setRouteEnabled(policy, RouteKind.WORKER_WS, false)

        assertEquals(setOf(RouteKind.WORKER_WS), updated.enabledRoutes)
        assertEquals(RouteKind.WORKER_WS, updated.preferredRoute)
    }

    @Test
    fun setRouteEnabled_movesPreferredWhenPreferredDisabled() {
        val policy = policy(
            routes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
            preferred = RouteKind.DIRECT_WS,
        )

        val updated = NetworkRoutePolicyEditor.setRouteEnabled(policy, RouteKind.DIRECT_WS, false)

        assertEquals(RouteKind.WORKER_WS, updated.preferredRoute)
    }

    @Test
    fun setPreferredRoute_ignoresDisabledRoute() {
        val policy = policy(
            routes = setOf(RouteKind.WORKER_WS),
            preferred = RouteKind.WORKER_WS,
        )

        val updated = NetworkRoutePolicyEditor.setPreferredRoute(policy, RouteKind.DIRECT_WS)

        assertEquals(RouteKind.WORKER_WS, updated.preferredRoute)
    }

    @Test
    fun normalize_returnsDefaultWhenRoutesEmpty() {
        val policy = policy(routes = emptySet(), preferred = null)

        val updated = NetworkRoutePolicyEditor.normalize(policy)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), updated)
    }

    @Test
    fun normalize_fixesInvalidPreferred() {
        val policy = policy(
            routes = setOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS),
            preferred = RouteKind.DIRECT_WS,
        )

        val updated = NetworkRoutePolicyEditor.normalize(policy)

        // Invalid legacy preferred routes are normalized to the documented deterministic order.
        assertEquals(RouteKind.CF_PROXY_WS, updated.preferredRoute)
    }

    @Test
    fun configuredRouteGuard_blocksLastReachableRouteFromBeingDisabled() {
        val initial = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val onlyCf = initial.copy(
            activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP),
            inactiveRoutes = listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.WORKER_WS),
        )
        val candidate = NetworkRoutePolicyEditor.setRouteEnabled(
            onlyCf, RouteKind.CF_PROXY_WS, false
        )
        assertFalse(NetworkRoutePolicyEditor.hasConfiguredOutboundRoute(
            candidate, workerConfigured = false, awgConfigured = false
        ))
        assertTrue(NetworkRoutePolicyEditor.hasConfiguredOutboundRoute(
            candidate, workerConfigured = false, awgConfigured = true
        ))
    }

    @Test
    fun configuredRouteGuard_allowsExplicitCloudflareFallbackWithoutAwgConfig() {
        val mixed = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
            activeRoutes = listOf(RouteKind.AWG_WARP, RouteKind.CF_PROXY_WS),
        )
        assertTrue(NetworkRoutePolicyEditor.hasConfiguredOutboundRoute(
            mixed, workerConfigured = false, awgConfigured = false
        ))
    }

    private fun policy(
        routes: Set<RouteKind>,
        preferred: RouteKind?,
    ): NetworkRoutePolicy {
        return NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = routes,
            preferredRoute = preferred,
            autoStrategy = AutoStrategy.BALANCED,
            allowFallback = true,
        )
    }
}
