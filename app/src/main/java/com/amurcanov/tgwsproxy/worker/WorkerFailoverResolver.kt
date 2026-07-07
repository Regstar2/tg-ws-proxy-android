package com.amurcanov.tgwsproxy.worker

object WorkerFailoverResolver {
    fun buildOrderedCandidates(
        workers: List<WorkerEndpoint>,
        selectedWorkerId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<WorkerFailoverCandidate>, Int> {
        val config = WorkerPoolConfig(
            selectedWorkerId = selectedWorkerId,
            selectionStrategy = WorkerSelectionStrategy.FAILOVER,
        )
        return when (val result = WorkerSelectionResolver.resolveCandidates(config, workers, nowMs)) {
            is WorkerSelectionResult.Success -> result.candidates to result.skippedBackoffCount
            is WorkerSelectionResult.Error -> emptyList<WorkerFailoverCandidate>() to 0
        }
    }

    fun nextCandidate(
        candidates: List<WorkerFailoverCandidate>,
        failedWorkerIds: Set<String>,
    ): WorkerFailoverResult {
        if (candidates.isEmpty()) {
            return WorkerFailoverResult.Error(
                WorkerFailoverErrorCode.NO_ENABLED_WORKER,
                WorkerFailoverReason.NO_ENABLED_WORKER,
            )
        }
        val next = candidates.firstOrNull { it.workerId !in failedWorkerIds }
            ?: return WorkerFailoverResult.Error(
                WorkerFailoverErrorCode.ALL_WORKERS_FAILED,
                WorkerFailoverReason.ALL_WORKERS_FAILED,
            )
        return WorkerFailoverResult.Selected(next)
    }

    fun resolveMaxAttempts(
        strategy: WorkerSelectionStrategy,
        candidateCount: Int,
    ): Int {
        if (candidateCount <= 0) return 0
        return when (strategy) {
            WorkerSelectionStrategy.MANUAL -> 1
            else -> minOf(candidateCount, WorkerFailoverConfig.DEFAULT_MAX_WORKER_FAILOVER_ATTEMPTS)
        }
    }

    fun resolveMaxAttempts(candidateCount: Int): Int {
        return resolveMaxAttempts(WorkerSelectionStrategy.FAILOVER, candidateCount)
    }
}
