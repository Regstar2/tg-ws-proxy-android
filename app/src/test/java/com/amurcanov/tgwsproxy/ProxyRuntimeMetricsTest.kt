package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeMetricsTest {
    @Test
    fun parseStatus_mapsFields() {
        val raw = "running=1;mode=auto;route=cf_worker_ws;active=4;bytes_up=100;bytes_down=200;latency_ms=210;last_error="
        val metrics = ProxyRuntimeMetrics.parseStatus(raw)!!
        assertTrue(metrics.running)
        assertEquals("auto", metrics.mode)
        assertEquals("cf_worker_ws", metrics.route)
        assertEquals(4, metrics.activeConnections)
        assertEquals(100, metrics.bytesUp)
        assertEquals(200, metrics.bytesDown)
        assertEquals(210, metrics.lastLatencyMs)
    }

    @Test
    fun parseStatus_blankReturnsNull() {
        assertEquals(null, ProxyRuntimeMetrics.parseStatus(""))
        assertEquals(null, ProxyRuntimeMetrics.parseStatus(null))
    }

    @Test
    fun routeDisplayLabel_knownRoutes() {
        assertEquals("Worker", ProxyRuntimeMetrics.routeDisplayLabel("cf_worker_ws"))
        assertEquals("CF proxy", ProxyRuntimeMetrics.routeDisplayLabel("cf_proxy_ws"))
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
