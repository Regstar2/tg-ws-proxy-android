package com.amurcanov.tgwsproxy.worker

import android.util.Log
import com.amurcanov.tgwsproxy.WorkerDomain

class WorkerPoolRepository(
    private val persistence: WorkerPoolPersistence,
) {
    fun getWorkerPoolConfig(): WorkerPoolConfig = persistence.loadConfig()

    fun saveWorkerPoolConfig(config: WorkerPoolConfig) {
        persistence.saveConfig(config)
    }

    fun getWorkers(): List<WorkerEndpoint> = persistence.loadWorkers()

    fun getWorker(id: String): WorkerEndpoint? = getWorkers().firstOrNull { it.id == id }

    fun addWorker(worker: WorkerEndpoint): Result<WorkerEndpoint> {
        val validation = WorkerEndpointValidator.validate(worker.name, worker.url)
        if (validation != null) {
            return Result.failure(WorkerPoolOperationException(validation.toPoolError()))
        }
        val normalized = worker.copy(
            url = WorkerDomain.normalize(worker.url).ifBlank { worker.url.trim() },
            updatedAt = System.currentTimeMillis(),
        )
        val workers = getWorkers().toMutableList()
        workers += normalized
        if (!persistWorkers(workers)) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.WORKER_POOL_STORAGE_ERROR))
        }
        Log.i(TAG, "Worker added: id=${normalized.id}, name=${normalized.name}, url=${WorkerUrlSanitizer.maskForLog(normalized.url)}")
        return Result.success(normalized)
    }

    fun updateWorker(worker: WorkerEndpoint): Result<WorkerEndpoint> {
        val validation = WorkerEndpointValidator.validate(worker.name, worker.url)
        if (validation != null) {
            return Result.failure(WorkerPoolOperationException(validation.toPoolError()))
        }
        val workers = getWorkers().toMutableList()
        val index = workers.indexOfFirst { it.id == worker.id }
        if (index < 0) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.SELECTED_WORKER_NOT_FOUND))
        }
        val updated = worker.copy(
            url = WorkerDomain.normalize(worker.url).ifBlank { worker.url.trim() },
            updatedAt = System.currentTimeMillis(),
        )
        workers[index] = updated
        if (!persistWorkers(workers)) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.WORKER_POOL_STORAGE_ERROR))
        }
        Log.i(TAG, "Worker updated: id=${updated.id}")
        return Result.success(updated)
    }

    fun removeWorker(id: String): Result<Unit> {
        val workers = getWorkers().toMutableList()
        val removed = workers.removeAll { it.id == id }
        if (!removed) {
            return Result.success(Unit)
        }
        val config = getWorkerPoolConfig()
        val newSelected = if (config.selectedWorkerId == id) {
            nextEnabledWorkerId(workers, excludeId = id)
        } else {
            config.selectedWorkerId
        }
        if (!persistWorkers(workers)) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.WORKER_POOL_STORAGE_ERROR))
        }
        if (newSelected != config.selectedWorkerId) {
            saveWorkerPoolConfig(config.copy(selectedWorkerId = newSelected))
            Log.i(TAG, "Selected worker changed: id=${newSelected ?: "NONE"}")
        }
        Log.i(TAG, "Worker deleted: id=$id")
        return Result.success(Unit)
    }

    fun setWorkerEnabled(id: String, enabled: Boolean): Result<WorkerEndpoint> {
        val workers = getWorkers().toMutableList()
        val index = workers.indexOfFirst { it.id == id }
        if (index < 0) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.SELECTED_WORKER_NOT_FOUND))
        }
        val current = workers[index]
        val now = System.currentTimeMillis()
        val updated = current.copy(
            enabled = enabled,
            state = if (enabled) {
                if (current.state == WorkerHealthState.DISABLED) WorkerHealthState.UNKNOWN else current.state
            } else {
                WorkerHealthState.DISABLED
            },
            updatedAt = now,
        )
        workers[index] = updated
        val config = getWorkerPoolConfig()
        var newSelected = config.selectedWorkerId
        if (!enabled && config.selectedWorkerId == id) {
            newSelected = nextEnabledWorkerId(workers, excludeId = id)
        }
        if (!persistWorkers(workers)) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.WORKER_POOL_STORAGE_ERROR))
        }
        if (newSelected != config.selectedWorkerId) {
            saveWorkerPoolConfig(config.copy(selectedWorkerId = newSelected))
            Log.i(TAG, "Selected worker changed: id=${newSelected ?: "NONE"}")
        }
        Log.i(TAG, if (enabled) "Worker enabled: id=$id" else "Worker disabled: id=$id")
        return Result.success(updated)
    }

    fun selectWorker(id: String): Result<WorkerEndpoint> {
        val worker = getWorker(id)
            ?: return Result.failure(WorkerPoolOperationException(WorkerPoolError.SELECTED_WORKER_NOT_FOUND))
        if (!worker.enabled) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.NO_ENABLED_WORKER))
        }
        val config = getWorkerPoolConfig()
        saveWorkerPoolConfig(config.copy(selectedWorkerId = id))
        Log.i(TAG, "Selected worker changed: id=$id")
        return Result.success(worker)
    }

    fun getSelectedWorker(): WorkerEndpoint? {
        val config = getWorkerPoolConfig()
        val workers = getWorkers()
        val selected = config.selectedWorkerId?.let { id -> workers.firstOrNull { it.id == id && it.enabled } }
        if (selected != null) {
            return selected
        }
        return workers.firstOrNull { it.enabled }
    }

    fun setPoolEnabled(enabled: Boolean) {
        val config = getWorkerPoolConfig()
        saveWorkerPoolConfig(config.copy(enabled = enabled))
    }

    fun setSelectionStrategy(strategy: WorkerSelectionStrategy) {
        val config = getWorkerPoolConfig()
        if (config.selectionStrategy == strategy) {
            return
        }
        saveWorkerPoolConfig(config.copy(selectionStrategy = strategy))
        Log.i(TAG, "Worker selection strategy changed: ${strategy.name}")
    }

    @Synchronized
    fun resolveSelectionForConnection(): WorkerSelectionResult {
        val config = getWorkerPoolConfig()
        val workers = getWorkers()
        val result = WorkerSelectionResolver.resolveCandidates(
            config = config,
            workers = workers,
            advanceRoundRobin = true,
        )
        if (result is WorkerSelectionResult.Success &&
            config.selectionStrategy == WorkerSelectionStrategy.ROUND_ROBIN &&
            result.roundRobinNextCursor != null &&
            result.roundRobinNextCursor != config.roundRobinCursor
        ) {
            saveWorkerPoolConfig(config.copy(roundRobinCursor = result.roundRobinNextCursor))
        }
        return result
    }

    fun previewSelection(): WorkerSelectionPreview {
        return WorkerSelectionResolver.preview(getWorkerPoolConfig(), getWorkers())
    }

    fun isMigrationCompleted(): Boolean = persistence.isMigrationCompleted()

    fun markMigrationCompleted() {
        persistence.markMigrationCompleted()
    }

    fun applyHealthUpdate(worker: WorkerEndpoint): Result<WorkerEndpoint> {
        val workers = getWorkers().toMutableList()
        val index = workers.indexOfFirst { it.id == worker.id }
        if (index < 0) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.SELECTED_WORKER_NOT_FOUND))
        }
        workers[index] = worker
        if (!persistWorkers(workers)) {
            return Result.failure(WorkerPoolOperationException(WorkerPoolError.WORKER_POOL_STORAGE_ERROR))
        }
        return Result.success(worker)
    }

    fun restoreSelectedWorkerId(selectedWorkerId: String?) {
        val config = getWorkerPoolConfig()
        if (config.selectedWorkerId == selectedWorkerId) {
            return
        }
        saveWorkerPoolConfig(config.copy(selectedWorkerId = selectedWorkerId))
        Log.w(TAG, "Selected worker restored after health check: id=${selectedWorkerId ?: "NONE"}")
    }

    fun buildReportSnapshot(legacyWorkerDomain: String, maskDomains: Boolean): WorkerPoolReportSnapshot {
        val workers = getWorkers()
        val selected = getSelectedWorker()
        return WorkerPoolReportSnapshot(
            enabled = getWorkerPoolConfig().enabled,
            workers = workers,
            selectedWorker = selected,
            legacyWorkerDomain = legacyWorkerDomain,
            maskDomains = maskDomains,
        )
    }

    private fun nextEnabledWorkerId(workers: List<WorkerEndpoint>, excludeId: String? = null): String? {
        return workers.firstOrNull { it.enabled && it.id != excludeId }?.id
    }

    private fun persistWorkers(workers: List<WorkerEndpoint>): Boolean {
        return runCatching {
            persistence.saveWorkers(workers)
        }.isSuccess
    }

    private companion object {
        const val TAG = "TgWsProxy"
    }
}

class WorkerPoolOperationException(val code: WorkerPoolError) : Exception(code.name)

private fun WorkerValidationError.toPoolError(): WorkerPoolError {
    return when (this) {
        WorkerValidationError.EMPTY_NAME -> WorkerPoolError.INVALID_WORKER_URL
        WorkerValidationError.EMPTY_URL -> WorkerPoolError.INVALID_WORKER_URL
        WorkerValidationError.INVALID_URL -> WorkerPoolError.INVALID_WORKER_URL
    }
}
