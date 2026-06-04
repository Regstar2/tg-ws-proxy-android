package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteRuntimeStateTest {
    @Test
    fun fromStatusMap_parsesRouteTruthFields() {
        val map = mapOf(
            "mode" to "worker_first",
            "configured_mode" to "worker_first",
            "selected_route" to "cf_worker_ws",
            "active_route_kind" to "cf_proxy_ws",
            "last_success_route" to "cf_proxy_ws",
            "last_failed_route" to "cf_worker_ws",
            "fallback_reason" to "worker_failed",
            "network_type" to "mobile",
            "route_state_updated_at" to "12345",
        )
        val state = RouteRuntimeState.fromStatusMap(map)
        assertEquals("worker_first", state.configuredMode)
        assertEquals("cf_worker_ws", state.selectedRoute)
        assertEquals("cf_proxy_ws", state.activeRoute)
        assertEquals("cf_proxy_ws", state.lastSuccessfulRoute)
        assertEquals("cf_worker_ws", state.lastFailedRoute)
        assertEquals("worker_failed", state.fallbackReason)
        assertEquals("mobile", state.networkType)
        assertEquals(12345L, state.lastUpdatedAtMs)
    }

    @Test
    fun parseStatus_includesRouteRuntime() {
        val raw = "running=1;mode=worker_first;route=cf_proxy_ws;active_route_kind=cf_proxy_ws;" +
            "configured_mode=worker_first;selected_route=cf_worker_ws;last_success_route=cf_proxy_ws;" +
            "last_failed_route=cf_worker_ws;fallback_reason=worker_failed;network_type=wifi"
        val metrics = ProxyRuntimeMetrics.parseStatus(raw)!!
        assertEquals("cf_proxy_ws", metrics.routeRuntime.activeRoute)
        assertEquals("cf_worker_ws", metrics.routeRuntime.selectedRoute)
        assertEquals("worker_failed", metrics.routeRuntime.fallbackReason)
    }
}
