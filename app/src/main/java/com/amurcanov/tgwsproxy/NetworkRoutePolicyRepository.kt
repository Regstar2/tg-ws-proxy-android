package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

class NetworkRoutePolicyRepository(
    private val prefs: SharedPreferences,
) {
    fun load(type: NetworkProfileType): NetworkRoutePolicy {
        return parse(type, prefs.getString(keyFor(type), null))
    }

    fun hasSavedPolicy(type: NetworkProfileType): Boolean {
        return prefs.contains(keyFor(type))
    }

    fun save(policy: NetworkRoutePolicy) {
        val normalized = normalize(policy)
        prefs.edit().putString(keyFor(normalized.networkType), encode(normalized)).apply()
    }

    fun reset(type: NetworkProfileType) {
        prefs.edit().remove(keyFor(type)).apply()
    }

    fun resetAll() {
        prefs.edit()
            .remove(KEY_WIFI)
            .remove(KEY_MOBILE)
            .remove(KEY_UNKNOWN)
            .apply()
    }

    fun loadAll(): Map<NetworkProfileType, NetworkRoutePolicy> {
        return NetworkProfileType.entries.associateWith { load(it) }
    }

    private fun parse(type: NetworkProfileType, raw: String?): NetworkRoutePolicy {
        if (raw.isNullOrBlank()) {
            return DefaultNetworkRoutePolicies.forType(type)
        }

        return runCatching {
            val fields = raw.split(';')
                .mapNotNull { field ->
                    val index = field.indexOf('=')
                    if (index <= 0) {
                        null
                    } else {
                        field.substring(0, index).trim().lowercase() to field.substring(index + 1).trim()
                    }
                }
                .toMap()

            if (fields.isEmpty()) {
                return@runCatching DefaultNetworkRoutePolicies.forType(type)
            }

            val routes = fields["routes"]
                ?.split('|')
                .orEmpty()
                .mapNotNull { RouteKind.fromPref(it) }
                .toSetInStableOrder()
            val defaults = DefaultNetworkRoutePolicies.forType(type)
            val preferred = RouteKind.fromPref(fields["preferred"])
            val strategy = parseStrategy(fields["strategy"]) ?: defaults.autoStrategy
            val fallback = parseFallback(fields["fallback"]) ?: defaults.allowFallback

            normalize(
                NetworkRoutePolicy(
                    networkType = type,
                    enabledRoutes = routes,
                    preferredRoute = preferred,
                    autoStrategy = strategy,
                    allowFallback = fallback,
                ),
            )
        }.getOrElse {
            DefaultNetworkRoutePolicies.forType(type)
        }
    }

    private fun encode(policy: NetworkRoutePolicy): String {
        val routes = orderedRoutes(policy.enabledRoutes).joinToString("|") { it.prefValue }
        val preferred = policy.preferredRoute?.prefValue.orEmpty()
        val fallback = if (policy.allowFallback) "1" else "0"
        return "routes=$routes;preferred=$preferred;strategy=${policy.autoStrategy.prefValue};fallback=$fallback"
    }

    private fun normalize(policy: NetworkRoutePolicy): NetworkRoutePolicy {
        val routes = orderedRoutes(policy.enabledRoutes).toSet()
        if (routes.isEmpty()) {
            return DefaultNetworkRoutePolicies.forType(policy.networkType)
        }
        val preferred = policy.preferredRoute
            ?.takeIf { it in routes }
            ?: routes.first()
        return policy.copy(
            enabledRoutes = routes,
            preferredRoute = preferred,
        )
    }

    private fun parseStrategy(raw: String?): AutoStrategy? {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        val parsed = AutoStrategy.fromPref(normalized)
        return parsed.takeIf { it.prefValue == normalized }
    }

    private fun parseFallback(raw: String?): Boolean? {
        return when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    private fun orderedRoutes(routes: Set<RouteKind>): List<RouteKind> {
        return ROUTE_ORDER.filter { it in routes }
    }

    private fun List<RouteKind>.toSetInStableOrder(): Set<RouteKind> {
        val values = toSet()
        return orderedRoutes(values).toSet()
    }

    private fun keyFor(type: NetworkProfileType): String {
        return when (type) {
            NetworkProfileType.WIFI -> KEY_WIFI
            NetworkProfileType.MOBILE -> KEY_MOBILE
            NetworkProfileType.UNKNOWN -> KEY_UNKNOWN
        }
    }

    private companion object {
        const val KEY_WIFI = "route_policy_wifi_v1"
        const val KEY_MOBILE = "route_policy_mobile_v1"
        const val KEY_UNKNOWN = "route_policy_unknown_v1"

        val ROUTE_ORDER = listOf(
            RouteKind.DIRECT_WS,
            RouteKind.WORKER_WS,
            RouteKind.CF_PROXY_WS,
            RouteKind.TCP_FALLBACK,
        )
    }
}
