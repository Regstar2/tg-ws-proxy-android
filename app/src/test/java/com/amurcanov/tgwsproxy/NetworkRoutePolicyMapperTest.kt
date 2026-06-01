package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRoutePolicyMapperTest {
    @Test
    fun fromConnectionMode_directOnly() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.WIFI, ConnectionMode.DirectOnly)

        assertEquals(setOf(RouteKind.DIRECT_WS), policy.enabledRoutes)
        assertEquals(RouteKind.DIRECT_WS, policy.preferredRoute)
        assertFalse(policy.allowFallback)
    }

    @Test
    fun fromConnectionMode_workerOnly() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.WIFI, ConnectionMode.WorkerOnly)

        assertEquals(setOf(RouteKind.WORKER_WS), policy.enabledRoutes)
        assertEquals(RouteKind.WORKER_WS, policy.preferredRoute)
        assertFalse(policy.allowFallback)
    }

    @Test
    fun fromConnectionMode_cfOnly() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.WIFI, ConnectionMode.CFOnly)

        assertEquals(setOf(RouteKind.CF_PROXY_WS), policy.enabledRoutes)
        assertEquals(RouteKind.CF_PROXY_WS, policy.preferredRoute)
        assertFalse(policy.allowFallback)
    }

    @Test
    fun fromConnectionMode_workerFirst() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.WIFI, ConnectionMode.WorkerFirst)

        assertEquals(RouteKind.WORKER_WS, policy.preferredRoute)
        assertTrue(policy.allowFallback)
        assertTrue(RouteKind.WORKER_WS in policy.enabledRoutes)
        assertTrue(RouteKind.CF_PROXY_WS in policy.enabledRoutes)
        assertTrue(RouteKind.DIRECT_WS in policy.enabledRoutes)
        assertTrue(RouteKind.TCP_FALLBACK in policy.enabledRoutes)
    }

    @Test
    fun fromConnectionMode_cfFirst() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.WIFI, ConnectionMode.CFFirst)

        assertEquals(RouteKind.CF_PROXY_WS, policy.preferredRoute)
        assertTrue(policy.allowFallback)
        assertTrue(RouteKind.CF_PROXY_WS in policy.enabledRoutes)
        assertTrue(RouteKind.WORKER_WS in policy.enabledRoutes)
        assertTrue(RouteKind.DIRECT_WS in policy.enabledRoutes)
        assertTrue(RouteKind.TCP_FALLBACK in policy.enabledRoutes)
    }

    @Test
    fun fromConnectionMode_autoMobileUsesMobileDefault() {
        val policy = NetworkRoutePolicyMapper.fromConnectionMode(NetworkProfileType.MOBILE, ConnectionMode.Auto)

        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE), policy)
        assertFalse(RouteKind.DIRECT_WS in policy.enabledRoutes)
        assertTrue(RouteKind.WORKER_WS in policy.enabledRoutes)
    }

    @Test
    fun toLegacyConnectionMode_strictDirect() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.DIRECT_WS),
            preferredRoute = RouteKind.DIRECT_WS,
            autoStrategy = AutoStrategy.BALANCED,
            allowFallback = false,
        )

        assertEquals(ConnectionMode.DirectOnly, NetworkRoutePolicyMapper.toLegacyConnectionMode(policy))
    }

    @Test
    fun toLegacyConnectionMode_workerPreferred() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
            preferredRoute = RouteKind.WORKER_WS,
            autoStrategy = AutoStrategy.BALANCED,
            allowFallback = true,
        )

        assertEquals(ConnectionMode.WorkerFirst, NetworkRoutePolicyMapper.toLegacyConnectionMode(policy))
    }

    @Test
    fun toLegacyConnectionMode_unknownCustomFallsBackToAuto() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.WORKER_WS, RouteKind.CF_PROXY_WS),
            preferredRoute = null,
            autoStrategy = AutoStrategy.BALANCED,
            allowFallback = false,
        )

        assertEquals(ConnectionMode.Auto, NetworkRoutePolicyMapper.toLegacyConnectionMode(policy))
    }
}
