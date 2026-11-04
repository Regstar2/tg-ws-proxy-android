package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

class NetworkRoutePolicyRepository(
    private val prefs: SharedPreferences,
) {
    fun load(type: NetworkProfileType): NetworkRoutePolicy {
        val v2 = prefs.getString(v2KeyFor(type), null)
        if (v2 != null) return parseV2(type, v2)
        return parseV1(type, prefs.getString(v1KeyFor(type), null))
    }

    fun hasSavedPolicy(type: NetworkProfileType): Boolean {
        return prefs.contains(v2KeyFor(type)) || prefs.contains(v1KeyFor(type))
    }

    fun save(policy: NetworkRoutePolicy) {
        val normalized = NetworkRoutePolicyEditor.normalize(policy)
        prefs.edit().putString(v2KeyFor(normalized.networkType), encodeV2(normalized)).apply()
    }

    fun reset(type: NetworkProfileType) {
        prefs.edit().remove(v2KeyFor(type)).remove(v1KeyFor(type)).apply()
    }

    fun resetAll() {
        val edit = prefs.edit()
        NetworkProfileType.entries.forEach { type ->
            edit.remove(v2KeyFor(type)).remove(v1KeyFor(type))
        }
        edit.apply()
    }

    fun loadAll(): Map<NetworkProfileType, NetworkRoutePolicy> {
        return NetworkProfileType.entries.associateWith { load(it) }
    }

    private fun parseV2(type: NetworkProfileType, raw: String): NetworkRoutePolicy {
        val fields = parseFields(raw)
        val active = parseRoutes(fields["active"])
        if (fields["version"] != "2" || active.isEmpty()) {
            return DefaultNetworkRoutePolicies.forType(type)
        }
        val inactive = parseRoutes(fields["inactive"])
        return NetworkRoutePolicyEditor.normalize(
            NetworkRoutePolicy(
                networkType = type,
                enabledRoutes = active.toSet(),
                preferredRoute = active.first(),
                autoStrategy = AutoStrategy.CF_PREFERRED,
                allowFallback = active.size > 1,
                activeRoutes = active,
                inactiveRoutes = inactive,
            ),
        )
    }

    private fun parseV1(type: NetworkProfileType, raw: String?): NetworkRoutePolicy {
        if (raw.isNullOrBlank()) return DefaultNetworkRoutePolicies.forType(type)
        val fields = parseFields(raw)
        if (fields.isEmpty() || !fields.containsKey("routes")) {
            return DefaultNetworkRoutePolicies.forType(type)
        }
        val routes = parseRoutes(fields["routes"]).toSet()
        val defaults = DefaultNetworkRoutePolicies.forType(type)
        val preferred = RouteKind.fromPref(fields["preferred"])
        val strategy = parseStrategy(fields["strategy"]) ?: defaults.autoStrategy
        val fallback = parseFallback(fields["fallback"]) ?: defaults.allowFallback
        return NetworkRoutePolicyEditor.normalize(
            NetworkRoutePolicy(
                networkType = type,
                enabledRoutes = routes,
                preferredRoute = preferred,
                autoStrategy = strategy,
                allowFallback = fallback,
            ),
        )
    }

    private fun encodeV2(policy: NetworkRoutePolicy): String {
        val active = policy.activeRoutes.joinToString("|") { it.prefValue }
        val inactive = policy.inactiveRoutes.joinToString("|") { it.prefValue }
        return "version=2;active=$active;inactive=$inactive"
    }

    private fun parseRoutes(value: String?): List<RouteKind> {
        return value.orEmpty().split('|').mapNotNull(RouteKind::fromPref).distinct()
    }

    private fun parseFields(raw: String): Map<String, String> {
        return raw.split(';').mapNotNull { field ->
            val index = field.indexOf('=')
            if (index <= 0) null
            else field.substring(0, index).trim().lowercase() to field.substring(index + 1).trim()
        }.toMap()
    }

    private fun parseStrategy(raw: String?): AutoStrategy? {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        val parsed = AutoStrategy.fromPref(normalized)
        return parsed.takeIf { it.prefValue == normalized }
    }

    private fun parseFallback(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    private fun v1KeyFor(type: NetworkProfileType): String = when (type) {
        NetworkProfileType.WIFI -> "route_policy_wifi_v1"
        NetworkProfileType.MOBILE -> "route_policy_mobile_v1"
        NetworkProfileType.UNKNOWN -> "route_policy_unknown_v1"
    }

    private fun v2KeyFor(type: NetworkProfileType): String = when (type) {
        NetworkProfileType.WIFI -> "route_policy_wifi_v2"
        NetworkProfileType.MOBILE -> "route_policy_mobile_v2"
        NetworkProfileType.UNKNOWN -> "route_policy_unknown_v2"
    }
}
