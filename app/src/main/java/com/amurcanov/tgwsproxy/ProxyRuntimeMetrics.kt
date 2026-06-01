package com.amurcanov.tgwsproxy

data class ProxyRuntimeMetrics(
    val running: Boolean = false,
    val mode: String = "",
    val route: String = "",
    val activeConnections: Long = 0,
    val totalConnections: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val lastLatencyMs: Long = 0,
    val lastError: String = "",
    val workerPoolHits: Int = 0,
    val workerPoolMisses: Int = 0,
    val workerPoolIdle: Int = 0,
    val workerPoolErrors: Int = 0,
    val cfPoolHits: Int = 0,
    val cfPoolMisses: Int = 0,
    val cfPoolIdle: Int = 0,
    val cfPoolErrors: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun routeLabel(context: android.content.Context): String = RouteDisplayNames.routeLabel(context, route)

    fun modeLabel(context: android.content.Context): String = RouteDisplayNames.modeLabel(context, mode)

    companion object {
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
            return ProxyRuntimeMetrics(
                running = map["running"] == "1",
                mode = map["mode"].orEmpty(),
                route = map["route"].orEmpty(),
                activeConnections = map["active"]?.toLongOrNull() ?: 0,
                bytesUp = map["bytes_up"]?.toLongOrNull() ?: 0,
                bytesDown = map["bytes_down"]?.toLongOrNull() ?: 0,
                lastLatencyMs = map["latency_ms"]?.toLongOrNull() ?: 0,
                lastError = map["last_error"].orEmpty(),
                workerPoolHits = map.safeInt("worker_pool_hits"),
                workerPoolMisses = map.safeInt("worker_pool_misses"),
                workerPoolIdle = map.safeInt("worker_pool_idle"),
                workerPoolErrors = map.safeInt("worker_pool_refill_errors", "worker_pool_err"),
                cfPoolHits = map.safeInt("cf_pool_hits"),
                cfPoolMisses = map.safeInt("cf_pool_misses"),
                cfPoolIdle = map.safeInt("cf_pool_idle"),
                cfPoolErrors = map.safeInt("cf_pool_refill_errors", "cf_pool_err"),
            )
        }

        /** @deprecated Use [RouteDisplayNames.routeLabelRes] in UI code. */
        fun routeDisplayLabel(route: String): String = route

        /** @deprecated Use [RouteDisplayNames.modeLabelRes] in UI code. */
        fun modeDisplayLabel(mode: String): String = mode
    }
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
