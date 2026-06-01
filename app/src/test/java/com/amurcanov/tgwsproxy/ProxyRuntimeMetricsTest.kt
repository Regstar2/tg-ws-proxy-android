package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeMetricsTest {
    @Test
    fun parseStatus_mapsFields() {
        val raw = "running=1;mode=auto;route=cf_worker_ws;active=4;bytes_up=100;bytes_down=200;latency_ms=210;last_error=;" +
            "worker_pool_hits=3;worker_pool_misses=5;worker_pool_idle=2;worker_pool_refill_errors=1;" +
            "cf_pool_hits=4;cf_pool_misses=2;cf_pool_idle=1;cf_pool_refill_errors=0"
        val metrics = ProxyRuntimeMetrics.parseStatus(raw)!!
        assertTrue(metrics.running)
        assertEquals("auto", metrics.mode)
        assertEquals("cf_worker_ws", metrics.route)
        assertEquals(4, metrics.activeConnections)
        assertEquals(100, metrics.bytesUp)
        assertEquals(200, metrics.bytesDown)
        assertEquals(210, metrics.lastLatencyMs)
        assertEquals(3, metrics.workerPoolHits)
        assertEquals(5, metrics.workerPoolMisses)
        assertEquals(2, metrics.workerPoolIdle)
        assertEquals(1, metrics.workerPoolErrors)
        assertEquals(4, metrics.cfPoolHits)
        assertEquals(2, metrics.cfPoolMisses)
        assertEquals(1, metrics.cfPoolIdle)
        assertEquals(0, metrics.cfPoolErrors)
    }

    @Test
    fun parseStatus_defaultsPoolMetricsToZero() {
        val metrics = ProxyRuntimeMetrics.parseStatus("running=1;mode=auto")!!
        assertEquals(0, metrics.workerPoolHits)
        assertEquals(0, metrics.workerPoolMisses)
        assertEquals(0, metrics.workerPoolIdle)
        assertEquals(0, metrics.workerPoolErrors)
        assertEquals(0, metrics.cfPoolHits)
        assertEquals(0, metrics.cfPoolMisses)
        assertEquals(0, metrics.cfPoolIdle)
        assertEquals(0, metrics.cfPoolErrors)
    }

    @Test
    fun parseStatus_ignoresBrokenPoolMetricNumbers() {
        val raw = "running=1;worker_pool_hits=x;worker_pool_misses=bad;cf_pool_hits=;cf_pool_refill_errors=nope"
        val metrics = ProxyRuntimeMetrics.parseStatus(raw)!!
        assertEquals(0, metrics.workerPoolHits)
        assertEquals(0, metrics.workerPoolMisses)
        assertEquals(0, metrics.cfPoolHits)
        assertEquals(0, metrics.cfPoolErrors)
    }

    @Test
    fun parseStatus_blankReturnsNull() {
        assertEquals(null, ProxyRuntimeMetrics.parseStatus(""))
        assertEquals(null, ProxyRuntimeMetrics.parseStatus(null))
    }

    @Test
    fun routeLabelRes_knownRoutes() {
        assertEquals(R.string.route_display_worker, RouteDisplayNames.routeLabelRes("cf_worker_ws"))
        assertEquals(R.string.route_display_cf_proxy, RouteDisplayNames.routeLabelRes("cf_proxy_ws"))
        assertEquals(R.string.route_display_direct_ws, RouteDisplayNames.routeLabelRes("direct_ws"))
    }

    @Test
    fun speedSampler_computesDelta() {
        val sampler = SpeedSampler(windowMs = 1000)
        sampler.sample(0, 0, nowMs = 0)
        val (down, up) = sampler.sample(bytesUp = 1000, bytesDown = 2000, nowMs = 1000)
        assertTrue(down > 0)
        assertTrue(up > 0)
    }

    @Test
    fun speedSampler_resetClearsState() {
        val sampler = SpeedSampler()
        sampler.sample(100, 200, 1000)
        sampler.reset()
        val (down, up) = sampler.sample(100, 200, 2000)
        assertEquals(0.0, down, 0.001)
        assertEquals(0.0, up, 0.001)
    }
}
