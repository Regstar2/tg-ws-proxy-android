package com.amurcanov.tgwsproxy

object NetworkRoutePolicyEditor {
    val routeOrder: List<RouteKind> = listOf(
        RouteKind.DIRECT_WS,
        RouteKind.WORKER_WS,
        RouteKind.CF_PROXY_WS,
        RouteKind.TCP_FALLBACK,
    )

    fun setRouteEnabled(
        policy: NetworkRoutePolicy,
        route: RouteKind,
        enabled: Boolean,
    ): NetworkRoutePolicy {
        val current = ordered(policy.enabledRoutes).toMutableSet()
        if (enabled) {
            current += route
        } else {
            if (current.size <= 1 && route in current) {
                return normalize(policy)
            }
            current -= route
        }
        return normalize(policy.copy(enabledRoutes = ordered(current).toSet()))
    }

    fun setPreferredRoute(
        policy: NetworkRoutePolicy,
        route: RouteKind,
    ): NetworkRoutePolicy {
        if (route !in policy.enabledRoutes) {
            return normalize(policy)
        }
        return normalize(policy.copy(preferredRoute = route))
    }

    fun normalize(policy: NetworkRoutePolicy): NetworkRoutePolicy {
        val routes = ordered(policy.enabledRoutes)
        if (routes.isEmpty()) {
            return DefaultNetworkRoutePolicies.forType(policy.networkType)
        }
        val preferred = policy.preferredRoute
            ?.takeIf { it in routes }
            ?: routes.first()
        return policy.copy(
            enabledRoutes = routes.toSet(),
            preferredRoute = preferred,
        )
    }

    private fun ordered(routes: Set<RouteKind>): List<RouteKind> {
        return routeOrder.filter { it in routes }
    }
}
