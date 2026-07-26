package com.amurcanov.tgwsproxy

data class ProxyRuntimeMetrics(
    val running: Boolean = false,
    val mode: String = "",
    /** @deprecated Legacy field; prefer [activeRouteKind]. */
    val route: String = "",
    val activeRouteKind: String = "",
    val transportType: String = "",
    val policyGeneration: Long = 0,
    val allowedRoutes: String = "",
    val preferredRoute: String = "",
    val activeConnections: Long = 0,
    val totalConnections: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val lastLatencyMs: Long = 0,
    val lastError: String = "",
    val workerEndpointPoolHits: Int = 0,
    val workerEndpointPoolMisses: Int = 0,
    val workerWsPreconnectEnabled: Boolean = false,
    val workerWsPreconnectHits: Int = 0,
    val workerWsPreconnectMisses: Int = 0,
    val workerWsPreconnectIdle: Int = 0,
    val workerWsPreconnectErrors: Int = 0,
    /** @deprecated Legacy alias of [workerWsPreconnectHits]; prefer [workerEndpointPoolHits] for endpoint selection. */
    val workerPoolHits: Int = 0,
    /** @deprecated Legacy alias of [workerWsPreconnectMisses]. */
    val workerPoolMisses: Int = 0,
    /** @deprecated Legacy alias of [workerWsPreconnectIdle]. */
    val workerPoolIdle: Int = 0,
    /** @deprecated Legacy alias of [workerWsPreconnectErrors]. */
    val workerPoolErrors: Int = 0,
    val cfPoolHits: Int = 0,
    val cfPoolMisses: Int = 0,
    val cfPoolIdle: Int = 0,
    val cfPoolErrors: Int = 0,
    val routeRuntime: RouteRuntimeState = RouteRuntimeState(),
    val destinationModeStats: List<DestinationModeStatsEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val currentRouteKind: String
        get() = activeRouteKind.ifBlank { route }

    fun routeLabel(context: android.content.Context): String =
        RouteDisplayNames.currentRouteLabel(context, currentRouteKind, running)

    fun transportLabel(context: android.content.Context): String =
        RouteDisplayNames.transportLabel(context, transportType)

    fun modeLabel(context: android.content.Context): String = RouteDisplayNames.modeLabel(context, mode)

    companion object {
        internal fun fromMtProtoStatus(status: MtProtoNativeStatus): ProxyRuntimeMetrics {
            val activeRoute = status.actualBackend.noneAsBlank()
                .ifBlank { status.selectedBackend.noneAsBlank() }
            val selectedRoute = status.selectedBackend.noneAsBlank()
            return ProxyRuntimeMetrics(
                running = status.toRuntimeState() == MtProtoRuntimeState.RUNNING ||
                    status.toRuntimeState() == MtProtoRuntimeState.LISTENING_LOCAL_ONLY,
                mode = "mtproto",
                route = activeRoute,
                activeRouteKind = activeRoute,
                transportType = if (activeRoute.isBlank()) "" else "websocket",
                activeConnections = status.activeConnections,
                totalConnections = status.totalConnections,
                lastError = status.lastError.noneAsBlank(),
                routeRuntime = RouteRuntimeState(
                    configuredMode = "mtproto",
                    selectedRoute = selectedRoute,
                    activeRoute = activeRoute,
                    lastSuccessfulRoute = if (activeRoute.isNotBlank()) activeRoute else "",
                    fallbackReason = if (status.fallbackUsed) status.routeReason.noneAsBlank() else "",
                    lastUpdatedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        fun parseStatus(raw: String?): ProxyRuntimeMetrics? {
            if (raw.isNullOrBlank()) {
                return null
            }
            val map = mutableMapOf<String, String>()
            raw.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    map[part.substring(0, idx)] = part.substring(idx + 1)
                }
            }
            val activeRoute = map["active_route_kind"].orEmpty().ifBlank { map["route"].orEmpty() }
            val routeRuntime = RouteRuntimeState.fromStatusMap(map)
            val workerEndpointHits = map.safeInt("worker_endpoint_pool_hits")
            val workerEndpointMisses = map.safeInt("worker_endpoint_pool_misses")
            val wsPreconnectEnabled = map["worker_ws_preconnect_enabled"] == "1"
            val wsPreconnectHits = map.safeInt("worker_ws_preconnect_hits", "worker_pool_hits")
            val wsPreconnectMisses = map.safeInt("worker_ws_preconnect_misses", "worker_pool_misses")
            val wsPreconnectIdle = map.safeInt("worker_ws_preconnect_idle", "worker_pool_idle")
            val wsPreconnectErrors = map.safeInt("worker_ws_preconnect_refill_errors", "worker_pool_refill_errors", "worker_pool_err")
            return ProxyRuntimeMetrics(
                running = map["running"] == "1",
                mode = map["mode"].orEmpty(),
                route = activeRoute,
                activeRouteKind = routeRuntime.activeRoute.ifBlank { activeRoute },
                transportType = map["transport_type"].orEmpty(),
                policyGeneration = map["policy_generation"]?.toLongOrNull() ?: 0,
                allowedRoutes = map["allowed_routes"].orEmpty(),
                preferredRoute = map["preferred_route"].orEmpty(),
                activeConnections = map["active"]?.toLongOrNull() ?: 0,
                bytesUp = map["bytes_up"]?.toLongOrNull() ?: 0,
                bytesDown = map["bytes_down"]?.toLongOrNull() ?: 0,
                lastLatencyMs = map["latency_ms"]?.toLongOrNull() ?: 0,
                lastError = map["last_error"].orEmpty(),
                workerEndpointPoolHits = workerEndpointHits,
                workerEndpointPoolMisses = workerEndpointMisses,
                workerWsPreconnectEnabled = wsPreconnectEnabled,
                workerWsPreconnectHits = wsPreconnectHits,
                workerWsPreconnectMisses = wsPreconnectMisses,
                workerWsPreconnectIdle = wsPreconnectIdle,
                workerWsPreconnectErrors = wsPreconnectErrors,
                workerPoolHits = wsPreconnectHits,
                workerPoolMisses = wsPreconnectMisses,
                workerPoolIdle = wsPreconnectIdle,
                workerPoolErrors = wsPreconnectErrors,
                cfPoolHits = map.safeInt("cf_pool_hits"),
                cfPoolMisses = map.safeInt("cf_pool_misses"),
                cfPoolIdle = map.safeInt("cf_pool_idle"),
                cfPoolErrors = map.safeInt("cf_pool_refill_errors", "cf_pool_err"),
                routeRuntime = routeRuntime,
                destinationModeStats = DestinationModeStatsParser.parse(map["destination_mode_stats"]),
            )
        }

        /** @deprecated Use [RouteDisplayNames.routeLabelRes] in UI code. */
        fun routeDisplayLabel(route: String): String = route

        /** @deprecated Use [RouteDisplayNames.modeLabelRes] in UI code. */
        fun modeDisplayLabel(mode: String): String = mode
    }
}

private fun String.noneAsBlank(): String {
    return trim().takeUnless { it.equals("none", ignoreCase = true) }.orEmpty()
}

private fun Map<String, String>.safeInt(vararg keys: String): Int {
    for (key in keys) {
        val parsed = this[key]?.toIntOrNull()
        if (parsed != null) {
            return parsed
        }
    }
    return 0
}

class SpeedSampler(private val windowMs: Long = 4000) {
    private var lastBytesUp: Long = 0
    private var lastBytesDown: Long = 0
    private var lastSampleAtMs: Long? = null
    private var downloadBps: Double = 0.0
    private var uploadBps: Double = 0.0

    fun sample(bytesUp: Long, bytesDown: Long, nowMs: Long = System.currentTimeMillis()): Pair<Double, Double> {
        val previousSampleAtMs = lastSampleAtMs
        if (previousSampleAtMs == null) {
            lastBytesUp = bytesUp
            lastBytesDown = bytesDown
            lastSampleAtMs = nowMs
            return 0.0 to 0.0
        }
        val elapsedSec = (nowMs - previousSampleAtMs).coerceAtLeast(1L) / 1000.0
        if (nowMs - previousSampleAtMs >= windowMs / 2) {
            val upDelta = (bytesUp - lastBytesUp).coerceAtLeast(0)
            val downDelta = (bytesDown - lastBytesDown).coerceAtLeast(0)
            downloadBps = downDelta / elapsedSec
            uploadBps = upDelta / elapsedSec
            lastBytesUp = bytesUp
            lastBytesDown = bytesDown
            lastSampleAtMs = nowMs
        }
        return downloadBps to uploadBps
    }

    fun reset() {
        lastBytesUp = 0
        lastBytesDown = 0
        lastSampleAtMs = null
        downloadBps = 0.0
        uploadBps = 0.0
    }
}
