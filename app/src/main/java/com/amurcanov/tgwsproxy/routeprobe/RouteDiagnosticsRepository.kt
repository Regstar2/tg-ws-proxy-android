package com.amurcanov.tgwsproxy.routeprobe

import android.content.Context

class RouteDiagnosticsRepository(
    private val runner: RouteProbeRunner = RouteProbeRunner(),
) {
    private val lastResults = mutableListOf<RouteProbeResult>()

    fun getLastProbeResults(): List<RouteProbeResult> = lastResults.toList()

    fun clearProbeResults() {
        lastResults.clear()
    }

    suspend fun runProbe(
        context: Context,
        target: RouteProbeTarget,
        request: RouteProbeRequest,
    ): RouteProbeResult {
        val result = runner.run(context, target, request)
        lastResults.removeAll { it.target == target }
        lastResults.add(result)
        return result
    }

    suspend fun runProbes(
        context: Context,
        targets: List<RouteProbeTarget>,
        request: RouteProbeRequest,
    ): RouteProbeSummary {
        val results = targets.map { target ->
            runner.run(context, target, request)
        }
        lastResults.clear()
        lastResults.addAll(results)
        return RouteProbeSummary(results = results, finishedAtMs = System.currentTimeMillis())
    }

    companion object {
        val DEFAULT_ROUTE_TARGETS: List<RouteProbeTarget> = listOf(
            RouteProbeTarget.CURRENT_NETWORK,
            RouteProbeTarget.DIRECT_WEBSOCKET,
            RouteProbeTarget.WORKER_WEBSOCKET,
            RouteProbeTarget.CLOUDFLARE_PROXY,
        )

        val DIAGNOSTICS_SCREEN_TARGETS: List<RouteProbeTarget> = listOf(
            RouteProbeTarget.CURRENT_NETWORK,
            RouteProbeTarget.DIRECT_WEBSOCKET,
            RouteProbeTarget.WORKER_WEBSOCKET,
            RouteProbeTarget.CLOUDFLARE_PROXY,
            RouteProbeTarget.TELEGRAM_REACHABILITY,
            RouteProbeTarget.IPV4_CONNECTIVITY,
            RouteProbeTarget.IPV6_CONNECTIVITY,
        )
    }
}
