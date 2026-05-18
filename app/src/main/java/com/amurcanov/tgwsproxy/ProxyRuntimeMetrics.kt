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
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun routeLabel(): String = routeDisplayLabel(route)

    fun modeLabel(): String = modeDisplayLabel(mode)

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
            )
        }

        fun routeDisplayLabel(route: String): String {
            return when (route) {
                "direct_ws", "direct" -> "Direct"
                "cf_worker_ws", "worker" -> "Worker"
                "cf_proxy_ws", "cf" -> "CF proxy"
                "tcp_fallback", "tcp" -> "TCP fallback"
                else -> route.ifBlank { "—" }
            }
        }

        fun modeDisplayLabel(mode: String): String {
            return when (mode) {
                "auto" -> "Auto"
                "direct_with_fallback" -> "Direct + fallback"
                "worker_first" -> "Worker first"
                "cf_first" -> "CF first"
                "worker_only" -> "Worker only"
                "cf_only" -> "CF only"
                "direct_only" -> "Direct only"
                else -> mode.ifBlank { "—" }
            }
        }
    }
}

class SpeedSampler(private val windowMs: Long = 4000) {
    private var lastBytesUp: Long = 0
    private var lastBytesDown: Long = 0
    private var lastSampleAtMs: Long = 0
    private var downloadBps: Double = 0.0
    private var uploadBps: Double = 0.0

    fun sample(bytesUp: Long, bytesDown: Long, nowMs: Long = System.currentTimeMillis()): Pair<Double, Double> {
        if (lastSampleAtMs == 0L) {
            lastBytesUp = bytesUp
            lastBytesDown = bytesDown
            lastSampleAtMs = nowMs
            return 0.0 to 0.0
        }
        val elapsedSec = (nowMs - lastSampleAtMs).coerceAtLeast(1L) / 1000.0
        if (nowMs - lastSampleAtMs >= windowMs / 2) {
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
        lastSampleAtMs = 0
        downloadBps = 0.0
        uploadBps = 0.0
    }
}
