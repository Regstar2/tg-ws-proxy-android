package com.amurcanov.tgwsproxy

import android.content.Context
import java.text.DateFormat
import java.util.Date

enum class AutoStrategy(val prefValue: String) {
    BALANCED("balanced"),
    DIRECT_PREFERRED("direct_preferred"),
    WORKER_PREFERRED("worker_preferred"),
    CF_PREFERRED("cf_preferred"),
    STRICT_FAST_FAILOVER("strict_fast_failover"),
    ;

    companion object {
        fun fromPref(raw: String?): AutoStrategy {
            return entries.firstOrNull { it.prefValue == raw } ?: BALANCED
        }
    }
}

object DomainMasking {
    fun mask(hostname: String): String {
        if (hostname.isBlank()) {
            return hostname
        }
        val parts = hostname.split('.')
        if (parts.size < 2) {
            return "*".repeat(hostname.length.coerceAtMost(8))
        }
        val first = parts.first()
        val maskedFirst = if (first.length <= 1) "*" else first.first() + "*".repeat(first.length - 1)
        return (listOf(maskedFirst) + parts.drop(1)).joinToString(".")
    }
}

object AdaptiveDiagnosticsReport {
    fun buildMarkdown(
        context: Context,
        versionName: String,
        connectionMode: ConnectionMode,
        strategy: AutoStrategy,
        profile: NetworkProfile,
        workerDomain: String,
        manualCfDomains: List<String>,
        cachedUpstreamCount: Int,
        builtInCount: Int,
        stats: List<RouteStatSnapshot>,
        maskDomains: Boolean,
    ): String {
        val workerLine = if (workerDomain.isBlank()) {
            "no"
        } else if (maskDomains) {
            "yes (${DomainMasking.mask(workerDomain)})"
        } else {
            "yes ($workerDomain)"
        }
        val cfManual = if (manualCfDomains.isEmpty()) {
            "no"
        } else if (maskDomains) {
            manualCfDomains.joinToString(", ") { DomainMasking.mask(it) }
        } else {
            manualCfDomains.joinToString(", ")
        }
        val networkType = when (profile.type) {
            NetworkProfileType.WIFI -> context.getString(R.string.adaptive_network_wifi)
            NetworkProfileType.MOBILE -> context.getString(R.string.adaptive_network_mobile)
            NetworkProfileType.UNKNOWN -> context.getString(R.string.adaptive_network_unknown)
        }
        val strategyLabel = strategyLabel(context, strategy)
        val statsBlock = if (stats.isEmpty()) {
            context.getString(R.string.adaptive_none)
        } else {
            stats.joinToString("\n") { formatStatLine(context, it) }
        }
        return buildString {
            appendLine("## TgWsProxy Android diagnostics")
            appendLine()
            appendLine("Version: $versionName")
            appendLine("Connection mode: ${connectionMode.prefValue}")
            appendLine("Auto strategy: $strategyLabel")
            appendLine("Network type: $networkType")
            appendLine("Network profile id: ${profile.id}")
            appendLine("Worker configured: $workerLine")
            appendLine("CF manual domains: $cfManual")
            appendLine("CF cached upstream count: $cachedUpstreamCount")
            appendLine("CF built-in count: $builtInCount")
            appendLine()
            appendLine("Route stats:")
            appendLine(statsBlock)
            appendLine()
            appendLine("Notes:")
            appendLine("- Route statistics are stored locally on device.")
            appendLine("- Network profile is a hash; raw SSID is not stored.")
        }
    }

    fun buildAdaptiveLogSection(
        context: Context,
        versionName: String,
        connectionMode: ConnectionMode,
        strategy: AutoStrategy,
        profile: NetworkProfile,
        workerDomain: String,
        cachedUpstreamCount: Int,
        builtInCount: Int,
        stats: List<RouteStatSnapshot>,
        maskDomains: Boolean,
    ): String = buildString {
        appendLine("--- Adaptive Routing Diagnostics ---")
        appendLine(buildMarkdown(context, versionName, connectionMode, strategy, profile, workerDomain, emptyList(), cachedUpstreamCount, builtInCount, stats, maskDomains))
    }

    private fun strategyLabel(context: Context, strategy: AutoStrategy): String {
        return context.getString(
            when (strategy) {
                AutoStrategy.BALANCED -> R.string.adaptive_strategy_balanced
                AutoStrategy.DIRECT_PREFERRED -> R.string.adaptive_strategy_direct
                AutoStrategy.WORKER_PREFERRED -> R.string.adaptive_strategy_worker
                AutoStrategy.CF_PREFERRED -> R.string.adaptive_strategy_cf
                AutoStrategy.STRICT_FAST_FAILOVER -> R.string.adaptive_strategy_fast_failover
            },
        )
    }

    private fun formatStatLine(context: Context, row: RouteStatSnapshot): String {
        val route = routeLabel(context, row.routeType)
        val now = System.currentTimeMillis()
        val status = if (row.cooldownUntilMs > now) {
            context.getString(R.string.adaptive_status_cooldown)
        } else {
            context.getString(R.string.adaptive_status_active)
        }
        val reason = row.lastFailureReason?.let {
            "${context.getString(R.string.adaptive_status_reason)}: $it"
        }.orEmpty()
        val until = if (row.cooldownUntilMs > now) {
            val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.cooldownUntilMs))
            "${context.getString(R.string.adaptive_status_until)}: $t"
        } else {
            ""
        }
        return listOfNotNull(
            "$route: $status",
            "${context.getString(R.string.adaptive_stat_successes)} ${row.successCount}",
            "${context.getString(R.string.adaptive_stat_failures)} ${row.failureCount}",
            reason.takeIf { it.isNotBlank() },
            until.takeIf { it.isNotBlank() },
        ).joinToString("; ")
    }

    private fun routeLabel(context: Context, routeType: String): String {
        return when (routeType) {
            "direct_ws" -> context.getString(R.string.adaptive_route_direct)
            "cf_worker_ws" -> context.getString(R.string.adaptive_route_worker)
            "cf_proxy_ws" -> context.getString(R.string.adaptive_route_cf)
            "tcp_fallback" -> context.getString(R.string.adaptive_route_tcp)
            else -> routeType
        }
    }
}
