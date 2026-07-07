package com.amurcanov.tgwsproxy.worker

import android.util.Log

object WorkerFailoverConfigBuilder {
    private const val TAG = "TgWsProxy"

    fun build(repository: WorkerPoolRepository): WorkerFailoverPayload? {
        val config = repository.getWorkerPoolConfig()
        if (!config.enabled) {
            return null
        }
        when (val result = repository.resolveSelectionForConnection()) {
            is WorkerSelectionResult.Success -> {
                if (result.candidates.isEmpty()) {
                    return null
                }
                val selectedId = config.selectedWorkerId?.takeIf { it.isNotBlank() }
                    ?: repository.getSelectedWorker()?.id.orEmpty()
                val maxAttempts = WorkerFailoverResolver.resolveMaxAttempts(result.strategy, result.candidates.size)
                return WorkerFailoverPayload(
                    enabled = true,
                    selectedWorkerId = selectedId,
                    candidates = result.candidates,
                    maxAttempts = maxAttempts,
                    primaryDomain = result.candidates.first().domain,
                    skippedBackoffCount = result.skippedBackoffCount,
                    selectionStrategy = result.strategy,
                    selectionReason = result.reason,
                    roundRobinCursor = config.roundRobinCursor,
                    candidateCount = result.candidates.size,
                )
            }
            is WorkerSelectionResult.Error -> {
                Log.w(
                    TAG,
                    "Worker selection failed for runtime: strategy=${result.strategy.name}, reason=${result.reason.wireValue}",
                )
                return null
            }
        }
    }

    fun preview(repository: WorkerPoolRepository): WorkerSelectionPreview {
        val config = repository.getWorkerPoolConfig()
        return WorkerSelectionResolver.preview(config, repository.getWorkers())
    }
}
