package com.amurcanov.tgwsproxy

object NetworkRoutePolicyEditor {
    val routeOrder: List<RouteKind> = listOf(
        RouteKind.DIRECT_WS,
        RouteKind.WORKER_WS,
        RouteKind.CF_PROXY_WS,
        RouteKind.AWG_WARP,
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
            if (route in active) return current
            inactive.remove(route)
            active.add(route)
        } else {
            if (route !in active || active.size == 1) return current
            active.remove(route)
            inactive.add(route)
        }
        return withOrder(current, active, inactive)
    }

    /** Shared by arrow controls and drag-and-drop. Disabled routes cannot be moved. */
    fun moveEnabledRoute(
        policy: NetworkRoutePolicy,
        fromIndex: Int,
        toIndex: Int,
    ): NetworkRoutePolicy {
        val current = normalize(policy)
        val active = current.activeRoutes.toMutableList()
        if (fromIndex !in active.indices || toIndex !in active.indices || fromIndex == toIndex) {
            return current
        }
        val route = active.removeAt(fromIndex)
        active.add(toIndex, route)
        return withOrder(current, active, current.inactiveRoutes)
    }

    /** Legacy preferred-route editor is equivalent to moving the route to priority one. */
    fun setPreferredRoute(
        policy: NetworkRoutePolicy,
        route: RouteKind,
    ): NetworkRoutePolicy {
        val current = normalize(policy)
        val index = current.activeRoutes.indexOf(route)
        if (index < 0) return current
        return moveEnabledRoute(current, index, 0)
    }

    fun normalize(policy: NetworkRoutePolicy): NetworkRoutePolicy {
        val explicitOrder = policy.activeRoutes.isNotEmpty()
        val active = if (explicitOrder) {
            policy.activeRoutes.distinct()
        } else {
            val enabled = routeOrder.filter { it in policy.enabledRoutes }
            val first = policy.preferredRoute?.takeIf { it in enabled } ?: enabled.firstOrNull()
            if (first == null) {
                emptyList()
            } else if (!policy.allowFallback) {
                listOf(first)
            } else {
                listOf(first) + enabled.filterNot { it == first }
            }
        }
        if (active.isEmpty()) {
            return DefaultNetworkRoutePolicies.forType(policy.networkType)
        }
        val inactive = (policy.inactiveRoutes + routeOrder)
            .distinct()
            .filterNot { it in active }
        return withOrder(policy, active, inactive)
    }

    private fun withOrder(
        policy: NetworkRoutePolicy,
        active: List<RouteKind>,
        inactive: List<RouteKind>,
    ): NetworkRoutePolicy = policy.copy(
        enabledRoutes = active.toSet(),
        preferredRoute = active.first(),
        allowFallback = active.size > 1,
        activeRoutes = active.toList(),
        inactiveRoutes = inactive.toList(),
    )
}
