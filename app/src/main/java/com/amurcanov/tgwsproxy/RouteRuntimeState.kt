package com.amurcanov.tgwsproxy

import android.content.Context

/**
 * Runtime route truth exported from Go via [ProxyRuntimeMetrics.parseStatus].
 * UI must display these fields — not infer active route from configured mode alone.
 */
data class RouteRuntimeState(
    val configuredMode: String = "",
    val selectedRoute: String = "",
    val activeRoute: String = "",
    val lastSuccessfulRoute: String = "",
    val lastFailedRoute: String = "",
    val fallbackReason: String = "",
    val currentWorkerDomain: String = "",
    val currentWorkerId: String = "",
    val currentWorkerName: String = "",
    val currentWorkerUrlMasked: String = "",
    val currentWorkerState: com.amurcanov.tgwsproxy.worker.WorkerHealthState? = null,
    val networkType: String = "",
    val lastUpdatedAtMs: Long = 0L,
) {
    fun configuredModeLabel(context: Context): String =
        RouteDisplayNames.modeLabel(context, configuredMode.ifBlank { "" })

    fun selectedRouteLabel(context: Context, running: Boolean): String =
        RouteDisplayNames.currentRouteLabel(context, selectedRoute, running)

    fun activeRouteLabel(context: Context, running: Boolean): String =
        RouteDisplayNames.currentRouteLabel(context, activeRoute, running)

    fun lastSuccessRouteLabel(context: Context): String =
        RouteDisplayNames.routeLabel(context, lastSuccessfulRoute)

    fun lastFailedRouteLabel(context: Context): String =
        RouteDisplayNames.routeLabel(context, lastFailedRoute)

    fun fallbackReasonLabel(context: Context): String =
        RouteFailureReason.label(context, fallbackReason)

    fun networkTypeLabel(context: Context): String =
        RouteNetworkType.label(context, networkType)

    fun currentWorkerLabel(context: Context): String {
        if (currentWorkerName.isNotBlank()) {
            return currentWorkerName
        }
        if (currentWorkerUrlMasked.isNotBlank()) {
            return currentWorkerUrlMasked
        }
        if (currentWorkerDomain.isNotBlank()) {
            return com.amurcanov.tgwsproxy.worker.WorkerUrlSanitizer.maskForDisplay(currentWorkerDomain)
        }
        return context.getString(R.string.common_none)
    }

    fun currentWorkerStateLabel(context: Context): String {
        val state = currentWorkerState ?: com.amurcanov.tgwsproxy.worker.WorkerHealthState.UNKNOWN
        return WorkerHealthStateLabels.label(context, state)
    }

    companion object {
        fun fromStatusMap(map: Map<String, String>): RouteRuntimeState {
            val configured = map["configured_mode"].orEmpty().ifBlank { map["mode"].orEmpty() }
            val active = map["active_route_kind"].orEmpty().ifBlank { map["route"].orEmpty() }
            return RouteRuntimeState(
                configuredMode = configured,
                selectedRoute = map["selected_route"].orEmpty(),
                activeRoute = active,
                lastSuccessfulRoute = map["last_success_route"].orEmpty(),
                lastFailedRoute = map["last_failed_route"].orEmpty(),
                fallbackReason = map["fallback_reason"].orEmpty(),
                currentWorkerDomain = map["current_worker_domain"].orEmpty(),
                networkType = map["network_type"].orEmpty(),
                lastUpdatedAtMs = map["route_state_updated_at"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

internal object RouteFailureReason {
    fun label(context: Context, raw: String): String {
        val code = raw.trim().lowercase()
        if (code.isEmpty()) {
            return context.getString(R.string.common_none)
        }
        val res = when {
            code.contains("worker") && code.contains("timeout") -> R.string.route_failure_worker_timeout
            code.contains("worker") -> R.string.route_failure_worker_failed
            code == "worker_failed" -> R.string.route_failure_worker_failed
            code.contains("cfproxy") || code.contains("cf_proxy") -> R.string.route_failure_cf_proxy_failed
            code == "ws_unavailable" -> R.string.route_failure_ws_unavailable
            code == "ws_blacklisted" -> R.string.route_failure_ws_blacklisted
            code == "direct_disabled_by_policy" -> R.string.route_failure_direct_disabled
            code == "direct_ws_cooldown" -> R.string.route_failure_direct_cooldown
            code == "restricted_mode" -> R.string.route_failure_restricted_mode
            code == "primary_routes_exhausted" -> R.string.route_failure_primary_exhausted
            code == "tcp_failed" -> R.string.route_failure_tcp_failed
            code == "dc_not_configured" -> R.string.route_failure_dc_not_configured
            else -> R.string.route_failure_generic
        }
        return context.getString(res)
    }
}

internal object RouteNetworkType {
    fun label(context: Context, raw: String): String {
        return when (raw.trim().lowercase()) {
            "wifi" -> context.getString(R.string.adaptive_network_wifi)
            "mobile", "cellular" -> context.getString(R.string.adaptive_network_mobile)
            "unknown", "" -> context.getString(R.string.adaptive_network_unknown)
            else -> raw.ifBlank { context.getString(R.string.adaptive_network_unknown) }
        }
    }
}

internal object WorkerHealthStateLabels {
    fun label(context: Context, state: com.amurcanov.tgwsproxy.worker.WorkerHealthState): String {
        val res = when (state) {
            com.amurcanov.tgwsproxy.worker.WorkerHealthState.UNKNOWN ->
                R.string.worker_pool_worker_state_unknown
            com.amurcanov.tgwsproxy.worker.WorkerHealthState.HEALTHY ->
                R.string.worker_pool_worker_state_healthy
            com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEGRADED ->
                R.string.worker_pool_worker_state_degraded
            com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEAD ->
                R.string.worker_pool_worker_state_dead
            com.amurcanov.tgwsproxy.worker.WorkerHealthState.DISABLED ->
                R.string.worker_pool_worker_state_disabled
        }
        return context.getString(res)
    }
}
