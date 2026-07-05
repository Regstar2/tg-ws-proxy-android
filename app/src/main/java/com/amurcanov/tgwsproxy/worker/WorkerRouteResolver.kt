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
        val worker = repository.getSelectedWorker() ?: return routeState.copy(
            currentWorkerId = "",
            currentWorkerName = "",
            currentWorkerUrlMasked = "",
            currentWorkerState = WorkerHealthState.UNKNOWN,
        )
        return routeState.copy(
            currentWorkerId = worker.id,
            currentWorkerName = worker.name,
            currentWorkerUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            currentWorkerState = worker.state,
            currentWorkerDomain = worker.normalizedDomain().ifBlank { routeState.currentWorkerDomain },
        )
    }
}
