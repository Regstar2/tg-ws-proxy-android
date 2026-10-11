package com.amurcanov.tgwsproxy.worker

object WorkerHealthStateResolver {
    // A WebSocket upgrade confirms endpoint compatibility, not Telegram relay health.
    fun resolveAfterTransportCheck(
        transport: WorkerTransport,
        currentFailureCount: Int,
        checkStatus: WorkerHealthCheckStatus,
    ): Pair<WorkerHealthState, Int> {
        if (transport == WorkerTransport.LEGACY_WEBSOCKET && checkStatus == WorkerHealthCheckStatus.OK) {
            return WorkerHealthState.UNKNOWN to 0
        }
        return resolveAfterCheck(currentFailureCount, checkStatus)
    }

    fun resolveAfterCheck(
        currentFailureCount: Int,
        checkStatus: WorkerHealthCheckStatus,
    ): Pair<WorkerHealthState, Int> {
        if (checkStatus == WorkerHealthCheckStatus.SKIPPED) {
            return WorkerHealthState.DISABLED to currentFailureCount
        }
        if (checkStatus == WorkerHealthCheckStatus.OK) {
            return WorkerHealthState.HEALTHY to 0
        }
        val nextFailureCount = currentFailureCount + 1
        val state = when {
            nextFailureCount >= WorkerHealthCheckConfig.DEAD_FAILURE_THRESHOLD -> WorkerHealthState.DEAD
            nextFailureCount >= WorkerHealthCheckConfig.DEGRADED_FAILURE_THRESHOLD -> WorkerHealthState.DEGRADED
            else -> WorkerHealthState.UNKNOWN
        }
        return state to nextFailureCount
    }

    fun resolveStateFromFailureCount(failureCount: Int): WorkerHealthState {
        return when {
            failureCount >= WorkerHealthCheckConfig.DEAD_FAILURE_THRESHOLD -> WorkerHealthState.DEAD
            failureCount >= WorkerHealthCheckConfig.DEGRADED_FAILURE_THRESHOLD -> WorkerHealthState.DEGRADED
            failureCount > 0 -> WorkerHealthState.UNKNOWN
            else -> WorkerHealthState.UNKNOWN
        }
    }
}
