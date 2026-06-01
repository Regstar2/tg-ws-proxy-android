package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyDiagnosticsTest {
    @Test
    fun formatLogLine_containsSafeFields() {
        val line = RoutePolicyDiagnosticsFormatter.formatLogLine(snapshot())

        assertTrue(line.contains("network=MOBILE"))
        assertTrue(line.contains("source=SAVED_NETWORK_POLICY"))
        assertTrue(line.contains("preferred=worker_ws"))
    }

    @Test
    fun formatLogLine_doesNotContainDomains() {
        val line = RoutePolicyDiagnosticsFormatter.formatLogLine(snapshot())

        assertFalse(line.contains("example.username.workers.dev"))
        assertFalse(line.contains("pclead.co.uk"))
    }

    @Test
    fun formatMarkdown_containsPolicyFields() {
        val markdown = RoutePolicyDiagnosticsFormatter.formatMarkdown(snapshot())

        assertTrue(markdown.contains("Effective Route Policy"))
        assertTrue(markdown.contains("worker_ws|cf_proxy_ws|tcp_fallback"))
        assertTrue(markdown.contains("Preferred route: worker_ws"))
        assertTrue(markdown.contains("Fallback allowed: true"))
    }

    @Test
    fun formatMarkdown_masksProfileId() {
        val markdown = RoutePolicyDiagnosticsFormatter.formatMarkdown(snapshot())

        assertFalse(markdown.contains("mobile-secret-profile-id"))
    }

    private fun snapshot(): RoutePolicyDiagnosticsSnapshot {
        return RoutePolicyDiagnosticsSnapshot(
            profile = NetworkProfile(
                id = "mobile-secret-profile-id",
                type = NetworkProfileType.MOBILE,
                label = "Mobile",
            ),
            source = EffectiveRoutePolicySource.SAVED_NETWORK_POLICY,
            legacyMode = ConnectionMode.WorkerFirst,
            policy = NetworkRoutePolicy(
                networkType = NetworkProfileType.MOBILE,
                enabledRoutes = setOf(
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            ),
            hasSavedPolicyForType = true,
            generatedAtMs = 123L,
        )
    }
}
