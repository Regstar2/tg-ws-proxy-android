package com.amurcanov.tgwsproxy

enum class RouteKind(val prefValue: String) {
    DIRECT_WS("direct_ws"),
    WORKER_WS("worker_ws"),
    CF_PROXY_WS("cf_proxy_ws"),
    AWG_WARP("awg_warp"),
    TCP_FALLBACK("tcp_fallback");

    companion object {
        fun fromPref(value: String?): RouteKind? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.prefValue == normalized }
        }
    }
}

/**
 * For v2, activeRoutes and inactiveRoutes are authoritative, ordered lists.
 * Legacy fields are retained only for migration and older consumers.
 */
data class NetworkRoutePolicy(
    val networkType: NetworkProfileType,
    val enabledRoutes: Set<RouteKind>,
    val preferredRoute: RouteKind?,
    val autoStrategy: AutoStrategy,
    val allowFallback: Boolean,
    val activeRoutes: List<RouteKind> = emptyList(),
    val inactiveRoutes: List<RouteKind> = emptyList(),
)

object DefaultNetworkRoutePolicies {
    fun forType(type: NetworkProfileType): NetworkRoutePolicy {
        val active = when (type) {
            NetworkProfileType.WIFI -> listOf(
                RouteKind.CF_PROXY_WS,
                RouteKind.DIRECT_WS,
                RouteKind.TCP_FALLBACK,
            )
            NetworkProfileType.MOBILE, NetworkProfileType.UNKNOWN -> listOf(RouteKind.CF_PROXY_WS)
        }
        return NetworkRoutePolicy(
            networkType = type,
            enabledRoutes = active.toSet(),
            preferredRoute = active.first(),
            autoStrategy = AutoStrategy.CF_PREFERRED,
            allowFallback = active.size > 1,
            activeRoutes = active,
            inactiveRoutes = NetworkRoutePolicyEditor.routeOrder.filterNot { it in active },
        )
    }
}
