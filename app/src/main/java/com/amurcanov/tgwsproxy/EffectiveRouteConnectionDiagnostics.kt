package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences

object EffectiveRouteConnectionDiagnostics {
    suspend fun probeEffectivePolicy(
        context: Context,
        prefs: SharedPreferences,
        routePolicyRepository: NetworkRoutePolicyRepository,
        workerDomain: String,
        manualCfDomains: List<String>,
        cachedUpstreamDomains: List<String>,
    ): EffectiveRouteProbeReport {
        val profile = NetworkProfileProvider.current(context)
        val effective = EffectiveRoutePolicyResolver(routePolicyRepository, prefs).resolve(profile)
        val policy = effective.policy
        val results = NetworkRoutePolicyEditor.routeOrder.map { route ->
            if (route !in policy.enabledRoutes) {
                RouteProbeResultMapper.disabled(route)
            } else {
                probeRoute(route, workerDomain, manualCfDomains, cachedUpstreamDomains)
            }
        }
        return EffectiveRouteProbeReport(
            profile = profile,
            policy = policy,
            policySource = effective.source,
            legacyMode = effective.legacyMode,
            generatedAtMs = System.currentTimeMillis(),
            results = results,
        )
    }

    private suspend fun probeRoute(
        route: RouteKind,
        workerDomain: String,
        manualCfDomains: List<String>,
        cachedUpstreamDomains: List<String>,
    ): RouteLevelProbeResult {
        return runCatching {
            when (route) {
                RouteKind.DIRECT_WS -> RouteProbeResultMapper.fromConnectionReport(
                    route,
                    ConnectionDiagnostics.probeDirectWs(),
                )
                RouteKind.WORKER_WS -> {
                    val normalized = WorkerDomain.normalize(workerDomain)
                    if (normalized.isBlank()) {
                        RouteProbeResultMapper.notConfigured(route, RouteProbeSkipReason.WORKER_DOMAIN_EMPTY)
                    } else {
                        RouteProbeResultMapper.fromConnectionReport(route, ConnectionDiagnostics.probeWorker(normalized))
                    }
                }
                RouteKind.CF_PROXY_WS -> {
                    val hasDomain = CfManualDomainList.normalize(manualCfDomains).isNotEmpty() ||
                        cachedUpstreamDomains.mapNotNull(CfDomain::normalizeOrNull).isNotEmpty() ||
                        CfDomain.builtInDomains.isNotEmpty()
                    if (!hasDomain) {
                        RouteProbeResultMapper.notConfigured(route, RouteProbeSkipReason.CF_DOMAIN_POOL_EMPTY)
                    } else {
                        RouteProbeResultMapper.fromConnectionReport(
                            route,
                            ConnectionDiagnostics.probeCfProxy(manualCfDomains, cachedUpstreamDomains),
                        )
                    }
                }
                RouteKind.TCP_FALLBACK -> RouteProbeResultMapper.fromConnectionReport(
                    route,
                    ConnectionDiagnostics.probeTcpFallback(),
                )
            }
        }.getOrElse {
            RouteLevelProbeResult(
                route = route,
                status = RouteProbeStatus.FAILURE,
                skipReason = RouteProbeSkipReason.NONE,
                successCount = 0,
                totalCount = 1,
                bestLatencyMs = null,
                averageLatencyMs = null,
                failedStages = listOf("exception"),
                details = emptyList(),
            )
        }
    }
}
