package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.NetworkProfile
import com.amurcanov.tgwsproxy.PersistentLoggingPrefs
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeResult
import com.amurcanov.tgwsproxy.worker.WorkerPoolReportSnapshot

data class DiagnosticReportContext(
    val appVersionName: String,
    val appVersionCode: Int,
    val buildType: String,
    val packageName: String,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val androidRelease: String,
    val sdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val networkProfile: NetworkProfile?,
    val isConnected: Boolean,
    val isVpnDetected: Boolean,
    val isMetered: Boolean?,
    val activeTransport: String,
    val runtimeRoute: RuntimeRouteUiModel?,
    val probeResults: List<RouteProbeResult>,
    val probeUiResults: List<RouteProbeUiModel>,
    val connectionMode: String,
    val proxyPort: String,
    val workerConfigured: Boolean,
    val workerDomain: String,
    val workerPoolSnapshot: WorkerPoolReportSnapshot? = null,
    val workerHealthLastCheckAtMs: Long? = null,
    val workerSelectionPreview: com.amurcanov.tgwsproxy.worker.WorkerSelectionPreview? = null,
    val enrichedRuntimeRoute: com.amurcanov.tgwsproxy.RouteRuntimeState? = null,
    val workerPoolUiSelectedWorkerMissing: Boolean = false,
    val workerPoolUiNoEnabledWorkers: Boolean = false,
    val workerPoolUiInvalidConfig: Boolean = false,
    val cfProxyConfigured: Boolean,
    val mtProtoConfigLines: List<String> = emptyList(),
    val frontendDiagnostics: FrontendDiagnosticsSnapshot = FrontendDiagnosticsSnapshot(),
    val maskDomains: Boolean,
    val fallbackEnabled: Boolean,
    val diagnosticsEnabled: Boolean,
    val persistentLogs: PersistentLoggingPrefs,
    val persistentLogsTotalBytes: Long,
    val persistentLogsDirectory: String,
    val recentLogLines: List<String>,
    val recentRouteEventLines: List<String>,
    val persistentLogTailLines: List<String>,
)
