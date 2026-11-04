package com.amurcanov.tgwsproxy

/**
 * Edits the native fallback chain without moving disabled rows in the visual list.
 * An inactive route keeps its slot and resumes its old priority on re-enable.
 */
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
        if (route !in current.displayOrder) return current
        val enabledSet = current.activeRoutes.toMutableSet()
        if (enabled) {
            if (!enabledSet.add(route)) return current
        } else {
            if (route !in enabledSet || enabledSet.size == 1) return current
            enabledSet.remove(route)
        }
        return current.withOrderedSlots(current.displayOrder, enabledSet)
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

        val positions = current.displayOrder.indices.filter {
            current.displayOrder[it] in current.enabledRoutes
        }
        val moved = current.activeRoutes.toMutableList()
        moved.add(toIndex, moved.removeAt(fromIndex))
        val slots = current.displayOrder.toMutableList()
        positions.forEachIndexed { index, slot -> slots[slot] = moved[index] }
        return current.withOrderedSlots(slots, moved.toSet())
    }

    /**
     * A route with missing local configuration cannot be the only usable choice.
     * Network reachability itself is checked by the native connector.
     */
    fun hasConfiguredOutboundRoute(
        policy: NetworkRoutePolicy,
        workerConfigured: Boolean,
        awgConfigured: Boolean,
    ): Boolean = normalize(policy).activeRoutes.any { route ->
        when (route) {
            RouteKind.WORKER_WS -> workerConfigured
            RouteKind.AWG_WARP -> awgConfigured
            RouteKind.DIRECT_WS, RouteKind.CF_PROXY_WS, RouteKind.TCP_FALLBACK -> true
        }
    }

    fun setPreferredRoute(policy: NetworkRoutePolicy, route: RouteKind): NetworkRoutePolicy {
        val current = normalize(policy)
        val index = current.activeRoutes.indexOf(route)
        return if (index < 0) current else moveEnabledRoute(current, index, 0)
    }

    fun normalize(policy: NetworkRoutePolicy): NetworkRoutePolicy {
        val selected = if (policy.activeRoutes.isNotEmpty()) {
            policy.activeRoutes.distinct()
        } else {
            // Legacy policies must retain strict-only semantics.
            val enabled = routeOrder.filter { it in policy.enabledRoutes }
            val preferred = policy.preferredRoute?.takeIf { it in enabled } ?: enabled.firstOrNull()
            when {
                preferred == null -> emptyList()
                !policy.allowFallback -> listOf(preferred)
                else -> listOf(preferred) + enabled.filterNot { it == preferred }
            }
        }
        if (selected.isEmpty()) return DefaultNetworkRoutePolicies.forType(policy.networkType)
        val suppliedOrder = if (policy.displayOrder.isNotEmpty()) {
            policy.displayOrder
        } else {
            selected + policy.inactiveRoutes
        }
        val allSlots = (suppliedOrder + routeOrder).distinct()
        return policy.withOrderedSlots(allSlots, selected.toSet())
    }

    private fun NetworkRoutePolicy.withOrderedSlots(
        slots: List<RouteKind>,
        enabled: Set<RouteKind>,
    ): NetworkRoutePolicy {
        val active = slots.filter { it in enabled }
        return copy(
            enabledRoutes = active.toSet(),
            preferredRoute = active.firstOrNull(),
            allowFallback = active.size > 1,
            activeRoutes = active,
            inactiveRoutes = slots.filterNot { it in enabled },
            displayOrder = slots,
        )
    }
}
