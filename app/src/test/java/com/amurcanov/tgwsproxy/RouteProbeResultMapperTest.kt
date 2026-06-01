package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RouteProbeResultMapperTest {
    @Test
    fun fromConnectionReport_successWhenAnySuccess() {
        val result = RouteProbeResultMapper.fromConnectionReport(
            RouteKind.WORKER_WS,
            ConnectionProbeReport(
                listOf(
                    RouteProbeResult("worker", 1, true, "ws_101", 200, "ok"),
                    RouteProbeResult("worker", 2, false, "ws_403", 300, "fail"),
                ),
            ),
        )

        assertEquals(RouteProbeStatus.SUCCESS, result.status)
        assertEquals(1, result.successCount)
        assertEquals(2, result.totalCount)
        assertNotNull(result.bestLatencyMs)
    }

    @Test
    fun fromConnectionReport_failureWhenNoSuccess() {
        val result = RouteProbeResultMapper.fromConnectionReport(
            RouteKind.CF_PROXY_WS,
            ConnectionProbeReport(
                listOf(
                    RouteProbeResult("cf", 1, false, "ws_403", 100, "fail"),
                    RouteProbeResult("cf", 2, false, "ws_403", 120, "fail"),
                    RouteProbeResult("cf", 3, false, "ws_429", 130, "fail"),
                ),
            ),
        )

        assertEquals(RouteProbeStatus.FAILURE, result.status)
        assertEquals(listOf("ws_403", "ws_429"), result.failedStages)
    }

    @Test
    fun disabled_hasZeroCounts() {
        val result = RouteProbeResultMapper.disabled(RouteKind.DIRECT_WS)

        assertEquals(RouteProbeStatus.DISABLED_BY_POLICY, result.status)
        assertEquals(0, result.successCount)
        assertEquals(0, result.totalCount)
    }

    @Test
    fun notConfigured_worker() {
        val result = RouteProbeResultMapper.notConfigured(
            RouteKind.WORKER_WS,
            RouteProbeSkipReason.WORKER_DOMAIN_EMPTY,
        )

        assertEquals(RouteProbeStatus.NOT_CONFIGURED, result.status)
        assertEquals(RouteProbeSkipReason.WORKER_DOMAIN_EMPTY, result.skipReason)
    }
}
