package com.amurcanov.tgwsproxy

object NetworkRoutePolicyMapper {
    fun fromConnectionMode(
        networkType: NetworkProfileType,
        mode: ConnectionMode,
        strategy: AutoStrategy = AutoStrategy.BALANCED,
    ): NetworkRoutePolicy {
        return when (mode) {
            ConnectionMode.DirectOnly -> strictPolicy(networkType, RouteKind.DIRECT_WS, strategy)
            ConnectionMode.WorkerOnly -> strictPolicy(networkType, RouteKind.WORKER_WS, strategy)
            ConnectionMode.CFOnly -> strictPolicy(networkType, RouteKind.CF_PROXY_WS, strategy)
            ConnectionMode.WorkerFirst -> fallbackPolicy(
                networkType = networkType,
                routes = linkedSetOf(
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.DIRECT_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.WORKER_WS,
                strategy = strategy,
            )
            ConnectionMode.CFFirst -> fallbackPolicy(
                networkType = networkType,
                routes = linkedSetOf(
                    RouteKind.CF_PROXY_WS,
                    RouteKind.WORKER_WS,
                    RouteKind.DIRECT_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.CF_PROXY_WS,
                strategy = strategy,
            )
            ConnectionMode.DirectWithFallback -> fallbackPolicy(
                networkType = networkType,
                routes = linkedSetOf(
                    RouteKind.DIRECT_WS,
                    RouteKind.WORKER_WS,
                    RouteKind.CF_PROXY_WS,
                    RouteKind.TCP_FALLBACK,
                ),
                preferredRoute = RouteKind.DIRECT_WS,
                strategy = strategy,
            )
            ConnectionMode.Auto -> DefaultNetworkRoutePolicies.forType(networkType)
        }
    }

    fun toLegacyConnectionMode(policy: NetworkRoutePolicy): ConnectionMode {
        return when {
            policy.enabledRoutes == setOf(RouteKind.DIRECT_WS) && !policy.allowFallback -> ConnectionMode.DirectOnly
            policy.enabledRoutes == setOf(RouteKind.WORKER_WS) && !policy.allowFallback -> ConnectionMode.WorkerOnly
            policy.enabledRoutes == setOf(RouteKind.CF_PROXY_WS) && !policy.allowFallback -> ConnectionMode.CFOnly
            policy.preferredRoute == RouteKind.WORKER_WS && policy.allowFallback -> ConnectionMode.WorkerFirst
            policy.preferredRoute == RouteKind.CF_PROXY_WS && policy.allowFallback -> ConnectionMode.CFFirst
            policy.preferredRoute == RouteKind.DIRECT_WS && policy.allowFallback -> ConnectionMode.DirectWithFallback
            else -> ConnectionMode.Auto
        }
    }

    private fun strictPolicy(
        networkType: NetworkProfileType,
        route: RouteKind,
        strategy: AutoStrategy,
    ): NetworkRoutePolicy {
        return NetworkRoutePolicy(
            networkType = networkType,
            enabledRoutes = setOf(route),
            preferredRoute = route,
            autoStrategy = strategy,
            allowFallback = false,
        )
    }

    private fun fallbackPolicy(
        networkType: NetworkProfileType,
        routes: Set<RouteKind>,
        preferredRoute: RouteKind,
        strategy: AutoStrategy,
    ): NetworkRoutePolicy {
        return NetworkRoutePolicy(
            networkType = networkType,
            enabledRoutes = routes,
            preferredRoute = preferredRoute,
            autoStrategy = strategy,
            allowFallback = true,
        )
    }
}
