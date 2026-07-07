package com.amurcanov.tgwsproxy.worker

import android.content.Context
import android.util.Log
import com.amurcanov.tgwsproxy.AppLogCategory
import com.amurcanov.tgwsproxy.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkerHealthRepository(
    private val poolRepository: WorkerPoolRepository,
    private val runner: WorkerHealthCheckRunner = WorkerHealthCheckRunner(),
) {
    private val lastResults = linkedMapOf<String, WorkerHealthCheckResult>()
    private var lastAllCheckAtMs: Long? = null
    private val checkMutex = Mutex()

    fun getLastHealthResults(): List<WorkerHealthCheckResult> = lastResults.values.toList()

    fun getLastResult(workerId: String): WorkerHealthCheckResult? = lastResults[workerId]

    fun getLastAllCheckAtMs(): Long? = lastAllCheckAtMs

    fun clearHealthResults() {
        lastResults.clear()
        lastAllCheckAtMs = null
    }

    suspend fun checkWorker(
        context: Context,
        workerId: String,
        maskDomains: Boolean = true,
        force: Boolean = false,
    ): WorkerHealthCheckResult = checkMutex.withLock {
        val worker = poolRepository.getWorker(workerId)
            ?: return missingWorkerResult(workerId)
        if (!force && !worker.enabled) {
            val skipped = runner.run(context, worker, maskDomains)
            lastResults[workerId] = skipped
            return skipped
        }
        if (!force && WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(worker, lastResults[workerId]?.finishedAt)) {
            return lastResults[workerId] ?: runner.run(context, worker, maskDomains)
        }
        val result = runner.run(context, worker, maskDomains)
        applyResultToWorker(result)
        lastResults[workerId] = result
        result
    }

    suspend fun checkAllEnabledWorkers(
        context: Context,
        maskDomains: Boolean = true,
        force: Boolean = true,
    ): WorkerHealthCheckSummary = checkMutex.withLock {
        val workers = poolRepository.getWorkers()
        val enabled = workers.filter { it.enabled }
        WorkerHealthCheckLogger.allStarted(enabled.size)
        if (enabled.isEmpty()) {
            lastAllCheckAtMs = System.currentTimeMillis()
            return WorkerHealthCheckSummary(
                results = emptyList(),
                healthyCount = 0,
                degradedCount = 0,
                deadCount = 0,
                skippedCount = workers.count { !it.enabled },
                finishedAtMs = lastAllCheckAtMs ?: System.currentTimeMillis(),
            )
        }
        val results = mutableListOf<WorkerHealthCheckResult>()
        enabled.forEach { worker ->
            val result = if (!force && WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(
                    worker,
                    lastResults[worker.id]?.finishedAt,
                )
            ) {
                lastResults[worker.id] ?: runner.run(context, worker, maskDomains)
            } else {
                val checked = runner.run(context, worker, maskDomains)
                applyResultToWorker(checked)
                lastResults[worker.id] = checked
                checked
            }
            results += result
        }
        workers.filter { !it.enabled }.forEach { disabled ->
            val skipped = runner.run(context, disabled, maskDomains)
            lastResults[disabled.id] = skipped
        }
        val finishedAt = System.currentTimeMillis()
        lastAllCheckAtMs = finishedAt
        val updatedWorkers = poolRepository.getWorkers()
        val summary = WorkerHealthCheckSummary(
            results = results,
            healthyCount = updatedWorkers.count { it.enabled && it.state == WorkerHealthState.HEALTHY },
            degradedCount = updatedWorkers.count { it.enabled && it.state == WorkerHealthState.DEGRADED },
            deadCount = updatedWorkers.count { it.enabled && it.state == WorkerHealthState.DEAD },
            skippedCount = updatedWorkers.count { !it.enabled },
            finishedAtMs = finishedAt,
        )
        WorkerHealthCheckLogger.allFinished(
            summary.healthyCount,
            summary.degradedCount,
            summary.deadCount,
            summary.skippedCount,
        )
        AppLogger.i(
            context,
            AppLogCategory.NETWORK,
            "Worker health check all finished",
            mapOf(
                "healthy" to summary.healthyCount.toString(),
                "degraded" to summary.degradedCount.toString(),
                "dead" to summary.deadCount.toString(),
                "skipped" to summary.skippedCount.toString(),
            ),
        )
        summary
    }

    private fun applyResultToWorker(result: WorkerHealthCheckResult) {
        val worker = poolRepository.getWorker(result.workerId) ?: return
        if (!worker.enabled && result.status == WorkerHealthCheckStatus.SKIPPED) {
            return
        }
        val now = System.currentTimeMillis()
        val selectedBefore = poolRepository.getWorkerPoolConfig().selectedWorkerId
        val (state, failureCount) = when (result.status) {
            WorkerHealthCheckStatus.OK -> WorkerHealthState.HEALTHY to 0
            WorkerHealthCheckStatus.SKIPPED -> WorkerHealthState.DISABLED to worker.failureCount
            else -> WorkerHealthStateResolver.resolveAfterCheck(worker.failureCount, result.status)
        }
        val updated = worker.copy(
            state = state,
            lastSuccessAt = if (result.status == WorkerHealthCheckStatus.OK) now else worker.lastSuccessAt,
            lastFailureAt = if (result.status != WorkerHealthCheckStatus.OK &&
                result.status != WorkerHealthCheckStatus.SKIPPED
            ) {
                now
            } else {
                worker.lastFailureAt
            },
            latencyMs = if (result.status == WorkerHealthCheckStatus.OK) result.latencyMs else worker.latencyMs,
            failureCount = failureCount,
            lastErrorCode = if (result.status == WorkerHealthCheckStatus.OK) {
                null
            } else {
                result.errorCode.name
            },
            lastCheckedAt = now,
            updatedAt = now,
        )
        poolRepository.applyHealthUpdate(updated)
        val selectedAfter = poolRepository.getWorkerPoolConfig().selectedWorkerId
        if (selectedBefore != selectedAfter) {
            Log.w(TAG, "Worker health check must not change selected worker; reverting selection change")
            poolRepository.restoreSelectedWorkerId(selectedBefore)
        }
    }

    private fun missingWorkerResult(workerId: String): WorkerHealthCheckResult {
        val now = System.currentTimeMillis()
        return WorkerHealthCheckResult(
            workerId = workerId,
            workerName = "",
            targetUrlMasked = "",
            status = WorkerHealthCheckStatus.FAIL,
            state = WorkerHealthState.UNKNOWN,
            steps = emptyList(),
            latencyMs = 0,
            startedAt = now,
            finishedAt = now,
            errorCode = WorkerHealthErrorCode.UNKNOWN_ERROR,
            errorMessageForDebug = "worker_not_found",
        )
    }

    private companion object {
        const val TAG = "TgWsProxy"
    }
}
