package com.amurcanov.tgwsproxy.worker

import android.util.Log

class WorkerRuntimeFailureRecorder(
    private val poolRepository: WorkerPoolRepository,
) {
    private var lastProcessedFailureKey: String = ""
    private var lastProcessedSuccessId: String = ""

    fun onRouteRuntimeUpdate(runtime: com.amurcanov.tgwsproxy.RouteRuntimeState) {
        val successId = runtime.lastSuccessfulWorkerId
        if (successId.isNotBlank() && successId != lastProcessedSuccessId) {
            lastProcessedSuccessId = successId
            recordSuccess(successId)
        }
        val failId = runtime.lastFailedWorkerId
        if (failId.isBlank()) {
            return
        }
        val failureKey = "$failId:${runtime.workerFailoverReason}:${runtime.lastUpdatedAtMs}"
        if (failureKey == lastProcessedFailureKey) {
            return
        }
        lastProcessedFailureKey = failureKey
        recordFailure(failId, WorkerFailoverReason.fromWire(runtime.workerFailoverReason))
    }

    fun recordSuccess(workerId: String) {
        val worker = poolRepository.getWorker(workerId) ?: return
        val now = System.currentTimeMillis()
        poolRepository.applyHealthUpdate(
            worker.copy(
                state = WorkerHealthState.HEALTHY,
                lastSuccessAt = now,
                failureCount = 0,
                lastErrorCode = null,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "Worker runtime success recorded: id=$workerId")
    }

    fun recordFailure(workerId: String, reason: WorkerFailoverReason) {
        val worker = poolRepository.getWorker(workerId) ?: return
        val now = System.currentTimeMillis()
        val errorCode = reasonToErrorCode(reason)
        val (state, failureCount) = WorkerHealthStateResolver.resolveAfterCheck(
            worker.failureCount,
            WorkerHealthCheckStatus.FAIL,
        )
        poolRepository.applyHealthUpdate(
            worker.copy(
                state = state,
                lastFailureAt = now,
                failureCount = failureCount,
                lastErrorCode = errorCode.name,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "Worker runtime failure recorded: id=$workerId, reason=${reason.name}, state=${state.name}")
    }

    fun reset() {
        lastProcessedFailureKey = ""
        lastProcessedSuccessId = ""
    }

    private fun reasonToErrorCode(reason: WorkerFailoverReason): WorkerHealthErrorCode = when (reason) {
        WorkerFailoverReason.WORKER_CONNECT_TIMEOUT -> WorkerHealthErrorCode.TIMEOUT
        WorkerFailoverReason.WORKER_DNS_FAILED -> WorkerHealthErrorCode.DNS_FAILED
        WorkerFailoverReason.WORKER_TLS_FAILED -> WorkerHealthErrorCode.TLS_HANDSHAKE_FAILED
        WorkerFailoverReason.WORKER_WEBSOCKET_FAILED -> WorkerHealthErrorCode.WEBSOCKET_HANDSHAKE_FAILED
        WorkerFailoverReason.SELECTED_WORKER_DISABLED -> WorkerHealthErrorCode.WORKER_DISABLED
        WorkerFailoverReason.NO_ENABLED_WORKER,
        WorkerFailoverReason.ALL_WORKERS_FAILED,
        -> WorkerHealthErrorCode.UNKNOWN_ERROR
        else -> WorkerHealthErrorCode.UNKNOWN_ERROR
    }

    private companion object {
        const val TAG = "TgWsProxy"
    }
}
