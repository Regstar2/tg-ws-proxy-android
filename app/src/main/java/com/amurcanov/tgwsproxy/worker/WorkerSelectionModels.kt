package com.amurcanov.tgwsproxy.worker

enum class WorkerSelectionStrategy(val prefValue: String) {
    MANUAL("manual"),
    PRIORITY("priority"),
    FAILOVER("failover"),
    ROUND_ROBIN("round_robin"),
    LOWEST_LATENCY("lowest_latency"),
    ;

    companion object {
        fun fromPref(value: String?): WorkerSelectionStrategy {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return FAILOVER
            return entries.firstOrNull { it.prefValue == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: FAILOVER
        }
    }
}

enum class WorkerSelectionReason(val wireValue: String) {
    SELECTED_WORKER("selected_worker"),
    HIGHEST_PRIORITY("highest_priority"),
    FAILOVER_ORDER("failover_order"),
    ROUND_ROBIN("round_robin"),
    LOWEST_CACHED_LATENCY("lowest_cached_latency"),
    NO_LATENCY_DATA("no_latency_data"),
    NO_ENABLED_WORKER("no_enabled_worker"),
    SELECTED_WORKER_NOT_FOUND("selected_worker_not_found"),
    SELECTED_WORKER_DISABLED("selected_worker_disabled"),
    SELECTED_WORKER_UNAVAILABLE("selected_worker_unavailable"),
    ALL_WORKERS_IN_BACKOFF("all_workers_in_backoff"),
    LOWEST_LATENCY_DATA_EXPIRED("lowest_latency_data_expired"),
    ROUND_ROBIN_CURSOR_INVALID("round_robin_cursor_invalid"),
    INVALID_SELECTION_STRATEGY("invalid_selection_strategy"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): WorkerSelectionReason {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return UNKNOWN
            return entries.firstOrNull { it.wireValue == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}

enum class WorkerSelectionErrorCode {
    NO_ENABLED_WORKER,
    SELECTED_WORKER_NOT_FOUND,
    SELECTED_WORKER_DISABLED,
    SELECTED_WORKER_UNAVAILABLE,
    ALL_WORKERS_IN_BACKOFF,
    NO_LATENCY_DATA,
    LOWEST_LATENCY_DATA_EXPIRED,
    ROUND_ROBIN_CURSOR_INVALID,
    INVALID_SELECTION_STRATEGY,
    UNKNOWN_SELECTION_ERROR,
}

sealed interface WorkerSelectionResult {
    data class Success(
        val candidates: List<WorkerFailoverCandidate>,
        val strategy: WorkerSelectionStrategy,
        val reason: WorkerSelectionReason,
        val skippedBackoffCount: Int = 0,
        val roundRobinNextCursor: String? = null,
        val fallbackFromStrategy: WorkerSelectionStrategy? = null,
    ) : WorkerSelectionResult

    data class Error(
        val code: WorkerSelectionErrorCode,
        val reason: WorkerSelectionReason,
        val strategy: WorkerSelectionStrategy,
    ) : WorkerSelectionResult
}

object WorkerSelectionConfig {
    const val DEFAULT_LOWEST_LATENCY_MAX_AGE_MS = 10 * 60 * 1000L
}

data class WorkerSelectionPreview(
    val strategy: WorkerSelectionStrategy,
    val reason: WorkerSelectionReason,
    val candidateCount: Int,
    val candidates: List<WorkerFailoverCandidate>,
    val roundRobinCursor: String? = null,
    val lowestLatencyMaxAgeMs: Long = WorkerSelectionConfig.DEFAULT_LOWEST_LATENCY_MAX_AGE_MS,
)
