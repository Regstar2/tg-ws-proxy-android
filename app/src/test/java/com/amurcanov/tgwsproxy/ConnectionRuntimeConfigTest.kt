package com.amurcanov.tgwsproxy

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionRuntimeConfigTest {
    @Test
    fun buildRuntimeTokens_workerFirst() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerFirst,
            cfProxyEnabled = true,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "example.test",
            workerEnabled = true,
            workerDomain = "example.username.workers.dev",
        )
        assertTrue(raw.contains("@connection_mode=worker_first"))
        assertTrue(raw.contains("@worker_domain=example.username.workers.dev"))
        assertTrue(raw.contains("@worker_enabled=1"))
    }

    @Test
    fun buildRuntimeTokens_workerOnlyWithoutDomainStillEnablesFlag() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerOnly,
            cfProxyEnabled = false,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
        )
        assertTrue(raw.contains("@connection_mode=worker_only"))
        assertTrue(raw.contains("@worker_enabled=1"))
    }

    @Test
    fun buildRuntimeTokens_normalizesManualCfDomain() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "https://virkgj.com/apiws",
            workerEnabled = false,
            workerDomain = "",
        )
        assertTrue(raw.contains("@cfproxy_domain=virkgj.com"))
    }

    @Test
    fun buildRuntimeTokens_rejectsInvalidManualCfDomain() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "domain with spaces.com",
            workerEnabled = false,
            workerDomain = "",
        )
        assertFalse(raw.contains("@cfproxy_domain="))
    }

    @Test
    fun buildRuntimeTokens_includesCachedCfDomains() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            cachedCfDomains = listOf("cached-a.example", "https://cached-b.example/apiws"),
        )

        assertTrue(raw.contains("@cf_cached_domains=cached-a.example|cached-b.example"))
    }

    @Test
    fun buildRuntimeTokens_includesMultipleManualCfDomains() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "",
            manualCfDomains = listOf("manual-a.example", "https://manual-b.example/apiws"),
            workerEnabled = false,
            workerDomain = "",
        )

        assertTrue(raw.contains("@cf_manual_domains=manual-a.example|manual-b.example"))
        assertTrue(raw.contains("@cfproxy_domain=manual-a.example"))
    }

    @Test
    fun buildRuntimeTokens_includesRoutePolicyTokens() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.DirectWithFallback,
            cfProxyEnabled = true,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            routePolicy = NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            ),
        )

        assertTrue(raw.contains("@route_direct_ws=1"))
        assertTrue(raw.contains("@route_worker_ws=1"))
        assertTrue(raw.contains("@route_cf_proxy_ws=0"))
        assertTrue(raw.contains("@route_tcp_fallback=0"))
        assertTrue(raw.contains("@preferred_route=worker_ws"))
        assertTrue(raw.contains("@route_fallback=1"))
    }

    @Test
    fun buildRuntimeTokens_routePolicyControlsCfFlag() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFFirst,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            routePolicy = NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.DIRECT_WS, RouteKind.WORKER_WS),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            ),
        )

        assertTrue(raw.contains("@cfproxy=0"))
    }

    @Test
    fun buildRuntimeTokens_routePolicyControlsWorkerFlag() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.DirectOnly,
            cfProxyEnabled = false,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            routePolicy = NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.WORKER_WS),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.WORKER_PREFERRED,
                allowFallback = false,
            ),
        )

        assertTrue(raw.contains("@worker_enabled=1"))
    }

    @Test
    fun buildRuntimeTokens_keepsLegacyBehaviorWithoutRoutePolicy() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.DirectOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "example.username.workers.dev",
        )

        assertTrue(raw.contains("@connection_mode=direct_only"))
        assertTrue(raw.contains("@cfproxy=0"))
        assertTrue(raw.contains("@worker_enabled=1"))
        assertFalse(raw.contains("@route_direct_ws="))
    }

    @Test
    fun buildRuntimeTokens_usesLegacyModeFromPolicy() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.DirectWithFallback,
            cfProxyEnabled = false,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            routePolicy = NetworkRoutePolicy(
                networkType = NetworkProfileType.WIFI,
                enabledRoutes = setOf(RouteKind.CF_PROXY_WS),
                preferredRoute = RouteKind.CF_PROXY_WS,
                autoStrategy = AutoStrategy.CF_PREFERRED,
                allowFallback = false,
            ),
        )

        assertTrue(raw.contains("@connection_mode=cf_only"))
    }

    @Test
    fun effectiveWsHostDc_mapsDc203ToKws2() {
        org.junit.Assert.assertEquals(2, ConnectionRuntimeConfig.effectiveWsHostDc(203))
        org.junit.Assert.assertEquals(5, ConnectionRuntimeConfig.effectiveWsHostDc(5))
    }
}
