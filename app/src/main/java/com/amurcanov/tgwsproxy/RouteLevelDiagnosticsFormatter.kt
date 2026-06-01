package com.amurcanov.tgwsproxy

import android.content.Context

object RouteLevelDiagnosticsFormatter {
    fun formatShort(context: Context, report: EffectiveRouteProbeReport): String {
        val network = RoutePolicyDisplayNames.networkTypeLabel(context, report.profile.type)
        val summary = report.results.joinToString("; ") { formatResultLine(context, it) }
        return "$network: $summary"
    }

    fun formatResultLine(context: Context, result: RouteLevelProbeResult): String {
        val route = RoutePolicyDisplayNames.routeLabel(context, result.route)
        return when (result.status) {
            RouteProbeStatus.DISABLED_BY_POLICY -> context.getString(
                R.string.route_probe_line_disabled,
                route,
            )
            RouteProbeStatus.NOT_CONFIGURED -> context.getString(
                R.string.route_probe_line_not_configured,
                route,
            )
            RouteProbeStatus.SKIPPED -> context.getString(
                R.string.route_probe_line_skipped,
                route,
            )
            RouteProbeStatus.SUCCESS -> {
                val latency = result.bestLatencyMs?.let {
                    context.getString(R.string.route_probe_line_latency_ms, it)
                }.orEmpty()
                context.getString(
                    R.string.route_probe_line_success,
                    route,
                    result.successCount,
                    result.totalCount,
                    latency,
                )
            }
            RouteProbeStatus.FAILURE -> context.getString(
                R.string.route_probe_line_failure,
                route,
                result.successCount,
                result.totalCount,
            )
        }
    }

    fun formatMarkdown(
        context: Context,
        report: EffectiveRouteProbeReport,
        maskSensitive: Boolean = true,
        poolMetrics: ProxyRuntimeMetrics? = null,
    ): String {
        val enabledRoutes = report.policy.enabledRoutes
            .orderedRoutes()
            .joinToString(", ") { RoutePolicyDisplayNames.routeLabel(context, it) }
        return buildString {
            appendLine("## Effective Route Probe Report")
            appendLine()
            appendLine("- Network type: ${report.profile.type.name}")
            if (!maskSensitive) {
                appendLine("- Network profile id: ${report.profile.id.take(8)}")
            }
            appendLine("- Policy source: ${RoutePolicyDisplayNames.sourceLabel(context, report.policySource)}")
            appendLine("- Legacy mode: ${context.getString(report.legacyMode.displayLabelRes())}")
            appendLine("- Enabled routes: $enabledRoutes")
            appendLine("- Preferred route: ${report.policy.preferredRoute?.let { RoutePolicyDisplayNames.routeLabel(context, it) } ?: context.getString(R.string.common_none)}")
            appendLine()
            appendLine("### Route results")
            appendLine()
            appendLine("| Route | Status | Result | Latency | Stages |")
            appendLine("|---|---|---:|---:|---|")
            report.results.forEach { result ->
                val latency = result.bestLatencyMs?.let { "$it ms" } ?: "-"
                val stages = result.failedStages.take(5).joinToString("|").ifBlank { "-" }
                appendLine(
                    "| ${RoutePolicyDisplayNames.routeLabel(context, result.route)} | " +
                        "${statusLabel(context, result.status)} | " +
                        "${result.successCount}/${result.totalCount} | " +
                        "$latency | $stages |",
                )
            }
            val hints = troubleshootingHints(context, report)
            if (hints.isNotEmpty()) {
                appendLine()
                appendLine("### Troubleshooting hints")
                appendLine()
                hints.forEach { appendLine("- $it") }
            }
            if (poolMetrics != null) {
                appendLine()
                append(PoolMetricsFormatter.formatMarkdown(poolMetrics).trim())
                appendLine()
            }
        }
    }

    fun formatLogLines(report: EffectiveRouteProbeReport): List<String> {
        return report.results.map { result ->
            val best = result.bestLatencyMs?.toString() ?: "-"
            val stages = result.failedStages.take(5).joinToString("|").ifBlank { "-" }
            "route_probe route=${result.route.prefValue} status=${result.status.name} " +
                "ok=${result.successCount} total=${result.totalCount} best_ms=$best stages=$stages"
        }
    }

    fun troubleshootingHints(
        context: Context,
        report: EffectiveRouteProbeReport,
    ): List<String> {
        return RouteLevelDiagnosticsHintRules.hintResIds(report).map { context.getString(it) }
    }

    private fun statusLabel(context: Context, status: RouteProbeStatus): String {
        return context.getString(
            when (status) {
                RouteProbeStatus.SUCCESS -> R.string.route_probe_status_success
                RouteProbeStatus.FAILURE -> R.string.route_probe_status_failure
                RouteProbeStatus.DISABLED_BY_POLICY -> R.string.route_probe_status_disabled
                RouteProbeStatus.NOT_CONFIGURED -> R.string.route_probe_status_not_configured
                RouteProbeStatus.SKIPPED -> R.string.route_probe_status_skipped
            },
        )
    }

}

object RouteLevelDiagnosticsHintRules {
    fun hintResIds(report: EffectiveRouteProbeReport): List<Int> {
        val hints = mutableListOf<Int>()
        val enabled = report.results.filter { it.status != RouteProbeStatus.DISABLED_BY_POLICY }
        if (enabled.isNotEmpty() && enabled.none { it.status == RouteProbeStatus.SUCCESS }) {
            hints += R.string.route_probe_hint_all_enabled_failed
        }
        if (report.results.any { it.route == RouteKind.WORKER_WS && it.skipReason == RouteProbeSkipReason.WORKER_DOMAIN_EMPTY }) {
            hints += R.string.route_probe_hint_worker_not_configured
        }
        if (report.results.any { it.route == RouteKind.CF_PROXY_WS && it.status == RouteProbeStatus.FAILURE }) {
            hints += R.string.route_probe_hint_cf_all_failed
        }
        if (report.results.any { it.route == RouteKind.DIRECT_WS && it.status == RouteProbeStatus.DISABLED_BY_POLICY }) {
            hints += R.string.route_probe_hint_direct_disabled
        }
        if (report.results.any { it.route == RouteKind.TCP_FALLBACK && it.status == RouteProbeStatus.FAILURE }) {
            hints += R.string.route_probe_hint_tcp_failed
        }
        if (report.hasAnySuccess) {
            hints += R.string.route_probe_hint_some_route_works
        }
        return hints.distinct()
    }
}

object RouteLevelDiagnosticsTextFormatter {
    fun formatLogLines(report: EffectiveRouteProbeReport): List<String> {
        return RouteLevelDiagnosticsFormatter.formatLogLines(report)
    }

    fun summarizeStatuses(report: EffectiveRouteProbeReport): String {
        return report.results.joinToString(",") { "${it.route.prefValue}:${it.status.name}" }
    }

    fun safeStageList(stages: List<String>): String {
        return stages.take(5).joinToString("|").ifBlank { "-" }
    }
}

private fun Set<RouteKind>.orderedRoutes(): List<RouteKind> {
    return NetworkRoutePolicyEditor.routeOrder.filter { it in this }
}
