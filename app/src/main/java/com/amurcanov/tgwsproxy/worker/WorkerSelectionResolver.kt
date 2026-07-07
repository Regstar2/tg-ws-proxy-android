package com.amurcanov.tgwsproxy.worker

import android.util.Log

object WorkerSelectionResolver {
    private const val TAG = "TgWsProxy"

    fun resolveCandidates(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long = System.currentTimeMillis(),
        advanceRoundRobin: Boolean = false,
    ): WorkerSelectionResult {
        Log.i(TAG, "Worker selection started: strategy=${config.selectionStrategy.name}")
        return when (config.selectionStrategy) {
            WorkerSelectionStrategy.MANUAL -> resolveManual(config, workers, nowMs)
            WorkerSelectionStrategy.PRIORITY -> resolvePriority(config, workers, nowMs)
            WorkerSelectionStrategy.FAILOVER -> resolveFailover(config, workers, nowMs)
            WorkerSelectionStrategy.ROUND_ROBIN -> resolveRoundRobin(config, workers, nowMs, advanceRoundRobin)
            WorkerSelectionStrategy.LOWEST_LATENCY -> resolveLowestLatency(config, workers, nowMs)
        }
    }

    fun preview(config: WorkerPoolConfig, workers: List<WorkerEndpoint>, nowMs: Long = System.currentTimeMillis()): WorkerSelectionPreview {
        val result = resolveCandidates(config, workers, nowMs, advanceRoundRobin = false)
        return when (result) {
            is WorkerSelectionResult.Success -> WorkerSelectionPreview(
                strategy = result.strategy,
                reason = result.reason,
                candidateCount = result.candidates.size,
                candidates = result.candidates,
                roundRobinCursor = config.roundRobinCursor,
                lowestLatencyMaxAgeMs = config.lowestLatencyMaxAgeMs,
            )
            is WorkerSelectionResult.Error -> WorkerSelectionPreview(
                strategy = result.strategy,
                reason = result.reason,
                candidateCount = 0,
                candidates = emptyList(),
                roundRobinCursor = config.roundRobinCursor,
                lowestLatencyMaxAgeMs = config.lowestLatencyMaxAgeMs,
            )
        }
    }

