package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences

data class RoutePolicyDiagnosticsSnapshot(
    val profile: NetworkProfile,
    val source: EffectiveRoutePolicySource,
    val legacyMode: ConnectionMode,
    val policy: NetworkRoutePolicy,
    val hasSavedPolicyForType: Boolean,
    val generatedAtMs: Long,
)

object RoutePolicyDiagnostics {
    fun buildSnapshot(
        context: Context,
        prefs: SharedPreferences,
        repository: NetworkRoutePolicyRepository,
    ): RoutePolicyDiagnosticsSnapshot {
        val profile = NetworkProfileProvider.current(context)
        val effective = EffectiveRoutePolicyResolver(repository, prefs).resolve(profile)
        return RoutePolicyDiagnosticsSnapshot(
            profile = profile,
            source = effective.source,
            legacyMode = effective.legacyMode,
            policy = effective.policy,
            hasSavedPolicyForType = repository.hasSavedPolicy(profile.type),
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    fun formatShort(context: Context, snapshot: RoutePolicyDiagnosticsSnapshot): String {
        val network = RoutePolicyDisplayNames.networkTypeLabel(context, snapshot.profile.type)
        val source = RoutePolicyDisplayNames.sourceLabel(context, snapshot.source)
        val preferred = snapshot.policy.preferredRoute
            ?.let { RoutePolicyDisplayNames.routeLabel(context, it) }
            ?: context.getString(R.string.common_none)
        return "$network · $source · $preferred"
    }

    fun formatMarkdown(
        context: Context,
        snapshot: RoutePolicyDiagnosticsSnapshot,
        maskSensitive: Boolean = true,
    ): String {
        val yes = context.getString(R.string.common_yes)
        val no = context.getString(R.string.common_no)
        val routes = snapshot.policy.enabledRoutes
            .orderedRoutes()
            .joinToString(", ") { RoutePolicyDisplayNames.routeLabel(context, it) }
        val preferred = snapshot.policy.preferredRoute
            ?.let { RoutePolicyDisplayNames.routeLabel(context, it) }
            ?: context.getString(R.string.common_none)
        return buildString {
            appendLine("## Effective Route Policy")
            appendLine()
            appendLine("- Network type: ${snapshot.profile.type.name}")
            if (!maskSensitive) {
                appendLine("- Network profile id: ${snapshot.profile.id.take(8)}")
            }
            appendLine("- Policy source: ${RoutePolicyDisplayNames.sourceLabel(context, snapshot.source)}")
            appendLine("- Legacy mode compatibility: ${context.getString(snapshot.legacyMode.displayLabelRes())}")
            appendLine("- Enabled routes: $routes")
            appendLine("- Preferred route: $preferred")
            appendLine("- Auto strategy: ${RoutePolicyDisplayNames.strategyLabel(context, snapshot.policy.autoStrategy)}")
            appendLine("- Fallback allowed: ${if (snapshot.policy.allowFallback) yes else no}")
            appendLine("- Saved policy for this network type: ${if (snapshot.hasSavedPolicyForType) yes else no}")
        }
    }

    fun formatLogLine(snapshot: RoutePolicyDiagnosticsSnapshot): String {
        return RoutePolicyDiagnosticsFormatter.formatLogLine(snapshot)
    }
}

object RoutePolicyDiagnosticsFormatter {
    fun formatLogLine(snapshot: RoutePolicyDiagnosticsSnapshot): String {
        val policy = snapshot.policy
        return "route_policy network=${snapshot.profile.type.name} " +
            "source=${snapshot.source.name} " +
            "legacy=${snapshot.legacyMode.name} " +
            "routes=${routeValues(policy)} " +
            "preferred=${policy.preferredRoute?.prefValue.orEmpty()} " +
            "fallback=${policy.allowFallback} " +
            "strategy=${policy.autoStrategy.prefValue}"
    }

    fun formatMarkdown(snapshot: RoutePolicyDiagnosticsSnapshot): String {
        return buildString {
            appendLine("## Effective Route Policy")
            appendLine()
            appendLine("- Network type: ${snapshot.profile.type.name}")
            appendLine("- Policy source: ${snapshot.source.name}")
            appendLine("- Legacy mode compatibility: ${snapshot.legacyMode.name}")
            appendLine("- Enabled routes: ${routeValues(snapshot.policy)}")
            appendLine("- Preferred route: ${snapshot.policy.preferredRoute?.prefValue.orEmpty()}")
            appendLine("- Auto strategy: ${snapshot.policy.autoStrategy.prefValue}")
            appendLine("- Fallback allowed: ${snapshot.policy.allowFallback}")
            appendLine("- Saved policy for this network type: ${snapshot.hasSavedPolicyForType}")
        }
    }

    fun routeValues(policy: NetworkRoutePolicy): String {
        return policy.enabledRoutes.orderedRoutes().joinToString("|") { it.prefValue }
    }
}

object RoutePolicyDisplayNames {
    fun routeLabel(context: Context, route: RouteKind): String {
        return context.getString(
            when (route) {
                RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
                RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
                RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
                RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
            },
        )
    }

    fun routePrefValue(route: RouteKind): String = route.prefValue

    fun strategyLabel(context: Context, strategy: AutoStrategy): String {
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

    fun sourceLabel(context: Context, source: EffectiveRoutePolicySource): String {
        return context.getString(
            when (source) {
                EffectiveRoutePolicySource.SAVED_NETWORK_POLICY -> R.string.route_policy_source_saved_network_policy
                EffectiveRoutePolicySource.LEGACY_CONNECTION_MODE -> R.string.route_policy_source_legacy_connection_mode
                EffectiveRoutePolicySource.DEFAULT_POLICY -> R.string.route_policy_source_default_policy
            },
        )
    }

    fun networkTypeLabel(context: Context, type: NetworkProfileType): String {
        return context.getString(
            when (type) {
                NetworkProfileType.WIFI -> R.string.route_policy_wifi_title
                NetworkProfileType.MOBILE -> R.string.route_policy_mobile_title
                NetworkProfileType.UNKNOWN -> R.string.route_policy_unknown_title
            },
        )
    }
}

private fun Set<RouteKind>.orderedRoutes(): List<RouteKind> {
    return NetworkRoutePolicyEditor.routeOrder.filter { it in this }
}
