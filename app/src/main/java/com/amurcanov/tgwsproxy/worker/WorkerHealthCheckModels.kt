package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.routeprobe.RouteProbeConfig

enum class WorkerHealthCheckStatus {
    OK,
    FAIL,
    PARTIAL,
    TIMEOUT,
    SKIPPED,
    INVALID_CONFIG,
    UNKNOWN,
}

enum class WorkerHealthCheckStep {
    CONFIG_VALIDATION,
    DNS_RESOLVE,
    TCP_CONNECT,
    TLS_HANDSHAKE,
    HTTP_PROBE,
    WEBSOCKET_HANDSHAKE,
}

enum class WorkerHealthErrorCode {
    NONE,
    INVALID_WORKER_URL,
    WORKER_DISABLED,
    DNS_FAILED,
    TCP_CONNECT_FAILED,
    TLS_HANDSHAKE_FAILED,
    HTTP_STATUS_ERROR,
    WEBSOCKET_HANDSHAKE_FAILED,
    TIMEOUT,
    NETWORK_UNAVAILABLE,
    CANCELLED,
    UNKNOWN_ERROR,
}

data class WorkerHealthCheckStepResult(
    val step: WorkerHealthCheckStep,
    val status: WorkerHealthCheckStatus,
    val latencyMs: Long = 0,
    val errorCode: WorkerHealthErrorCode = WorkerHealthErrorCode.NONE,
    val debugDetail: String = "",
)

data class WorkerHealthCheckResult(
    val workerId: String,
    val workerName: String,
    val targetUrlMasked: String,
    val status: WorkerHealthCheckStatus,
    val state: WorkerHealthState,
    val steps: List<WorkerHealthCheckStepResult>,
    val latencyMs: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val errorCode: WorkerHealthErrorCode,
    val errorMessageForDebug: String = "",
)

data class WorkerHealthCheckSummary(
    val results: List<WorkerHealthCheckResult>,
    val healthyCount: Int,
    val degradedCount: Int,
    val deadCount: Int,
    val skippedCount: Int,
    val finishedAtMs: Long,
)

object WorkerHealthCheckConfig {
    const val DEGRADED_FAILURE_THRESHOLD = 1
    const val DEAD_FAILURE_THRESHOLD = 3
    const val DEFAULT_WORKER_HEALTH_CONNECT_TIMEOUT_MS = RouteProbeConfig.DEFAULT_CONNECT_TIMEOUT_MS
    const val DEFAULT_WORKER_HEALTH_READ_TIMEOUT_MS = RouteProbeConfig.DEFAULT_READ_TIMEOUT_MS
    const val DEFAULT_WORKER_HEALTH_OVERALL_TIMEOUT_MS = RouteProbeConfig.DEFAULT_OVERALL_TIMEOUT_MS
    const val DEFAULT_DEAD_WORKER_BACKOFF_MS = 5 * 60 * 1000L
}
