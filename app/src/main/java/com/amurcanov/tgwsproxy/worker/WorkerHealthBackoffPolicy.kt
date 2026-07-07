package com.amurcanov.tgwsproxy.worker

object WorkerHealthBackoffPolicy {
    fun shouldSkipAutomaticCheck(
        worker: WorkerEndpoint,
        lastCheckAtMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (worker.state != WorkerHealthState.DEAD) {
            return false
        }
        val anchor = lastCheckAtMs ?: worker.lastFailureAt ?: return false
        return nowMs - anchor < WorkerHealthCheckConfig.DEFAULT_DEAD_WORKER_BACKOFF_MS
    }
}
