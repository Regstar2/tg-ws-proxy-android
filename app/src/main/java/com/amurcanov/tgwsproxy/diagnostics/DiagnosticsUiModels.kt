package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.RouteRuntimeState
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStep
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget

data class RouteProbeStepUiModel(
    val step: RouteProbeStep,
    val status: RouteProbeStatus,
    val latencyMs: Long,
    val errorCode: RouteProbeErrorCode,
    val debugDetail: String = "",
)

data class RouteProbeUiModel(
    val target: RouteProbeTarget,
    val status: RouteProbeStatus,
    val latencyMs: Long? = null,
    val lastCheckedAtMs: Long? = null,
    val errorCode: RouteProbeErrorCode = RouteProbeErrorCode.NONE,
    val shortDetails: String = "",
    val steps: List<RouteProbeStepUiModel> = emptyList(),
)

data class RuntimeRouteUiModel(
    val configuredMode: String = "",
    val selectedRoute: String = "",
    val activeRoute: String = "",
    val lastSuccessfulRoute: String = "",
    val lastFailedRoute: String = "",
    val fallbackReason: String = "",
    val selectedWorkerId: String = "",
    val selectedWorkerName: String = "",
    val currentWorkerName: String = "",
    val currentWorkerState: com.amurcanov.tgwsproxy.worker.WorkerHealthState? = null,
    val runtimeWorkerId: String = "",
    val runtimeWorkerName: String = "",
    val lastSuccessfulWorkerId: String = "",
    val lastSuccessfulWorkerName: String = "",
    val lastFailedWorkerId: String = "",
    val lastFailedWorkerName: String = "",
    val workerFailoverReason: String = "",
    val workerFailoverAttemptCount: Int = 0,
    val workerFailoverActive: Boolean = false,
    val workerSelectionStrategy: String = "",
    val workerSelectionReason: String = "",
    val workerCandidateCount: Int = 0,
) {
    companion object {
        fun from(route: RouteRuntimeState): RuntimeRouteUiModel {
            return RuntimeRouteUiModel(
                configuredMode = route.configuredMode,
                selectedRoute = route.selectedRoute,
                activeRoute = route.activeRoute,
                lastSuccessfulRoute = route.lastSuccessfulRoute,
                lastFailedRoute = route.lastFailedRoute,
                fallbackReason = route.fallbackReason,
                selectedWorkerId = route.selectedWorkerId,
                selectedWorkerName = route.selectedWorkerName,
                currentWorkerName = route.currentWorkerName,
                currentWorkerState = route.currentWorkerState,
                runtimeWorkerId = route.currentWorkerId,
                runtimeWorkerName = route.currentWorkerName,
                lastSuccessfulWorkerId = route.lastSuccessfulWorkerId,
                lastSuccessfulWorkerName = route.lastSuccessfulWorkerName,
                lastFailedWorkerId = route.lastFailedWorkerId,
                lastFailedWorkerName = route.lastFailedWorkerName,
                workerFailoverReason = route.workerFailoverReason,
                workerFailoverAttemptCount = route.workerFailoverAttemptCount,
                workerFailoverActive = route.workerFailoverActive,
                workerSelectionStrategy = route.workerSelectionStrategy,
                workerSelectionReason = route.workerSelectionReason,
                workerCandidateCount = route.workerCandidateCount,
            )
        }
    }
}

data class DiagnosticsScreenState(
    val isChecking: Boolean = false,
    val checkingLabel: String? = null,
    val hasRunOnce: Boolean = false,
    val results: List<RouteProbeUiModel> = emptyList(),
    val lastRunAtMs: Long? = null,
    val screenError: Boolean = false,
    val runtimeRoute: RuntimeRouteUiModel? = null,
    val isGeneratingReport: Boolean = false,
    val persistentLogsEnabled: Boolean = false,
    val persistentLogsSizeLabel: String = "",
    val frontendDiagnostics: FrontendDiagnosticsSnapshot? = null,
)

data class WorkerPoolHealthSummaryUi(
    val workersCount: Int,
    val healthyCount: Int,
    val degradedCount: Int,
    val deadCount: Int,
    val disabledCount: Int,
    val selectedWorkerState: com.amurcanov.tgwsproxy.worker.WorkerHealthState? = null,
    val lastHealthCheckAtMs: Long? = null,
)

data class WorkerFailoverSummaryUi(
    val enabledWorkersCount: Int,
    val candidateCount: Int,
    val selectedWorkerName: String,
    val runtimeWorkerName: String,
    val lastSuccessfulWorkerName: String,
    val lastFailedWorkerName: String,
    val failoverReason: String,
    val failoverActive: Boolean,
    val attemptCount: Int,
)

data class WorkerSelectionSummaryUi(
    val strategy: com.amurcanov.tgwsproxy.worker.WorkerSelectionStrategy,
    val selectionReason: String,
    val candidateCount: Int,
    val candidateNames: List<String>,
    val runtimeWorkerName: String,
    val roundRobinCursor: String? = null,
    val lowestLatencyMaxAgeMs: Long = com.amurcanov.tgwsproxy.worker.WorkerSelectionConfig.DEFAULT_LOWEST_LATENCY_MAX_AGE_MS,
)
