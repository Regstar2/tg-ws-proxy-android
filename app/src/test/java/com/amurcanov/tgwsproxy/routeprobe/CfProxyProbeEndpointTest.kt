package com.amurcanov.tgwsproxy.routeprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class CfProxyProbeEndpointTest {
    @Test
    fun cfProxyDc2_usesBaseDomainForTlsAndDcHostForWebSocket() {
        val endpoint = cfProxyProbeEndpoint("pool.example", 2)

        assertEquals("kws2.pool.example", endpoint.upstreamHost)
        assertEquals("pool.example", endpoint.tlsServerName)
        assertEquals("/apiws", endpoint.path)
    }

    @Test
    fun cfProxyDc4_keepsDomainRoutingForWebSocketAndTcp() {
        val endpoint = cfProxyProbeEndpoint("zone.pool.example", 4)

        assertEquals("kws4.zone.pool.example", endpoint.upstreamHost)
        assertEquals("zone.pool.example", endpoint.tlsServerName)
        assertEquals("/apiws", endpoint.path)
    }
}
