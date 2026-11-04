package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

/** V2 lists keep user priority; legacy v1 settings are read without modifying them. */
class NetworkRoutePolicyRepository(private val prefs: SharedPreferences) {
    fun load(type: NetworkProfileType): NetworkRoutePolicy {
        val v2 = prefs.getString(keyFor(type, true), null)
        if (!v2.isNullOrBlank()) return parseV2(type, v2)
        return parseV1(type, prefs.getString(keyFor(type, false), null))
    }

    fun hasSavedPolicy(type: NetworkProfileType): Boolean {
        return prefs.contains(keyFor(type, true)) || prefs.contains(keyFor(type, false))
    }

    fun save(policy: NetworkRoutePolicy) {
        val normalized = NetworkRoutePolicyEditor.normalize(policy)
        val active = normalized.activeRoutes.joinToString("|") { it.prefValue }
        val inactive = normalized.inactiveRoutes.joinToString("|") { it.prefValue }
        prefs.edit().putString(keyFor(normalized.networkType, true),
            "active=$active;inactive=$inactive;strategy=${normalized.autoStrategy.prefValue}").apply()
    }

    fun reset(type: NetworkProfileType) {
        prefs.edit().remove(keyFor(type, true)).remove(keyFor(type, false)).apply()
    }

    fun resetAll() {
        val edit = prefs.edit()
        NetworkProfileType.entries.forEach {
            edit.remove(keyFor(it, true)).remove(keyFor(it, false))
        }
        edit.apply()
    }

    fun loadAll(): Map<NetworkProfileType, NetworkRoutePolicy> =
        NetworkProfileType.entries.associateWith { load(it) }

    private fun parseV2(type: NetworkProfileType, raw: String): NetworkRoutePolicy {
        val fields = parseFields(raw)
        if (!fields.containsKey("active")) return DefaultNetworkRoutePolicies.forType(type)
        val active = parseRoutes(fields["active"])
        if (active.isEmpty()) return DefaultNetworkRoutePolicies.forType(type)
        val inactive = parseRoutes(fields["inactive"]).filterNot { it in active }
        val strategy = fields["strategy"]?.let(AutoStrategy::fromPref)
            ?: DefaultNetworkRoutePolicies.forType(type).autoStrategy
        return NetworkRoutePolicyEditor.normalize(
            DefaultNetworkRoutePolicies.forType(type).copy(
                autoStrategy = strategy,
                activeRoutes = active,
                inactiveRoutes = inactive,
            ),
        )
    }

    private fun parseV1(type: NetworkProfileType, raw: String?): NetworkRoutePolicy {
        if (raw.isNullOrBlank()) return DefaultNetworkRoutePolicies.forType(type)
        val fields = parseFields(raw)
        val routes = parseRoutes(fields["routes"]).toSet()
        if (routes.isEmpty()) return DefaultNetworkRoutePolicies.forType(type)
        val defaults = DefaultNetworkRoutePolicies.forType(type)
        val first = RouteKind.fromPref(fields["preferred"])?.takeIf { it in routes }
            ?: parseRoutes(fields["routes"]).first()
        val fallback = when (fields["fallback"]?.trim()?.lowercase()) {
            "0", "false", "no" -> false
            "1", "true", "yes" -> true
            else -> defaults.allowFallback
        }
        val strategy = AutoStrategy.fromPref(fields["strategy"])
        return NetworkRoutePolicyEditor.normalize(
            defaults.copy(
                enabledRoutes = routes,
                preferredRoute = first,
                allowFallback = fallback,
                autoStrategy = strategy,
                activeRoutes = emptyList(),
                inactiveRoutes = emptyList(),
            ),
        )
    }

    private fun parseFields(raw: String): Map<String, String> =
        raw.split(';').mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) null else
                field.substring(0, separator).trim().lowercase() to
                    field.substring(separator + 1).trim()
        }.toMap()

    private fun parseRoutes(raw: String?): List<RouteKind> =
        raw.orEmpty().split('|').mapNotNull(RouteKind::fromPref).distinct()

    private fun keyFor(type: NetworkProfileType, v2: Boolean): String {
        val suffix = when (type) {
            NetworkProfileType.WIFI -> "wifi"
            NetworkProfileType.MOBILE -> "mobile"
            NetworkProfileType.UNKNOWN -> "unknown"
        }
        return "route_policy_${suffix}_v${if (v2) 2 else 1}"
    }
}
