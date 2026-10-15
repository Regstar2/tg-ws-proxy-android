package com.amurcanov.tgwsproxy.worker

/**
 * Pure presentation rules for the approved Worker screen A.
 * No persistence, route selection or network behavior is changed here.
 */
internal data class WorkerScreenAPresentation(
    val canChangeConfiguration: Boolean,
    val canCheckAll: Boolean,
    val showEmptyState: Boolean,
    val showNoEnabledWarning: Boolean,
    val showActualRuntime: Boolean,
)

internal fun workerScreenAPresentation(
    poolEnabled: Boolean,
    proxyRunning: Boolean,
    totalWorkers: Int,
    enabledWorkers: Int,
    checkingAll: Boolean,
    activeRoute: String,
): WorkerScreenAPresentation = WorkerScreenAPresentation(
    canChangeConfiguration = !proxyRunning,
    canCheckAll = poolEnabled && !proxyRunning && !checkingAll && enabledWorkers > 0,
    showEmptyState = poolEnabled && totalWorkers == 0,
    showNoEnabledWarning = poolEnabled && totalWorkers > 0 && enabledWorkers == 0,
    showActualRuntime = proxyRunning && poolEnabled &&
        activeRoute in setOf("cf_worker_ws", "worker_ws", "worker"),
)
