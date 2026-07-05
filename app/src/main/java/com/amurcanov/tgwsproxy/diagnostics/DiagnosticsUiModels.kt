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
    val currentWorkerName: String = "",
    val currentWorkerState: com.amurcanov.tgwsproxy.worker.WorkerHealthState? = null,
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
                currentWorkerName = route.currentWorkerName,
                currentWorkerState = route.currentWorkerState,
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
)
