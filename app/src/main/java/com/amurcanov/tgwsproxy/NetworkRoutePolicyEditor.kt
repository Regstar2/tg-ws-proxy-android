package com.amurcanov.tgwsproxy

/** The only editor for route order and enabled state, shared by arrows and drag. */
object NetworkRoutePolicyEditor {
    val routeOrder: List<RouteKind> = listOf(
        RouteKind.CF_PROXY_WS,
        RouteKind.AWG_WARP,
        RouteKind.WORKER_WS,
        RouteKind.DIRECT_WS,
        RouteKind.TCP_FALLBACK,
    )

    fun setRouteEnabled(
        policy: NetworkRoutePolicy,
        route: RouteKind,
        enabled: Boolean,
    ): NetworkRoutePolicy {
        val current = normalize(policy)
        val active = current.activeRoutes.toMutableList()
        val inactive = current.inactiveRoutes.toMutableList()
        if (enabled) {
            if (route in active || route !in inactive) return current
            inactive.remove(route)
            active.add(route)
        } else {
            if (route !in active || active.size == 1) return current
            active.remove(route)
            inactive.add(route)
        }
        return current.withRoutes(active, inactive)
    }

    fun moveEnabledRoute(
        policy: NetworkRoutePolicy,
        fromIndex: Int,
        toIndex: Int,
    ): NetworkRoutePolicy {
        val current = normalize(policy)
        if (fromIndex !in current.activeRoutes.indices ||
            toIndex !in current.activeRoutes.indices ||
            fromIndex == toIndex
        ) return current
        val active = current.activeRoutes.toMutableList()
        active.add(toIndex, active.removeAt(fromIndex))
        return current.withRoutes(active, current.inactiveRoutes)
    }

    fun setPreferredRoute(policy: NetworkRoutePolicy, route: RouteKind): NetworkRoutePolicy {
        val current = normalize(policy)
        val index = current.activeRoutes.indexOf(route)
        return if (index < 0) current else moveEnabledRoute(current, index, 0)
    }

    fun normalize(policy: NetworkRoutePolicy): NetworkRoutePolicy {
        val active = if (policy.activeRoutes.isNotEmpty()) {
            policy.activeRoutes.distinct()
        } else {
            // Interpret legacy fields exactly once; fallback=false must remain strict-only.
            val enabled = routeOrder.filter { it in policy.enabledRoutes }
            val preferred = policy.preferredRoute?.takeIf { it in enabled } ?: enabled.firstOrNull()
            when {
                preferred == null -> emptyList()
                !policy.allowFallback -> listOf(preferred)
                else -> listOf(preferred) + enabled.filterNot { it == preferred }
            }
        }
        if (active.isEmpty()) return DefaultNetworkRoutePolicies.forType(policy.networkType)
        val inactive = (policy.inactiveRoutes + routeOrder).distinct().filterNot { it in active }
        return policy.withRoutes(active, inactive)
    }

    private fun NetworkRoutePolicy.withRoutes(
        active: List<RouteKind>,
        inactive: List<RouteKind>,
    ): NetworkRoutePolicy = copy(
        enabledRoutes = active.toSet(),
        preferredRoute = active.first(),
        allowFallback = active.size > 1,
        activeRoutes = active.toList(),
        inactiveRoutes = inactive.toList(),
    )
}
