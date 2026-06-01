package com.amurcanov.tgwsproxy

enum class RouteProbeStatus {
    SUCCESS,
    FAILURE,
    DISABLED_BY_POLICY,
    NOT_CONFIGURED,
    SKIPPED,
}

enum class RouteProbeSkipReason {
    NONE,
    DISABLED_BY_POLICY,
    WORKER_DOMAIN_EMPTY,
    CF_DOMAIN_POOL_EMPTY,
    TCP_FALLBACK_DISABLED,
    UNKNOWN_ROUTE,
}

data class RouteLevelProbeResult(
    val route: RouteKind,
    val status: RouteProbeStatus,
    val skipReason: RouteProbeSkipReason,
    val successCount: Int,
    val totalCount: Int,
    val bestLatencyMs: Long?,
    val averageLatencyMs: Long?,
    val failedStages: List<String>,
    val details: List<RouteProbeResult>,
)

data class EffectiveRouteProbeReport(
    val profile: NetworkProfile,
    val policy: NetworkRoutePolicy,
    val policySource: EffectiveRoutePolicySource,
    val legacyMode: ConnectionMode,
    val generatedAtMs: Long,
    val results: List<RouteLevelProbeResult>,
) {
    val hasAnySuccess: Boolean get() = results.any { it.status == RouteProbeStatus.SUCCESS }
    val checkedRoutes: List<RouteLevelProbeResult>
        get() = results.filter { it.totalCount > 0 }
    val disabledRoutes: List<RouteLevelProbeResult>
        get() = results.filter { it.status == RouteProbeStatus.DISABLED_BY_POLICY }
    val failedRoutes: List<RouteLevelProbeResult>
        get() = results.filter { it.status == RouteProbeStatus.FAILURE }
}

object RouteProbeResultMapper {
    fun fromConnectionReport(
        route: RouteKind,
        report: ConnectionProbeReport,
    ): RouteLevelProbeResult {
        val successes = report.results.filter { it.success }
        val failedStages = report.results
            .filterNot { it.success }
            .map { it.stage.ifBlank { "failure" } }
            .distinct()
            .take(5)
        return RouteLevelProbeResult(
            route = route,
            status = if (report.successCount > 0) RouteProbeStatus.SUCCESS else RouteProbeStatus.FAILURE,
            skipReason = RouteProbeSkipReason.NONE,
            successCount = report.successCount,
            totalCount = report.totalCount,
            bestLatencyMs = successes.minOfOrNull { it.elapsedMs },
            averageLatencyMs = successes
                .takeIf { it.isNotEmpty() }
                ?.map { it.elapsedMs }
                ?.average()
                ?.toLong(),
            failedStages = failedStages,
            details = report.results,
        )
    }

    fun disabled(route: RouteKind): RouteLevelProbeResult {
        return RouteLevelProbeResult(
            route = route,
            status = RouteProbeStatus.DISABLED_BY_POLICY,
            skipReason = RouteProbeSkipReason.DISABLED_BY_POLICY,
            successCount = 0,
            totalCount = 0,
            bestLatencyMs = null,
            averageLatencyMs = null,
            failedStages = emptyList(),
            details = emptyList(),
        )
    }

    fun notConfigured(
        route: RouteKind,
        reason: RouteProbeSkipReason,
    ): RouteLevelProbeResult {
        return RouteLevelProbeResult(
            route = route,
            status = RouteProbeStatus.NOT_CONFIGURED,
            skipReason = reason,
            successCount = 0,
            totalCount = 0,
            bestLatencyMs = null,
            averageLatencyMs = null,
            failedStages = emptyList(),
            details = emptyList(),
        )
    }
}
