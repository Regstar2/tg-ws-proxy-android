package com.amurcanov.tgwsproxy.routeprobe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RouteProbeSnapshot(
    val target: RouteProbeTarget? = null,
    val status: RouteProbeStatus? = null,
    val checkedAtMs: Long = 0L,
    val errorCode: RouteProbeErrorCode? = null,
)

object RouteProbeDiagnosticsState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastSummary = MutableStateFlow<RouteProbeSummary?>(null)
    val lastSummary: StateFlow<RouteProbeSummary?> = _lastSummary.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<RouteProbeSnapshot?>(null)
    val lastSnapshot: StateFlow<RouteProbeSnapshot?> = _lastSnapshot.asStateFlow()

    suspend fun run(block: suspend () -> RouteProbeSummary) {
        if (_isRunning.value) return
        _isRunning.value = true
        try {
            val summary = block()
            _lastSummary.value = summary
            val primary = summary.results.firstOrNull { it.target == RouteProbeTarget.WORKER_WEBSOCKET }
                ?: summary.results.firstOrNull { it.status == RouteProbeStatus.FAIL || it.status == RouteProbeStatus.TIMEOUT }
                ?: summary.results.lastOrNull()
            _lastSnapshot.value = primary?.let {
                RouteProbeSnapshot(
                    target = it.target,
                    status = it.status,
                    checkedAtMs = it.finishedAtMs,
                    errorCode = it.errorCode,
                )
            }
        } finally {
            _isRunning.value = false
        }
    }

    fun clear() {
        _lastSummary.value = null
        _lastSnapshot.value = null
    }
}
