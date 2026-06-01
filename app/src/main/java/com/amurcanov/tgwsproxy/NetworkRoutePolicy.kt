package com.amurcanov.tgwsproxy

enum class RouteKind(val prefValue: String) {
    DIRECT_WS("direct_ws"),
    WORKER_WS("worker_ws"),
    CF_PROXY_WS("cf_proxy_ws"),
    TCP_FALLBACK("tcp_fallback");

    companion object {
        fun fromPref(value: String?): RouteKind? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.prefValue == normalized }
        }
    }
}

data class NetworkRoutePolicy(
    val networkType: NetworkProfileType,
    val enabledRoutes: Set<RouteKind>,
    val preferredRoute: RouteKind?,
    val autoStrategy: AutoStrategy,
    val allowFallback: Boolean,
)

object DefaultNetworkRoutePolicies {
    fun forType(type: NetworkProfileType): NetworkRoutePolicy {
        return when (type) {
            NetworkProfileType.WIFI -> NetworkRoutePolicy(
                networkType = type,
                enabledRoutes = linkedSetOf(
                    RouteKind.DIRECT_WS,
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.DIRECT_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            )
            NetworkProfileType.MOBILE -> NetworkRoutePolicy(
                networkType = type,
                enabledRoutes = linkedSetOf(
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.BALANCED,
                allowFallback = true,
            )
            NetworkProfileType.UNKNOWN -> NetworkRoutePolicy(
                networkType = type,
                enabledRoutes = linkedSetOf(
                    RouteKind.DIRECT_WS,
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.WORKER_WS,
                autoStrategy = AutoStrategy.STRICT_FAST_FAILOVER,
                allowFallback = true,
            )
        }
    }
}
