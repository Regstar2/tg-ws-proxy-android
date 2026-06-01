package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLevelDiagnosticsFormatterTest {
    @Test
    fun formatLogLines_containsRouteStatuses() {
        val lines = RouteLevelDiagnosticsTextFormatter.formatLogLines(
            report(
                RouteProbeResultMapper.disabled(RouteKind.DIRECT_WS),
                success(RouteKind.WORKER_WS),
            ),
        )

        assertTrue(lines.any { it.contains("route=worker_ws status=SUCCESS") })
        assertTrue(lines.any { it.contains("route=direct_ws status=DISABLED_BY_POLICY") })
    }

    @Test
    fun troubleshootingHints_allEnabledFailed() {
        val hints = RouteLevelDiagnosticsHintRules.hintResIds(
            report(failure(RouteKind.WORKER_WS), failure(RouteKind.CF_PROXY_WS)),
        )

        assertTrue(R.string.route_probe_hint_all_enabled_failed in hints)
    }

    @Test
    fun troubleshootingHints_workerNotConfigured() {
        val hints = RouteLevelDiagnosticsHintRules.hintResIds(
            report(
                RouteProbeResultMapper.notConfigured(
                    RouteKind.WORKER_WS,
                    RouteProbeSkipReason.WORKER_DOMAIN_EMPTY,
                ),
            ),
        )

        assertTrue(R.string.route_probe_hint_worker_not_configured in hints)
    }

    @Test
    fun troubleshootingHints_someRouteWorks() {
        val hints = RouteLevelDiagnosticsHintRules.hintResIds(report(success(RouteKind.WORKER_WS)))

        assertTrue(R.string.route_probe_hint_some_route_works in hints)
    }

    @Test
    fun formatMarkdown_doesNotContainDomains() {
        val markdown = RouteLevelDiagnosticsTextFormatter.summarizeStatuses(
            report(
                success(RouteKind.WORKER_WS).copy(
                    details = listOf(
                        RouteProbeResult("worker", 1, true, "ws_101", 200, "example.workers.dev"),
                        RouteProbeResult("cf", 2, false, "ws_403", 100, "kws2.example.com"),
                    ),
                ),
            ),
        )

        assertFalse(markdown.contains("example.workers.dev"))
        assertFalse(markdown.contains("kws2.example.com"))
    }

    @Test
    fun formatMarkdown_containsDisabledByPolicy() {
        val summary = RouteLevelDiagnosticsTextFormatter.summarizeStatuses(
            report(RouteProbeResultMapper.disabled(RouteKind.DIRECT_WS)),
        )

        assertTrue(summary.contains("direct_ws:DISABLED_BY_POLICY"))
    }

    private fun report(vararg results: RouteLevelProbeResult): EffectiveRouteProbeReport {
        return EffectiveRouteProbeReport(
            profile = NetworkProfile("profile", NetworkProfileType.MOBILE, "Mobile"),
            policy = NetworkRoutePolicy(
                networkType = NetworkProfileType.MOBILE,
                enabledRoutes = results
                    .filter { it.status != RouteProbeStatus.DISABLED_BY_POLICY }
                    .map { it.route }
                    .toSet()
                    .ifEmpty { setOf(RouteKind.WORKER_WS) },
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            ),
            policySource = EffectiveRoutePolicySource.SAVED_NETWORK_POLICY,
            legacyMode = ConnectionMode.WorkerFirst,
            generatedAtMs = 1L,
            results = results.toList(),
        )
    }

    private fun success(route: RouteKind): RouteLevelProbeResult {
        return RouteProbeResultMapper.fromConnectionReport(
            route,
            ConnectionProbeReport(listOf(RouteProbeResult(route.prefValue, 1, true, "ws_101", 100, "ok"))),
        )
    }

    private fun failure(route: RouteKind): RouteLevelProbeResult {
        return RouteProbeResultMapper.fromConnectionReport(
            route,
            ConnectionProbeReport(listOf(RouteProbeResult(route.prefValue, 1, false, "failure", 100, "fail"))),
        )
    }
}
