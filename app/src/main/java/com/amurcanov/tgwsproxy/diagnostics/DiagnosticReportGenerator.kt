package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.ConnectionMetricsFormatter
import com.amurcanov.tgwsproxy.RouteDisplayNames
import com.amurcanov.tgwsproxy.worker.WorkerUrlSanitizer
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeResult
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReportGenerator {
    fun generate(context: android.content.Context, input: DiagnosticReportContext): String {
        val generatedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(input.generatedAtMs))
        val runtime = input.runtimeRoute ?: RuntimeRouteUiModel()
        val probes = if (input.probeResults.isNotEmpty()) {
            input.probeResults
        } else {
            input.probeUiResults.map { ui ->
                RouteProbeResult(
                    target = ui.target,
                    status = ui.status,
                    steps = emptyList(),
                    latencyMs = ui.latencyMs ?: 0,
                    startedAtMs = 0,
                    finishedAtMs = ui.lastCheckedAtMs ?: 0,
                    errorCode = ui.errorCode,
                    errorMessageForDebug = ui.shortDetails,
                )
            }
        }

        val report = buildString {
            appendLine("TGWSProxyAndroid Diagnostic Report")
            appendLine("Generated at: $generatedAt")
            appendLine()
            appendLine("[App]")
            appendLine("Version: ${input.appVersionName} (${input.appVersionCode})")
            appendLine("Build type: ${input.buildType}")
            appendLine("Package: ${input.packageName}")
            appendLine()
            appendLine("[Device]")
            appendLine("Android: ${input.androidRelease}")
            appendLine("SDK: ${input.sdkInt}")
            appendLine("Device: ${input.deviceManufacturer} ${input.deviceModel}")
            appendLine()
            appendLine("[Network]")
            appendLine("Type: ${networkTypeLabel(context, input)}")
            appendLine("VPN detected: ${DiagnosticReportSanitizer.yesNo(input.isVpnDetected)}")
            appendLine("Connected: ${DiagnosticReportSanitizer.yesNo(input.isConnected)}")
            appendLine("Metered: ${input.isMetered?.let { DiagnosticReportSanitizer.yesNo(it) } ?: "UNKNOWN"}")
            appendLine("Transport: ${input.activeTransport.ifBlank { "UNKNOWN" }}")
            appendLine()
            appendLine("[Runtime Route]")
            appendLine("Configured mode: ${labelOrUnknown(context, runtime.configuredMode) { ctx, raw -> RouteDisplayNames.modeLabel(ctx, raw) }}")
            appendLine("Selected route: ${labelOrUnknown(context, runtime.selectedRoute) { ctx, raw -> RouteDisplayNames.routeLabel(ctx, raw) }}")
            appendLine("Active route: ${labelOrUnknown(context, runtime.activeRoute) { ctx, raw -> RouteDisplayNames.routeLabel(ctx, raw) }}")
            appendLine("Last successful route: ${labelOrUnknown(context, runtime.lastSuccessfulRoute) { ctx, raw -> RouteDisplayNames.routeLabel(ctx, raw) }}")
            appendLine("Last failed route: ${labelOrUnknown(context, runtime.lastFailedRoute) { ctx, raw -> RouteDisplayNames.routeLabel(ctx, raw) }}")
            appendLine("Fallback reason: ${runtime.fallbackReason.ifBlank { "NONE" }}")
            if (runtime.currentWorkerName.isNotBlank()) {
                appendLine("Current worker: ${runtime.currentWorkerName}")
                runtime.currentWorkerState?.let { state ->
                    appendLine("Worker state: ${state.name}")
                }
            }
            appendLine()
            appendLine("[Worker Pool]")
            formatWorkerPoolSection(input)?.let { append(it) }
            appendLine()
            appendLine("[Route Probes]")
            if (probes.isEmpty()) {
                appendLine("No probe results available.")
            } else {
                probes.forEach { result ->
                    append(formatProbeBlock(context, result))
                }
            }
            appendLine()
            appendLine("[Configuration]")
            appendLine("Connection mode: ${input.connectionMode.ifBlank { "UNKNOWN" }}")
            appendLine("Fallback enabled: ${DiagnosticReportSanitizer.yesNo(input.fallbackEnabled)}")
            appendLine("Proxy port: ${input.proxyPort.ifBlank { "UNKNOWN" }}")
            appendLine("Worker configured: ${DiagnosticReportSanitizer.yesNo(input.workerConfigured)}")
            appendLine(
                "Worker domain: ${DiagnosticReportSanitizer.maskWorkerHost(input.workerDomain, input.maskDomains)}",
            )
            appendLine("Cloudflare proxy configured: ${DiagnosticReportSanitizer.yesNo(input.cfProxyConfigured)}")
            appendLine("Diagnostics enabled: ${DiagnosticReportSanitizer.yesNo(input.diagnosticsEnabled)}")
            appendLine("Persistent logs enabled: ${DiagnosticReportSanitizer.yesNo(input.persistentLogs.enabled)}")
            appendLine(
                "Persistent logs max size: ${ConnectionMetricsFormatter.formatBytes(input.persistentLogs.maxTotalSizeBytes)}",
            )
            appendLine("Mask domains in report: ${DiagnosticReportSanitizer.yesNo(input.maskDomains)}")
            appendLine()
            appendLine("[Persistent Logs]")
            appendLine("Enabled: ${DiagnosticReportSanitizer.yesNo(input.persistentLogs.enabled)}")
            appendLine("Directory: ${input.persistentLogsDirectory}")
            appendLine("Total size: ${ConnectionMetricsFormatter.formatBytes(input.persistentLogsTotalBytes)}")
            appendLine("Max total size: ${ConnectionMetricsFormatter.formatBytes(input.persistentLogs.maxTotalSizeBytes)}")
            appendLine("Retention days: ${input.persistentLogs.retentionDays}")
            appendLine("Verbosity: ${input.persistentLogs.verbosity.name}")
            appendLine()
            appendLine("[Recent Route Events]")
            if (input.recentRouteEventLines.isEmpty()) {
                appendLine("No route events captured in recent logs.")
            } else {
                input.recentRouteEventLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("[Recent Logs]")
            if (input.recentLogLines.isEmpty()) {
                appendLine("No recent logs available.")
            } else {
                input.recentLogLines.forEach { appendLine(it) }
            }
            if (input.persistentLogTailLines.isNotEmpty()) {
                appendLine()
                appendLine("[Persistent Log Tail]")
                input.persistentLogTailLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("[Notes]")
            appendLine("- Report is generated locally; nothing is sent automatically.")
            appendLine("- Sensitive values are masked; verify before sharing externally.")
        }
        return DiagnosticReportSanitizer.sanitize(report)
    }

    private fun formatWorkerPoolSection(input: DiagnosticReportContext): String? {
        val snapshot = input.workerPoolSnapshot ?: return null
        return buildString {
            appendLine("Enabled: ${DiagnosticReportSanitizer.yesNo(snapshot.enabled)}")
            appendLine("Workers count: ${snapshot.workers.size}")
            appendLine(
                "Selected worker: ${snapshot.selectedWorker?.name ?: "NONE"}",
            )
            appendLine("Enabled workers: ${snapshot.workers.count { it.enabled }}")
            appendLine("Disabled workers: ${snapshot.workers.count { !it.enabled }}")
            snapshot.selectedWorker?.let { worker ->
                appendLine(
                    "Selected worker URL: ${WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains)}",
                )
            }
            snapshot.workers.forEach { worker ->
                appendLine(
                    "- ${worker.name}: enabled=${worker.enabled}, state=${worker.state.name}, url=${
                        WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains)
                    }",
                )
            }
        }
    }

    private fun networkTypeLabel(context: android.content.Context, input: DiagnosticReportContext): String {
        return when (input.networkProfile?.type?.prefValue) {
            "wifi" -> context.getString(com.amurcanov.tgwsproxy.R.string.adaptive_network_wifi)
            "mobile" -> context.getString(com.amurcanov.tgwsproxy.R.string.adaptive_network_mobile)
            "unknown", null -> context.getString(com.amurcanov.tgwsproxy.R.string.adaptive_network_unknown)
            else -> input.networkProfile.type.prefValue
        }
    }

    private fun labelOrUnknown(
        context: android.content.Context,
        raw: String,
        labeler: (android.content.Context, String) -> String,
    ): String {
        if (raw.isBlank()) return "UNKNOWN"
        return labeler(context, raw)
    }

    private fun formatProbeBlock(context: android.content.Context, result: RouteProbeResult): String {
        return buildString {
            val name = context.getString(RouteProbeUiMapper.targetLabelRes(result.target))
            val error = if (result.errorCode.name != "NONE") result.errorCode.name else ""
            val latency = if (result.latencyMs > 0) "${result.latencyMs} ms" else ""
            append("$name: ${result.status.name}")
            if (latency.isNotBlank()) append(", $latency")
            if (error.isNotBlank()) append(", $error")
            appendLine()
            if (result.steps.isNotEmpty()) {
                result.steps.forEach { step ->
                    val stepError = if (step.error.code.name != "NONE") ", ${step.error.code.name}" else ""
                    appendLine(
                        "  - ${step.step.name}: ${step.status.name}" +
                            if (step.latencyMs > 0) " (${step.latencyMs} ms)" else "" +
                            stepError,
                    )
                }
            } else if (result.errorMessageForDebug.isNotBlank()) {
                appendLine("  - ${result.errorMessageForDebug}")
            }
        }
    }
}
