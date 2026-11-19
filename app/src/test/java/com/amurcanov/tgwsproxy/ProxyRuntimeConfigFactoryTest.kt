package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeConfigFactoryTest {
    @Test
    fun defaultCfNoTlsTokenAndExplicitTlsOptOut() {
        fun tokens(noTls: Boolean) = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = emptyList(),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = false,
            cfProxyOnly = true,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            cfProxyNoTls = noTls,
        )
        assertTrue(ConnectionRuntimeConfig.DEFAULT_CF_PROXY_NO_TLS)
        assertTrue("@cfproxy_no_tls=1" in tokens(ConnectionRuntimeConfig.DEFAULT_CF_PROXY_NO_TLS))
        assertFalse("@cfproxy_no_tls=1" in tokens(false))
    }

    @Test
    fun plainCfUsesDedicatedOptInWithoutChangingRouteOrder() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.CF_PROXY_WS, RouteKind.TCP_FALLBACK),
            preferredRoute = RouteKind.CF_PROXY_WS,
            autoStrategy = AutoStrategy.BALANCED,
            allowFallback = true,
        )
        fun tokens(noTls: Boolean) = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = emptyList(),
            mode = ConnectionMode.CFFirst,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            cfProxyNoTls = noTls,
            routePolicy = policy,
        ).split(',')

        val secure = tokens(false)
        val plain = tokens(true)
        assertFalse("@cfproxy_no_tls=1" in secure)
        assertTrue("@cfproxy_no_tls=1" in plain)
        assertTrue("@route_order=cf_proxy_ws|tcp_fallback" in plain)
        assertTrue(secure == plain.filterNot { it == "@cfproxy_no_tls=1" })
    }

    @Test
    fun runtimePolicy_singleEnabledRouteDisablesFallbackWithoutAddingRoutes() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.WORKER_WS),
            preferredRoute = RouteKind.WORKER_WS,
            autoStrategy = AutoStrategy.WORKER_PREFERRED,
            allowFallback = true,
        )

        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerFirst,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = true,
            workerDomain = "example.username.workers.dev",
            routePolicy = policy,
        )

        assertTrue(raw.contains("@route_worker_ws=1"))
        assertTrue(raw.contains("@route_order=worker_ws"))
        assertTrue(raw.contains("@route_fallback=0"))
        assertTrue(raw.contains("@cfproxy=0"))
        assertTrue(raw.contains("@route_cf_proxy_ws=0"))
        assertTrue(raw.contains("@route_tcp_fallback=0"))
        assertFalse(raw.contains("@route_cf_proxy_ws=1"))
        assertFalse(raw.contains("@route_tcp_fallback=1"))
    }

    @Test
    fun runtimePolicy_strictWorkerOnlyKeepsDisabledRoutesDisabled() {
        val policy = NetworkRoutePolicy(
            networkType = NetworkProfileType.WIFI,
            enabledRoutes = setOf(RouteKind.WORKER_WS),
            preferredRoute = RouteKind.WORKER_WS,
            autoStrategy = AutoStrategy.WORKER_PREFERRED,
            allowFallback = false,
        )

        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = true,
            workerDomain = "example.username.workers.dev",
            routePolicy = policy,
        )

        assertTrue(raw.contains("@route_worker_ws=1"))
        assertTrue(raw.contains("@route_fallback=0"))
        assertTrue(raw.contains("@cfproxy=0"))
        assertTrue(raw.contains("@route_cf_proxy_ws=0"))
        assertTrue(raw.contains("@route_tcp_fallback=0"))
    }
}
