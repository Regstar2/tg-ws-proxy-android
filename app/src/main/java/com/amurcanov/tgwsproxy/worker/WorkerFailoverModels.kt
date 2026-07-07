package com.amurcanov.tgwsproxy.worker

data class WorkerFailoverCandidate(
    val workerId: String,
    val workerName: String,
    val url: String,
    val domain: String,
    val state: WorkerHealthState,
    val enabled: Boolean,
    val priority: Int,
    val latencyMs: Long?,
    val failureCount: Int,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val isSelectedWorker: Boolean,
    val isBackoffActive: Boolean,
)

enum class WorkerFailoverReason(val wireValue: String) {
    SELECTED_WORKER_UNAVAILABLE("selected_worker_unavailable"),
    SELECTED_WORKER_DISABLED("selected_worker_disabled"),
    SELECTED_WORKER_DEAD("selected_worker_dead"),
    WORKER_CONNECT_TIMEOUT("worker_connect_timeout"),
    WORKER_DNS_FAILED("worker_dns_failed"),
    WORKER_TLS_FAILED("worker_tls_failed"),
    WORKER_WEBSOCKET_FAILED("worker_websocket_failed"),
    WORKER_RUNTIME_FAILURE("worker_runtime_failure"),
    NO_ENABLED_WORKER("no_enabled_worker"),
    ALL_WORKERS_FAILED("all_workers_failed"),
    BACKOFF_ACTIVE("backoff_active"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): WorkerFailoverReason {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return UNKNOWN
            return entries.firstOrNull { it.wireValue == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}

sealed interface WorkerFailoverResult {
    data class Selected(val candidate: WorkerFailoverCandidate) : WorkerFailoverResult
    data class Error(val code: WorkerFailoverErrorCode, val reason: WorkerFailoverReason = WorkerFailoverReason.UNKNOWN) :
        WorkerFailoverResult
}

enum class WorkerFailoverErrorCode {
    NO_ENABLED_WORKER,
    SELECTED_WORKER_DISABLED,
    SELECTED_WORKER_NOT_FOUND,
    ALL_WORKERS_FAILED,
    ALL_WORKERS_IN_BACKOFF,
    WORKER_CONNECT_TIMEOUT,
    WORKER_DNS_FAILED,
    WORKER_TLS_FAILED,
    WORKER_WEBSOCKET_FAILED,
    WORKER_INVALID_CONFIG,
    WORKER_FAILOVER_CANCELLED,
    UNKNOWN,
}

data class WorkerConnectionAttemptState(
    val attemptId: String,
    val startedAt: Long,
    val attemptedWorkerIds: Set<String> = emptySet(),
    val failedWorkerIds: Set<String> = emptySet(),
    val currentWorkerId: String? = null,
    val lastFailureReason: WorkerFailoverReason? = null,
    val attemptCount: Int = 0,
    val maxAttempts: Int,
)

data class WorkerFailoverPayload(
    val enabled: Boolean,
    val selectedWorkerId: String,
    val candidates: List<WorkerFailoverCandidate>,
    val maxAttempts: Int,
    val primaryDomain: String,
    val skippedBackoffCount: Int = 0,
    val selectionStrategy: WorkerSelectionStrategy = WorkerSelectionStrategy.FAILOVER,
    val selectionReason: WorkerSelectionReason = WorkerSelectionReason.FAILOVER_ORDER,
    val roundRobinCursor: String? = null,
    val candidateCount: Int = candidates.size,
) {
    fun encodeCandidatesToken(): String {
        return candidates.joinToString("|") { candidate ->
            "${candidate.workerId}:${candidate.domain}"
        }
    }
}

object WorkerFailoverConfig {
    const val DEFAULT_MAX_WORKER_FAILOVER_ATTEMPTS = 3
}

data class WorkerFailoverRuntimeSnapshot(
    val selectedWorkerId: String = "",
    val selectedWorkerName: String = "",
    val runtimeWorkerId: String = "",
    val runtimeWorkerName: String = "",
    val lastSuccessfulWorkerId: String = "",
    val lastSuccessfulWorkerName: String = "",
    val lastFailedWorkerId: String = "",
    val lastFailedWorkerName: String = "",
    val failoverReason: WorkerFailoverReason = WorkerFailoverReason.UNKNOWN,
    val attemptCount: Int = 0,
    val failoverActive: Boolean = false,
    val enabledWorkersCount: Int = 0,
    val skippedBackoffCount: Int = 0,
    val selectionStrategy: WorkerSelectionStrategy = WorkerSelectionStrategy.FAILOVER,
    val selectionReason: WorkerSelectionReason = WorkerSelectionReason.UNKNOWN,
)
