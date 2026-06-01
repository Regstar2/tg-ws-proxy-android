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

        assertEquals(RouteKind.WORKER_WS, updated.preferredRoute)
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
