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

    @Test
    fun moveEnabledRouteChangesExactFallbackPriority() {
        val initial = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val moved = NetworkRoutePolicyEditor.moveEnabledRoute(initial, fromIndex = 2, toIndex = 0)
        assertEquals(
            listOf(RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS),
            moved.activeRoutes,
        )
        assertEquals(RouteKind.TCP_FALLBACK, moved.preferredRoute)
        assertTrue(moved.allowFallback)
    }

    @Test
    fun disablingThenEnablingMovesRouteToEnd() {
        val initial = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val disabled = NetworkRoutePolicyEditor.setRouteEnabled(initial, RouteKind.CF_PROXY_WS, false)
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK), disabled.activeRoutes)
        assertTrue(RouteKind.CF_PROXY_WS in disabled.inactiveRoutes)
        val restored = NetworkRoutePolicyEditor.setRouteEnabled(disabled, RouteKind.CF_PROXY_WS, true)
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS), restored.activeRoutes)
    }

    @Test
    fun lastRouteCannotBeDisabledOrMovedOutsideList() {
        val initial = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        assertEquals(initial, NetworkRoutePolicyEditor.setRouteEnabled(initial, RouteKind.CF_PROXY_WS, false))
        assertEquals(initial, NetworkRoutePolicyEditor.moveEnabledRoute(initial, 0, 1))
    }

    @Test
    fun legacyNoFallbackMigratesToStrictOnly() {
        val policy = policy(
            routes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
            preferred = RouteKind.WORKER_WS,
        ).copy(allowFallback = false)
        val migrated = NetworkRoutePolicyEditor.normalize(policy)
        assertEquals(listOf(RouteKind.WORKER_WS), migrated.activeRoutes)
        assertFalse(migrated.allowFallback)
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
