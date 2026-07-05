package com.amurcanov.tgwsproxy.worker

import java.util.UUID

enum class WorkerHealthState(val prefValue: String) {
    UNKNOWN("unknown"),
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    DEAD("dead"),
    DISABLED("disabled"),
    ;

    companion object {
        fun fromPref(value: String?): WorkerHealthState {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.prefValue == normalized } ?: UNKNOWN
        }
    }
}

enum class WorkerPoolError {
    NO_WORKER_CONFIGURED,
    NO_ENABLED_WORKER,
    SELECTED_WORKER_NOT_FOUND,
    INVALID_WORKER_URL,
    WORKER_POOL_STORAGE_ERROR,
}

enum class WorkerSelectionMode(val prefValue: String) {
    SELECTED_ONLY("selected_only"),
    ;

    companion object {
        fun fromPref(value: String?): WorkerSelectionMode? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.prefValue == normalized }
        }
    }
}

data class WorkerEndpoint(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val priority: Int = 0,
    val weight: Int = 1,
    val state: WorkerHealthState = WorkerHealthState.UNKNOWN,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val latencyMs: Long? = null,
    val failureCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun normalizedDomain(): String = com.amurcanov.tgwsproxy.WorkerDomain.normalize(url)

    companion object {
        fun create(
            name: String,
            url: String,
            enabled: Boolean = true,
            nowMs: Long = System.currentTimeMillis(),
        ): WorkerEndpoint {
            return WorkerEndpoint(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                url = url.trim(),
                enabled = enabled,
                state = if (enabled) WorkerHealthState.UNKNOWN else WorkerHealthState.DISABLED,
                createdAt = nowMs,
                updatedAt = nowMs,
            )
        }
    }
}

data class WorkerPoolConfig(
    val enabled: Boolean = false,
    val selectedWorkerId: String? = null,
    val fallbackToSingleWorkerUrl: Boolean = true,
    val defaultWorkerNamePattern: String = "Worker %d",
    val selectionMode: WorkerSelectionMode = WorkerSelectionMode.SELECTED_ONLY,
)

data class WorkerPoolReportSnapshot(
    val enabled: Boolean,
    val workers: List<WorkerEndpoint>,
    val selectedWorker: WorkerEndpoint?,
    val legacyWorkerDomain: String,
    val maskDomains: Boolean,
)

sealed interface WorkerRouteResolution {
    data class Legacy(val domain: String) : WorkerRouteResolution
    data class Pool(val domain: String, val worker: WorkerEndpoint) : WorkerRouteResolution
    data class Error(val code: WorkerPoolError) : WorkerRouteResolution
}
