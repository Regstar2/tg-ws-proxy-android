package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.RouteRuntimeState

enum class WorkerPoolContentState {
    EMPTY,
    NO_ENABLED,
    INVALID_CONFIG,
    CONTENT,
}

enum class WorkerPoolConfigWarning {
    NO_ENABLED_WORKERS,
    SELECTED_NOT_FOUND,
    SELECTED_DISABLED,
    SELECTED_UNAVAILABLE,
}

data class WorkerPoolUiState(
    val poolEnabled: Boolean,
    val contentState: WorkerPoolContentState,
    val summary: WorkerPoolSummaryUiModel,
    val strategy: WorkerStrategyUiModel,
    val runtime: WorkerRuntimeUiModel,
    val workers: List<WorkerItemUiModel>,
    val configWarning: WorkerPoolConfigWarning?,
    val isProxyRunning: Boolean,
    val isDiagRunning: Boolean,
    val maskDomains: Boolean,
    val checkingWorkerIds: Set<String>,
    val isCheckingAllWorkers: Boolean,
)

data class WorkerPoolSummaryUiModel(
    val poolEnabledLabelRes: Int,
    val strategyLabelRes: Int,
    val totalCount: Int,
    val enabledCount: Int,
    val disabledCount: Int,
    val healthyCount: Int,
    val degradedCount: Int,
    val deadCount: Int,
    val unknownCount: Int,
    val selectedWorkerName: String,
    val runtimeWorkerName: String,
    val lastSuccessfulWorkerName: String,
    val lastFailedWorkerName: String,
    val lastCheckTimeLabel: String?,
    val lastFailoverReasonLabel: String?,
)

data class WorkerStrategyUiModel(
    val strategy: WorkerSelectionStrategy,
    val labelRes: Int,
    val descriptionRes: Int,
)

data class WorkerRuntimeUiModel(
    val configuredMode: String,
    val selectedRoute: String,
    val activeRoute: String,
    val selectedWorkerName: String,
    val runtimeWorkerName: String,
    val lastSuccessfulWorkerName: String,
    val lastFailedWorkerName: String,
    val failoverReason: String,
    val failoverAttemptCount: Int,
    val failoverActive: Boolean,
)

data class WorkerItemUiModel(
    val id: String,
    val name: String,
    val maskedUrl: String,
    val enabled: Boolean,
    val healthState: WorkerHealthState,
    val healthStateLabelRes: Int,
    val enabledLabelRes: Int,
    val latencyMs: Long?,
    val lastCheckedLabel: String?,
    val failureCount: Int,
    val lastErrorLabelRes: Int?,
    val isSelected: Boolean,
    val isRuntimeWorker: Boolean,
    val isLastSuccessful: Boolean,
    val isLastFailed: Boolean,
    val tagLabelResIds: List<Int>,
    val priority: Int,
    val canSelect: Boolean,
    val canCheck: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val endpoint: WorkerEndpoint,
)

data class WorkerPoolCompactUiModel(
    val poolEnabled: Boolean,
    val strategyLabelRes: Int,
    val runtimeWorkerName: String,
    val runtimeHealthLabelRes: Int?,
    val healthyCount: Int,
    val degradedCount: Int,
)

data class WorkerPoolUiInput(
    val poolEnabled: Boolean,
    val config: WorkerPoolConfig,
    val workers: List<WorkerEndpoint>,
    val selectedWorker: WorkerEndpoint?,
    val failoverSnapshot: WorkerFailoverRuntimeSnapshot?,
    val routeState: RouteRuntimeState,
    val maskDomains: Boolean,
    val lastHealthCheckAtMs: Long?,
    val isProxyRunning: Boolean,
    val isDiagRunning: Boolean,
    val checkingWorkerIds: Set<String>,
    val isCheckingAllWorkers: Boolean,
)
