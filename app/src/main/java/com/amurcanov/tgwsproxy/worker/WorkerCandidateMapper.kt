package com.amurcanov.tgwsproxy.worker

internal object WorkerCandidateMapper {
    data class MapResult(
        val candidates: List<WorkerFailoverCandidate>,
        val skippedBackoffCount: Int,
    )

    fun mapOrdered(
        ordered: List<WorkerEndpoint>,
        selectedWorkerId: String?,
        nowMs: Long,
    ): MapResult {
        var skippedBackoff = 0
        val candidates = ordered.mapNotNull { worker ->
            val backoffActive = worker.state == WorkerHealthState.DEAD &&
                WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(worker, worker.lastCheckedAt, nowMs)
            if (backoffActive) {
                skippedBackoff += 1
                return@mapNotNull null
            }
            val domain = worker.normalizedDomain()
            if (domain.isBlank()) {
                return@mapNotNull null
            }
            WorkerFailoverCandidate(
                workerId = worker.id,
                workerName = worker.name,
                url = worker.url,
                domain = domain,
                state = worker.state,
                enabled = worker.enabled,
                priority = worker.priority,
                latencyMs = worker.latencyMs,
                failureCount = worker.failureCount,
                lastSuccessAt = worker.lastSuccessAt,
                lastFailureAt = worker.lastFailureAt,
                isSelectedWorker = worker.id == selectedWorkerId,
                isBackoffActive = false,
            )
        }
        return MapResult(candidates, skippedBackoff)
    }

    fun isEligibleForAttempt(
        worker: WorkerEndpoint,
        config: WorkerPoolConfig,
        nowMs: Long,
    ): Boolean {
        if (!worker.enabled) return false
        if (!config.allowDegradedWorkers && worker.state == WorkerHealthState.DEGRADED) {
            return false
        }
        if (worker.state == WorkerHealthState.DEAD &&
            WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(worker, worker.lastCheckedAt, nowMs)
        ) {
            return false
        }
        return worker.normalizedDomain().isNotBlank()
    }

    fun stateRank(state: WorkerHealthState): Int = when (state) {
        WorkerHealthState.HEALTHY -> 0
        WorkerHealthState.UNKNOWN -> 1
        WorkerHealthState.DEGRADED -> 2
        WorkerHealthState.DEAD -> 3
        WorkerHealthState.DISABLED -> 4
    }

    fun stableOrderComparator(): Comparator<WorkerEndpoint> {
        return compareBy<WorkerEndpoint>({ it.createdAt }, { it.id })
    }
}
