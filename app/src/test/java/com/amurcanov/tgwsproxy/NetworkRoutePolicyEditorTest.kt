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

    @Test
    fun disabledRouteNeverMovesAndReturnsToItsOriginalSlot() {
        val initial = NetworkRoutePolicyEditor.normalize(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
                activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP, RouteKind.WORKER_WS),
                displayOrder = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
                    RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            )
        )
        val disabled = NetworkRoutePolicyEditor.setRouteEnabled(initial, RouteKind.AWG_WARP, false)
        assertEquals(initial.displayOrder, disabled.displayOrder)
        assertEquals(listOf(RouteKind.CF_PROXY_WS, RouteKind.WORKER_WS), disabled.activeRoutes)

        val reordered = NetworkRoutePolicyEditor.moveEnabledRoute(disabled, 0, 1)
        assertEquals(listOf(RouteKind.WORKER_WS, RouteKind.AWG_WARP,
            RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            reordered.displayOrder)
        val enabled = NetworkRoutePolicyEditor.setRouteEnabled(reordered, RouteKind.AWG_WARP, true)
        assertEquals(listOf(RouteKind.WORKER_WS, RouteKind.AWG_WARP, RouteKind.CF_PROXY_WS),
            enabled.activeRoutes)
    }

    @Test
    fun moveRouteMovesActiveBetweenInactiveWithoutChangingItsEnabledState() {
        val original = NetworkRoutePolicyEditor.normalize(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
                activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
                displayOrder = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
                    RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            )
        )
        val moved = NetworkRoutePolicyEditor.moveRoute(original, 0, 1)
        assertEquals(
            listOf(RouteKind.AWG_WARP, RouteKind.CF_PROXY_WS,
                RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            moved.displayOrder
        )
        assertEquals(original.activeRoutes, moved.activeRoutes)
        assertEquals(original.enabledRoutes, moved.enabledRoutes)
        assertEquals(original.preferredRoute, moved.preferredRoute)
        assertEquals(original.allowFallback, moved.allowFallback)

        val enabled = NetworkRoutePolicyEditor.setRouteEnabled(moved, RouteKind.AWG_WARP, true)
        assertEquals(
            listOf(RouteKind.AWG_WARP, RouteKind.CF_PROXY_WS,
                RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            enabled.activeRoutes
        )
    }

    @Test
    fun moveRouteAcrossEnabledRoutesChangesFallbackPriorityNotEnabledMembership() {
        val original = NetworkRoutePolicyEditor.normalize(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
                activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
                displayOrder = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
                    RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            )
        )
        val moved = NetworkRoutePolicyEditor.moveRoute(original, 0, 4)
        assertEquals(
            listOf(RouteKind.AWG_WARP, RouteKind.WORKER_WS, RouteKind.DIRECT_WS,
                RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS),
            moved.displayOrder
        )
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS),
            moved.activeRoutes)
        assertEquals(original.enabledRoutes, moved.enabledRoutes)
        assertEquals(RouteKind.DIRECT_WS, moved.preferredRoute)
        assertTrue(moved.allowFallback)
    }

    @Test
    fun moveRouteHandlesDisabledAndActiveWithSameIndexRules() {
        val original = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        val movedDisabled = NetworkRoutePolicyEditor.moveRoute(original, 1, 0)
        assertEquals(
            listOf(RouteKind.AWG_WARP, RouteKind.CF_PROXY_WS),
            movedDisabled.displayOrder.take(2)
        )
        assertEquals(original.activeRoutes, movedDisabled.activeRoutes)
        assertEquals(original, NetworkRoutePolicyEditor.moveRoute(original, -1, 3))
        assertEquals(original, NetworkRoutePolicyEditor.moveRoute(original, 1, 5))
        assertEquals(original, NetworkRoutePolicyEditor.moveRoute(original, 2, 2))
        assertEquals(original.enabledRoutes, movedDisabled.enabledRoutes)
    }

    @Test
    fun movingInactiveRouteAcrossEnabledRowsKeepsActiveFallbackUnchanged() {
        val initial = NetworkRoutePolicyEditor.normalize(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
                activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
                displayOrder = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
                    RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
            )
        )
        val moved = NetworkRoutePolicyEditor.moveInactiveRoute(initial, RouteKind.AWG_WARP, 4)

        assertEquals(
            listOf(RouteKind.CF_PROXY_WS, RouteKind.WORKER_WS, RouteKind.DIRECT_WS,
                RouteKind.TCP_FALLBACK, RouteKind.AWG_WARP),
            moved.displayOrder,
        )
        assertEquals(initial.activeRoutes, moved.activeRoutes)
        assertEquals(initial.enabledRoutes, moved.enabledRoutes)
        assertEquals(initial.preferredRoute, moved.preferredRoute)
        assertEquals(initial.allowFallback, moved.allowFallback)
        assertEquals(listOf(RouteKind.WORKER_WS, RouteKind.AWG_WARP), moved.inactiveRoutes)
        assertEquals(initial, NetworkRoutePolicyEditor.moveInactiveRoute(initial, RouteKind.AWG_WARP, 1))
    }

    @Test
    fun movingInactiveRouteToFirstSlotControlsItsFutureEnabledPriority() {
        val original = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val moved = NetworkRoutePolicyEditor.moveInactiveRoute(original, RouteKind.WORKER_WS, 0)
        val restored = NetworkRoutePolicyEditor.setRouteEnabled(moved, RouteKind.WORKER_WS, true)

        assertEquals(RouteKind.WORKER_WS, restored.displayOrder.first())
        assertEquals(listOf(RouteKind.CF_PROXY_WS), moved.activeRoutes)
        assertEquals(setOf(RouteKind.CF_PROXY_WS), moved.enabledRoutes)
        assertEquals(listOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS), restored.activeRoutes)
    }

    @Test
    fun movingInactiveRouteCannotMoveEnabledItemOrInvalidIndex() {
        val original = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        assertEquals(original, NetworkRoutePolicyEditor.moveInactiveRoute(
            original, RouteKind.CF_PROXY_WS, 4
        ))
        assertEquals(original, NetworkRoutePolicyEditor.moveInactiveRoute(
            original, RouteKind.AWG_WARP, -1
        ))
        assertEquals(original, NetworkRoutePolicyEditor.moveInactiveRoute(
            original, RouteKind.AWG_WARP, 5
        ))
        assertEquals(RouteKind.entries.size,
            NetworkRoutePolicyEditor.moveInactiveRoute(original, RouteKind.AWG_WARP, 0)
                .displayOrder.distinct().size
        )
    }

    @Test
    fun resettingBothProfilesEnablesOnlyCloudflareProxy() {
        for (type in listOf(NetworkProfileType.WIFI, NetworkProfileType.MOBILE)) {
            val policy = DefaultNetworkRoutePolicies.forType(type)
            assertEquals(listOf(RouteKind.CF_PROXY_WS), policy.activeRoutes)
            assertFalse(policy.allowFallback)
        }
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
