package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the route-policy invariant relied upon by the issue #127 debug fixture. */
class Issue127DirectOnlyPolicyTest {
    @Test
    fun wifiDirectOnlySelectionNeverIncludesOtherOutboundBackends() {
        val policy = NetworkRoutePolicyEditor.normalize(
            NetworkRoutePolicyMapper.fromConnectionMode(
                NetworkProfileType.WIFI,
                ConnectionMode.DirectOnly,
                AutoStrategy.CF_PREFERRED,
            ),
        )

        assertEquals(listOf(RouteKind.DIRECT_WS), policy.activeRoutes)
        assertEquals(setOf(RouteKind.DIRECT_WS), policy.enabledRoutes)
        assertEquals(RouteKind.DIRECT_WS, policy.preferredRoute)
        assertFalse(policy.allowFallback)
        assertTrue(RouteKind.CF_PROXY_WS in policy.inactiveRoutes)
        assertTrue(RouteKind.WORKER_WS in policy.inactiveRoutes)
        assertTrue(RouteKind.AWG_WARP in policy.inactiveRoutes)
        assertTrue(RouteKind.TCP_FALLBACK in policy.inactiveRoutes)
    } 
    @Test
    fun diagnosticPortNeverReusesTelegramPort() {
        val candidates = listOf(1443, 9000, 80, 54732).iterator()
        val port = Issue127DiagnosticPort.select(setOf(1443, 9000)) { candidates.next() }
        assertEquals(54732, port)
    }

    @Test
    fun diagnosticPortRejectsExhaustedAllocator() {
        try {
            Issue127DiagnosticPort.select(setOf(1443)) { 1443 }
            throw AssertionError("Expected exhausted port allocation to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("distinct"))
        }
    }

}
