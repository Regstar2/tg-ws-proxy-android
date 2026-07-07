package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeConfigFactoryTest {
    @Test
    fun withRuntimeWorkerFallbackRoutes_expandsWorkerOnlyFallbackPolicy() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.WORKER_WS),
            preferredRoute = RouteKind.WORKER_WS,
            autoStrategy = AutoStrategy.WORKER_PREFERRED,
            allowFallback = true,
        )

        val runtimePolicy = policy.withRuntimeWorkerFallbackRoutes()

        assertEquals(RouteKind.WORKER_WS, runtimePolicy.preferredRoute)
        assertEquals(AutoStrategy.WORKER_PREFERRED, runtimePolicy.autoStrategy)
        assertTrue(RouteKind.WORKER_WS in runtimePolicy.enabledRoutes)
        assertTrue(RouteKind.CF_PROXY_WS in runtimePolicy.enabledRoutes)
        assertTrue(RouteKind.TCP_FALLBACK in runtimePolicy.enabledRoutes)
    }

    @Test
    fun withRuntimeWorkerFallbackRoutes_keepsStrictWorkerOnlyPolicy() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.WORKER_WS),
            preferredRoute = RouteKind.WORKER_WS,
            autoStrategy = AutoStrategy.WORKER_PREFERRED,
            allowFallback = false,
        )

        assertEquals(policy, policy.withRuntimeWorkerFallbackRoutes())
    }
}