    private fun resolveManual(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long,
    ): WorkerSelectionResult {
        val selectedId = config.selectedWorkerId?.takeIf { it.isNotBlank() }
        if (selectedId == null) {
            return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_NOT_FOUND,
                WorkerSelectionReason.SELECTED_WORKER_NOT_FOUND,
            )
        }
        val selected = workers.firstOrNull { it.id == selectedId }
            ?: return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_NOT_FOUND,
                WorkerSelectionReason.SELECTED_WORKER_NOT_FOUND,
            )
        if (!selected.enabled) {
            return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_DISABLED,
                WorkerSelectionReason.SELECTED_WORKER_DISABLED,
            )
        }
        if (selected.state == WorkerHealthState.DEAD &&
            WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(selected, selected.lastCheckedAt, nowMs)
        ) {
            return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_UNAVAILABLE,
                WorkerSelectionReason.SELECTED_WORKER_UNAVAILABLE,
            )
        }
        if (selected.normalizedDomain().isBlank()) {
            return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_UNAVAILABLE,
                WorkerSelectionReason.SELECTED_WORKER_UNAVAILABLE,
            )
        }
        val mapped = WorkerCandidateMapper.mapOrdered(listOf(selected), selectedId, nowMs)
        if (mapped.candidates.isEmpty()) {
            return error(
                WorkerSelectionStrategy.MANUAL,
                WorkerSelectionErrorCode.SELECTED_WORKER_UNAVAILABLE,
                WorkerSelectionReason.SELECTED_WORKER_UNAVAILABLE,
            )
        }
        return success(
            mapped.candidates,
            WorkerSelectionStrategy.MANUAL,
            WorkerSelectionReason.SELECTED_WORKER,
            mapped.skippedBackoffCount,
        )
    }

    private fun resolvePriority(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long,
    ): WorkerSelectionResult {
        val eligible = filterEligible(workers, config, nowMs)
        if (eligible.isEmpty()) {
            return noEnabledOrBackoff(WorkerSelectionStrategy.PRIORITY, workers, config, nowMs)
        }
        val ordered = eligible.sortedWith(
            compareByDescending<WorkerEndpoint> { it.priority }
                .thenBy { WorkerCandidateMapper.stateRank(it.state) }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        return mapSuccess(ordered, config.selectedWorkerId, WorkerSelectionStrategy.PRIORITY, WorkerSelectionReason.HIGHEST_PRIORITY, nowMs)
    }

    private fun resolveFailover(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long,
    ): WorkerSelectionResult {
        val enabled = workers.filter { it.enabled }
        if (enabled.isEmpty()) {
            return error(
                WorkerSelectionStrategy.FAILOVER,
                WorkerSelectionErrorCode.NO_ENABLED_WORKER,
                WorkerSelectionReason.NO_ENABLED_WORKER,
            )
        }
        val selectedId = config.selectedWorkerId?.takeIf { it.isNotBlank() }
        val selected = selectedId?.let { id -> enabled.firstOrNull { it.id == id } }
        val others = enabled.filter { it.id != selected?.id }
        val ordered = buildList {
            selected?.let { add(it) }
            addAll(
                others.sortedWith(
                    compareBy<WorkerEndpoint>({ WorkerCandidateMapper.stateRank(it.state) })
                        .thenBy { it.priority }
                        .thenBy { it.createdAt }
                        .thenBy { it.id },
                ),
            )
        }.filter { WorkerCandidateMapper.isEligibleForAttempt(it, config, nowMs) }
        if (ordered.isEmpty()) {
            return noEnabledOrBackoff(WorkerSelectionStrategy.FAILOVER, workers, config, nowMs)
        }
        return mapSuccess(ordered, selectedId, WorkerSelectionStrategy.FAILOVER, WorkerSelectionReason.FAILOVER_ORDER, nowMs)
    }

    private fun resolveRoundRobin(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long,
        advanceRoundRobin: Boolean,
    ): WorkerSelectionResult {
        val eligible = filterEligible(workers, config, nowMs)
        if (eligible.isEmpty()) {
            return noEnabledOrBackoff(WorkerSelectionStrategy.ROUND_ROBIN, workers, config, nowMs)
        }
        val sorted = eligible.sortedWith(WorkerCandidateMapper.stableOrderComparator())
        val cursorId = config.roundRobinCursor?.takeIf { it.isNotBlank() }
        var startIdx = cursorId?.let { id -> sorted.indexOfFirst { it.id == id } } ?: -1
        var reason = WorkerSelectionReason.ROUND_ROBIN
        if (startIdx < 0) {
            startIdx = 0
            if (cursorId != null) {
                reason = WorkerSelectionReason.ROUND_ROBIN_CURSOR_INVALID
                Log.i(TAG, "Round-robin cursor invalid: cursor=$cursorId")
            }
        }
        val rotated = sorted.drop(startIdx) + sorted.take(startIdx)
        val nextCursor = if (advanceRoundRobin && sorted.isNotEmpty()) {
            val nextIdx = (startIdx + 1) % sorted.size
            sorted[nextIdx].id
        } else {
            cursorId ?: sorted.firstOrNull()?.id
        }
        val mapped = WorkerCandidateMapper.mapOrdered(rotated, config.selectedWorkerId, nowMs)
        if (mapped.candidates.isEmpty()) {
            return error(
                WorkerSelectionStrategy.ROUND_ROBIN,
                WorkerSelectionErrorCode.ALL_WORKERS_IN_BACKOFF,
                WorkerSelectionReason.ALL_WORKERS_IN_BACKOFF,
            )
        }
        if (advanceRoundRobin && nextCursor != null) {
            Log.i(TAG, "Round-robin cursor advanced: old=${cursorId ?: "NONE"}, new=$nextCursor")
        }
        return WorkerSelectionResult.Success(
            candidates = mapped.candidates,
            strategy = WorkerSelectionStrategy.ROUND_ROBIN,
            reason = reason,
            skippedBackoffCount = mapped.skippedBackoffCount,
            roundRobinNextCursor = nextCursor,
        )
    }

    private fun resolveLowestLatency(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        nowMs: Long,
    ): WorkerSelectionResult {
        val eligible = filterEligible(workers, config, nowMs)
        if (eligible.isEmpty()) {
            return noEnabledOrBackoff(WorkerSelectionStrategy.LOWEST_LATENCY, workers, config, nowMs)
        }
        val maxAge = config.lowestLatencyMaxAgeMs.coerceAtLeast(0L)
        val withValidLatency = eligible.filter { worker ->
            hasFreshLatency(worker, nowMs, maxAge)
        }
        if (withValidLatency.isEmpty()) {
            Log.i(
                TAG,
                "Worker selection fallback: strategy=LOWEST_LATENCY, reason=no_latency_data, fallback=FAILOVER",
            )
            val fallback = resolveFailover(config, workers, nowMs)
            return when (fallback) {
                is WorkerSelectionResult.Success -> fallback.copy(
                    strategy = WorkerSelectionStrategy.LOWEST_LATENCY,
                    reason = WorkerSelectionReason.NO_LATENCY_DATA,
                    fallbackFromStrategy = WorkerSelectionStrategy.FAILOVER,
                )
                is WorkerSelectionResult.Error -> fallback.copy(
                    strategy = WorkerSelectionStrategy.LOWEST_LATENCY,
                    reason = WorkerSelectionReason.NO_LATENCY_DATA,
                )
            }
        }
        val ordered = withValidLatency.sortedWith(
            compareBy<WorkerEndpoint>({ WorkerCandidateMapper.stateRank(it.state) })
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                .thenBy { it.priority }
                .thenBy { it.id },
        )
        val withoutLatency = eligible.filter { worker -> !hasFreshLatency(worker, nowMs, maxAge) }
            .sortedWith(
                compareBy<WorkerEndpoint>({ WorkerCandidateMapper.stateRank(it.state) })
                    .thenBy { it.priority }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
        val fullOrder = ordered + withoutLatency
        return mapSuccess(
            fullOrder,
            config.selectedWorkerId,
            WorkerSelectionStrategy.LOWEST_LATENCY,
            WorkerSelectionReason.LOWEST_CACHED_LATENCY,
            nowMs,
        )
    }

    private fun hasFreshLatency(worker: WorkerEndpoint, nowMs: Long, maxAgeMs: Long): Boolean {
        val latency = worker.latencyMs ?: return false
        if (latency < 0) return false
        val anchor = worker.lastCheckedAt ?: worker.lastSuccessAt ?: return false
        if (maxAgeMs > 0L && nowMs - anchor > maxAgeMs) return false
        return true
    }

    private fun filterEligible(
        workers: List<WorkerEndpoint>,
        config: WorkerPoolConfig,
        nowMs: Long,
    ): List<WorkerEndpoint> {
        return workers.filter { WorkerCandidateMapper.isEligibleForAttempt(it, config, nowMs) }
    }

    private fun mapSuccess(
        ordered: List<WorkerEndpoint>,
        selectedWorkerId: String?,
        strategy: WorkerSelectionStrategy,
        reason: WorkerSelectionReason,
        nowMs: Long,
    ): WorkerSelectionResult {
        val mapped = WorkerCandidateMapper.mapOrdered(ordered, selectedWorkerId, nowMs)
        if (mapped.candidates.isEmpty()) {
            return error(strategy, WorkerSelectionErrorCode.ALL_WORKERS_IN_BACKOFF, WorkerSelectionReason.ALL_WORKERS_IN_BACKOFF)
        }
        Log.i(
            TAG,
            "Worker selection candidates resolved: strategy=${strategy.name}, count=${mapped.candidates.size}, reason=${reason.wireValue}",
        )
        return success(mapped.candidates, strategy, reason, mapped.skippedBackoffCount)
    }

    private fun noEnabledOrBackoff(
        strategy: WorkerSelectionStrategy,
        workers: List<WorkerEndpoint>,
        config: WorkerPoolConfig,
        nowMs: Long,
    ): WorkerSelectionResult {
        val enabledCount = workers.count { it.enabled }
        if (enabledCount == 0) {
            return error(strategy, WorkerSelectionErrorCode.NO_ENABLED_WORKER, WorkerSelectionReason.NO_ENABLED_WORKER)
        }
        return error(strategy, WorkerSelectionErrorCode.ALL_WORKERS_IN_BACKOFF, WorkerSelectionReason.ALL_WORKERS_IN_BACKOFF)
    }

    private fun success(
        candidates: List<WorkerFailoverCandidate>,
        strategy: WorkerSelectionStrategy,
        reason: WorkerSelectionReason,
        skippedBackoff: Int,
    ): WorkerSelectionResult.Success {
        Log.i(
            TAG,
            "Worker selected: strategy=${strategy.name}, workerId=${candidates.first().workerId}, reason=${reason.wireValue}",
        )
        return WorkerSelectionResult.Success(
            candidates = candidates,
            strategy = strategy,
            reason = reason,
            skippedBackoffCount = skippedBackoff,
        )
    }

    private fun error(
        strategy: WorkerSelectionStrategy,
        code: WorkerSelectionErrorCode,
        reason: WorkerSelectionReason,
    ): WorkerSelectionResult.Error {
        Log.w(TAG, "Worker selection failed: strategy=${strategy.name}, reason=${reason.wireValue}")
        return WorkerSelectionResult.Error(code, reason, strategy)
    }
}
