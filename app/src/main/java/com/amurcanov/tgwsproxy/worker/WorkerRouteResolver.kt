package com.amurcanov.tgwsproxy.worker

import android.util.Log
import com.amurcanov.tgwsproxy.WorkerDomain

object WorkerRouteResolver {
    private const val TAG = "TgWsProxy"

    fun resolve(
        repository: WorkerPoolRepository,
        legacyWorkerDomain: String,
    ): WorkerRouteResolution {
        val config = repository.getWorkerPoolConfig()
        if (!config.enabled) {
            val domain = WorkerDomain.normalize(legacyWorkerDomain)
            return if (domain.isBlank()) {
                WorkerRouteResolution.Error(WorkerPoolError.NO_WORKER_CONFIGURED)
            } else {
                WorkerRouteResolution.Legacy(domain)
            }
        }

        val selected = repository.getSelectedWorker()
        if (selected == null) {
            Log.w(TAG, "Worker route config error: no enabled worker")
            return WorkerRouteResolution.Error(WorkerPoolError.NO_ENABLED_WORKER)
        }
        val domain = selected.normalizedDomain()
        if (domain.isBlank()) {
            return WorkerRouteResolution.Error(WorkerPoolError.INVALID_WORKER_URL)
        }
        Log.i(
            TAG,
            "Worker route using selected worker: id=${selected.id}, name=${selected.name}, url=${WorkerUrlSanitizer.maskForLog(selected.url)}",
        )
        return WorkerRouteResolution.Pool(domain, selected)
    }

    fun resolveDomain(
        repository: WorkerPoolRepository,
        legacyWorkerDomain: String,
    ): String {
        return when (val resolution = resolve(repository, legacyWorkerDomain)) {
            is WorkerRouteResolution.Legacy -> resolution.domain
            is WorkerRouteResolution.Pool -> resolution.domain
            is WorkerRouteResolution.Error -> ""
        }
    }
}

object WorkerRuntimeTruth {
    fun enrichRouteState(
        routeState: com.amurcanov.tgwsproxy.RouteRuntimeState,
        repository: WorkerPoolRepository,
        legacyWorkerDomain: String,
        maskDomains: Boolean,
    ): com.amurcanov.tgwsproxy.RouteRuntimeState {
        val config = repository.getWorkerPoolConfig()
        if (!config.enabled) {
            return routeState
        }
        val selected = repository.getSelectedWorker()
        val runtimeWorker = routeState.currentWorkerId.takeIf { it.isNotBlank() }
            ?.let { id -> repository.getWorker(id) }
        val selectedWorker = selected
        return routeState.copy(
            selectedWorkerId = selectedWorker?.id.orEmpty(),
            selectedWorkerName = selectedWorker?.name.orEmpty(),
            currentWorkerId = runtimeWorker?.id ?: routeState.currentWorkerId,
            currentWorkerName = runtimeWorker?.name ?: routeState.currentWorkerName,
            currentWorkerUrlMasked = (runtimeWorker ?: selectedWorker)?.let { worker ->
                WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains)
            }.orEmpty().ifBlank { routeState.currentWorkerUrlMasked },
            currentWorkerState = (runtimeWorker ?: selectedWorker)?.state ?: routeState.currentWorkerState,
            currentWorkerDomain = (runtimeWorker ?: selectedWorker)?.normalizedDomain()
                ?.ifBlank { routeState.currentWorkerDomain }
                ?: routeState.currentWorkerDomain,
            lastSuccessfulWorkerName = routeState.lastSuccessfulWorkerId.takeIf { it.isNotBlank() }
                ?.let { id -> repository.getWorker(id)?.name }.orEmpty(),
            lastFailedWorkerName = routeState.lastFailedWorkerId.takeIf { it.isNotBlank() }
                ?.let { id -> repository.getWorker(id)?.name }.orEmpty(),
        )
    }

    fun buildFailoverSnapshot(
        routeState: com.amurcanov.tgwsproxy.RouteRuntimeState,
        repository: WorkerPoolRepository,
        legacyWorkerDomain: String,
        maskDomains: Boolean,
    ): WorkerFailoverRuntimeSnapshot {
        val config = repository.getWorkerPoolConfig()
        val enriched = enrichRouteState(routeState, repository, legacyWorkerDomain, maskDomains)
        val preview = repository.previewSelection()
        return WorkerFailoverRuntimeSnapshot(
            selectedWorkerId = enriched.selectedWorkerId,
            selectedWorkerName = enriched.selectedWorkerName,
            runtimeWorkerId = enriched.currentWorkerId,
            runtimeWorkerName = enriched.currentWorkerName,
            lastSuccessfulWorkerId = enriched.lastSuccessfulWorkerId,
            lastSuccessfulWorkerName = enriched.lastSuccessfulWorkerName,
            lastFailedWorkerId = enriched.lastFailedWorkerId,
            lastFailedWorkerName = enriched.lastFailedWorkerName,
            failoverReason = WorkerFailoverReason.fromWire(enriched.workerFailoverReason),
            attemptCount = enriched.workerFailoverAttemptCount,
            failoverActive = enriched.workerFailoverActive,
            enabledWorkersCount = repository.getWorkers().count { it.enabled },
            skippedBackoffCount = enriched.workerFailoverSkippedBackoff,
            selectionStrategy = if (enriched.workerSelectionStrategy.isNotBlank()) {
                WorkerSelectionStrategy.fromPref(enriched.workerSelectionStrategy)
            } else {
                config.selectionStrategy
            },
            selectionReason = if (enriched.workerSelectionReason.isNotBlank()) {
                WorkerSelectionReason.fromWire(enriched.workerSelectionReason)
            } else {
                preview.reason
            },
        )
    }
}
