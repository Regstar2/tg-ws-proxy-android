package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedRoutePolicyIssue135Test {
    @Test fun togglingMovesOnlySelectedRouteAndKeepsOtherPriorities() {
        val original = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val disabled = NetworkRoutePolicyEditor.setRouteEnabled(original, RouteKind.CF_PROXY_WS, false)
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK), disabled.activeRoutes)
        assertEquals(RouteKind.CF_PROXY_WS, disabled.inactiveRoutes.last())
        val restored = NetworkRoutePolicyEditor.setRouteEnabled(disabled, RouteKind.CF_PROXY_WS, true)
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS), restored.activeRoutes)
    }

    @Test fun arrowsAndDragShareOneOrderMutation() {
        val original = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
        val changed = NetworkRoutePolicyEditor.moveEnabledRoute(original, 0, 2)
        assertEquals(listOf(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS), changed.activeRoutes)
        assertEquals(original, NetworkRoutePolicyEditor.moveEnabledRoute(changed, 2, 0))
    }

    @Test fun lastRouteIsStrictOnly() {
        val mobile = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
        val result = NetworkRoutePolicyEditor.setRouteEnabled(mobile, RouteKind.CF_PROXY_WS, false)
        assertEquals(mobile, result)
        assertFalse(result.allowFallback)
    }

    @Test fun storedV2KeepsIndependentNetworksAndSanitizesDuplicates() {
        val prefs = InMemorySharedPreferences()
        val repository = NetworkRoutePolicyRepository(prefs)
        val wifi = NetworkRoutePolicyEditor.moveEnabledRoute(
            DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), 2, 0
        )
        repository.save(wifi)
        prefs.edit().putString(
            "route_policy_mobile_v2",
            "active=awg_warp|awg_warp|worker_ws|unknown;inactive=cf_proxy_ws|direct_ws",
        ).apply()
        assertEquals(wifi.activeRoutes, repository.load(NetworkProfileType.WIFI).activeRoutes)
        assertEquals(listOf(RouteKind.AWG_WARP, RouteKind.WORKER_WS),
            repository.load(NetworkProfileType.MOBILE).activeRoutes)
        assertEquals(RouteKind.CF_PROXY_WS,
            repository.load(NetworkProfileType.MOBILE).inactiveRoutes.first())
        repository.reset(NetworkProfileType.WIFI)
        assertEquals(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI), repository.load(NetworkProfileType.WIFI))
        assertEquals(listOf(RouteKind.AWG_WARP, RouteKind.WORKER_WS),
            repository.load(NetworkProfileType.MOBILE).activeRoutes)
    }

    @Test fun legacyFallbackFalseDoesNotExpandToDisabledRoutes() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("route_policy_wifi_v1",
            "routes=worker_ws|direct_ws|tcp_fallback;preferred=worker_ws;fallback=0;strategy=balanced").apply()
        val policy = NetworkRoutePolicyRepository(prefs).load(NetworkProfileType.WIFI)
        assertEquals(listOf(RouteKind.WORKER_WS), policy.activeRoutes)
        assertFalse(policy.allowFallback)
        assertTrue(RouteKind.DIRECT_WS in policy.inactiveRoutes)
        assertTrue(RouteKind.TCP_FALLBACK in policy.inactiveRoutes)
    }

    @Test fun orderedRuntimeTokenOverridesModeAndDoesNotAddDirect() {
        val only = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE).copy(
            activeRoutes = listOf(RouteKind.AWG_WARP),
            inactiveRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.WORKER_WS, RouteKind.TCP_FALLBACK)
        )
        val tokens = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.51"),
            mode = ConnectionMode.CFFirst,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = true,
            workerDomain = "",
            routePolicy = only
        )
        assertTrue(tokens.contains("@route_order=awg_warp"))
        assertTrue(tokens.contains("@route_direct_ws=0"))
        assertTrue(tokens.contains("@route_tcp_fallback=0"))
    }
}
