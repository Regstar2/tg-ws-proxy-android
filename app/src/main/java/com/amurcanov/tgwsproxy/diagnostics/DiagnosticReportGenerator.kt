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
            append(formatFrontendDiagnosticsSections(input.frontendDiagnostics))
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
            appendLine("[Worker Failover]")
            formatWorkerFailoverSection(input)?.let { append(it) } ?: appendLine("Worker failover not active.")
            appendLine()
            appendLine("[Worker Selection]")
            formatWorkerSelectionSection(input)?.let { append(it) } ?: appendLine("Worker selection not configured.")
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
            input.mtProtoConfigLines.forEach { line ->
                appendLine(line)
            }
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

    internal fun formatFrontendDiagnosticsSections(snapshot: FrontendDiagnosticsSnapshot): String {
        return buildString {
            appendLine("[SOCKS5 / WebSocket frontend]")
            appendLine("Frontend kind: SOCKS5")
            appendLine("Stable: YES")
            appendLine("Default: NO")
            appendLine("Listener status: ${snapshot.socks5ListenerStatus}")
            appendLine("Current route support: ${snapshot.socks5RouteSupport.name}")
            appendLine("Selected route: ${snapshot.socks5SelectedRoute.ifBlank { "NONE" }}")
            appendLine("Active route: ${snapshot.socks5ActiveRoute.ifBlank { "NONE" }}")
            appendLine()
            appendLine("[MTProto frontend]")
            appendLine("Frontend kind: MTPROTO")
            appendLine("Default: YES")
            appendLine("Mode enabled: ${DiagnosticReportSanitizer.yesNo(snapshot.mtProtoEnabled)}")
            appendLine("Listen host: ${snapshot.mtProtoHost.ifBlank { "UNKNOWN" }}")
            appendLine("Listen port: ${snapshot.mtProtoPort.takeIf { it > 0 } ?: "UNKNOWN"}")
            appendLine("Secret status: ${snapshot.mtProtoSecretStatus}")
            appendLine(
                "Secret fingerprint: ${snapshot.mtProtoSecretFingerprint.ifBlank { "UNAVAILABLE" }}",
            )
            appendLine("Local listener status: ${snapshot.mtProtoListenerStatus}")
            appendLine("Outbound support status: ${snapshot.mtProtoOutboundStatus}")
            appendLine("Route integration status: ${snapshot.mtProtoRouteSupport.name}")
            appendLine("Telegram proxy link support: ${snapshot.mtProtoProxyLinkSupport.name}")
            appendLine("Selected backend: ${snapshot.mtProtoSelectedBackend.ifBlank { "NONE" }}")
            appendLine("Actual backend: ${snapshot.mtProtoActualBackend.ifBlank { "NONE" }}")
            appendLine("Last error code: ${snapshot.mtProtoLastErrorCode}")
            appendLine("Fake TLS enabled: ${DiagnosticReportSanitizer.yesNo(snapshot.mtProtoFakeTlsEnabled)}")
            appendLine(
                "Fake TLS masking passthrough: " +
                    DiagnosticReportSanitizer.yesNo(snapshot.mtProtoMaskingPassthrough),
            )
            appendLine("Fake TLS accepted: ${snapshot.mtProtoFakeTlsAccepted}")
            appendLine("Fake TLS rejected: ${snapshot.mtProtoFakeTlsRejected}")
            appendLine("Fake TLS redirected: ${snapshot.mtProtoFakeTlsRedirected}")
            appendLine("Fake TLS probes: ${snapshot.mtProtoFakeTlsProbe}")
            appendLine("Fake TLS passthrough: ${snapshot.mtProtoFakeTlsPassthrough}")
            appendLine("Fake TLS last error: ${snapshot.mtProtoFakeTlsLastError.ifBlank { "NONE" }}")
            snapshot.lastSuccessfulStartAtMs?.let {
                appendLine("Last successful start time: ${formatReportTimestamp(it)}")
            }
            appendLine()
            appendLine("[Route backend]")
            appendLine("Configured frontend: ${snapshot.configuredFrontendKind}")
            appendLine("Runtime frontend: ${snapshot.runtimeFrontendKind}")
            appendLine("Route support: ${snapshot.runtimeRouteSupport.name}")
            appendLine("Selected backend: ${snapshot.selectedBackend.ifBlank { "NONE" }}")
            appendLine("Actual backend: ${snapshot.actualBackend.ifBlank { "NONE" }}")
            appendLine(
                "Fallback used: ${snapshot.fallbackUsed?.let(DiagnosticReportSanitizer::yesNo) ?: "UNKNOWN"}",
            )
            appendLine("Route reason: ${snapshot.routeReason.ifBlank { "NONE" }}")
            appendLine()
            appendLine("[Worker pool / future worker pool]")
            appendLine("Pool enabled: ${DiagnosticReportSanitizer.yesNo(snapshot.workerPoolEnabled)}")
            appendLine("Workers configured: ${snapshot.workerPoolWorkersCount}")
            appendLine("Workers enabled: ${snapshot.workerPoolEnabledWorkersCount}")
            appendLine(
                "Selected worker: ${snapshot.workerPoolSelectedWorkerName.ifBlank { "NONE" }}",
            )
            appendLine("MTProto worker pool integration: ${snapshot.mtProtoWorkerPoolSupport.name}")
            appendLine()
            appendLine("[Runtime route truth]")
            appendLine("Frontend: ${snapshot.runtimeFrontendKind}")
            appendLine("Listener: ${runtimeListenerStatus(snapshot)}")
            appendLine("Selected backend: ${snapshot.selectedBackend.ifBlank { "NONE" }}")
            appendLine("Actual backend: ${snapshot.actualBackend.ifBlank { "NONE" }}")
            appendLine(
                "Fallback used: ${snapshot.fallbackUsed?.let(DiagnosticReportSanitizer::yesNo) ?: "UNKNOWN"}",
            )
            appendLine("Reason: ${snapshot.routeReason.ifBlank { "NONE" }}")
            appendLine("Last error code: ${snapshot.lastErrorCode}")
            appendLine()
            appendLine("[SOCKS5/WS vs MTProto]")
            appendLine("SOCKS5/WS stability: STABLE")
            appendLine("SOCKS5/WS default: NO")
            appendLine("SOCKS5/WS current route support: ${snapshot.socks5RouteSupport.name}")
            appendLine("MTProto default: YES")
            appendLine("MTProto Telegram proxy link support: ${snapshot.mtProtoProxyLinkSupport.name}")
            appendLine("MTProto route support: ${snapshot.mtProtoRouteSupport.name}")
            appendLine()
        }
    }

    private fun runtimeListenerStatus(snapshot: FrontendDiagnosticsSnapshot): String {
        return when (snapshot.runtimeFrontendKind) {
            "MTPROTO" -> snapshot.mtProtoListenerStatus
            "SOCKS5" -> snapshot.socks5ListenerStatus
            else -> "STOPPED"
        }
    }

    private fun formatWorkerFailoverSection(input: DiagnosticReportContext): String? {
        val runtime = input.enrichedRuntimeRoute ?: return null
        if (runtime.selectedWorkerId.isBlank() && runtime.currentWorkerId.isBlank()) {
            return null
        }
        val snapshot = input.workerPoolSnapshot
        return buildString {
            appendLine(
                "Selected worker: ${runtime.selectedWorkerName.ifBlank { runtime.selectedWorkerId.ifBlank { "NONE" } }}",
            )
            appendLine(
                "Runtime worker: ${runtime.currentWorkerName.ifBlank { runtime.currentWorkerId.ifBlank { "NONE" } }}",
            )
            appendLine(
                "Last successful worker: ${runtime.lastSuccessfulWorkerName.ifBlank { runtime.lastSuccessfulWorkerId.ifBlank { "NONE" } }}",
            )
            appendLine(
                "Last failed worker: ${runtime.lastFailedWorkerName.ifBlank { runtime.lastFailedWorkerId.ifBlank { "NONE" } }}",
            )
            appendLine("Failover active: ${DiagnosticReportSanitizer.yesNo(runtime.workerFailoverActive)}")
            appendLine("Failover reason: ${runtime.workerFailoverReason.ifBlank { "NONE" }}")
            appendLine("Attempts: ${runtime.workerFailoverAttemptCount}")
            snapshot?.workers?.let { workers ->
                appendLine("Enabled workers: ${workers.count { it.enabled }}")
                appendLine("Candidates skipped by backoff: ${runtime.workerFailoverSkippedBackoff}")
                workers.forEach { worker ->
                    val attempted = worker.id == runtime.lastFailedWorkerId ||
                        worker.id == runtime.lastSuccessfulWorkerId ||
                        worker.id == runtime.currentWorkerId
                    appendLine("- ${worker.name}:")
                    appendLine("  enabled=${worker.enabled}")
                    appendLine("  state=${worker.state.name}")
                    appendLine("  failureCount=${worker.failureCount}")
                    worker.lastErrorCode?.let { appendLine("  lastErrorCode=$it") }
                    appendLine("  wasAttemptedInLastFailover=${DiagnosticReportSanitizer.yesNo(attempted)}")
                    appendLine(
                        "  maskedUrl=${WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains)}",
                    )
                }
            }
        }
    }

    private fun formatWorkerSelectionSection(input: DiagnosticReportContext): String? {
        val preview = input.workerSelectionPreview ?: return null
        val runtime = input.enrichedRuntimeRoute
        return buildString {
            appendLine("Strategy: ${preview.strategy.name}")
            appendLine("Selection reason: ${preview.reason.wireValue}")
            appendLine("Candidate count: ${preview.candidateCount}")
            runtime?.let { route ->
                appendLine(
                    "Selected worker: ${route.selectedWorkerName.ifBlank { route.selectedWorkerId.ifBlank { "NONE" } }}",
                )
                appendLine(
                    "Runtime worker: ${route.currentWorkerName.ifBlank { route.currentWorkerId.ifBlank { "NONE" } }}",
                )
            }
            preview.roundRobinCursor?.let { appendLine("Round-robin cursor: $it") }
            appendLine("Lowest latency max age: ${preview.lowestLatencyMaxAgeMs / 60_000} min")
            preview.candidates.forEachIndexed { index, candidate ->
                val latency = candidate.latencyMs?.let { "$it ms" } ?: "N/A"
                appendLine("${index + 1}. ${candidate.workerName} — ${candidate.state.name}, $latency")
            }
        }
    }

    private fun formatWorkerPoolSection(input: DiagnosticReportContext): String? {
        val snapshot = input.workerPoolSnapshot ?: return null
        val workers = snapshot.workers
        return buildString {
            appendLine("Enabled: ${DiagnosticReportSanitizer.yesNo(snapshot.enabled)}")
            appendLine("Workers count: ${workers.size}")
            appendLine("Selected worker: ${snapshot.selectedWorker?.name ?: "NONE"}")
            snapshot.selectedWorker?.let { worker ->
                appendLine("Selected worker state: ${worker.state.name}")
                worker.latencyMs?.let { appendLine("Selected worker latency: ${it} ms") }
            }
            appendLine("Healthy workers count: ${workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.HEALTHY }}")
            appendLine("Degraded workers count: ${workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEGRADED }}")
            appendLine("Dead workers count: ${workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEAD }}")
            appendLine("Disabled workers count: ${workers.count { !it.enabled }}")
            appendLine("Worker Pool UI selected worker missing: ${DiagnosticReportSanitizer.yesNo(input.workerPoolUiSelectedWorkerMissing)}")
            appendLine("Worker Pool UI no enabled workers: ${DiagnosticReportSanitizer.yesNo(input.workerPoolUiNoEnabledWorkers)}")
            appendLine("Worker Pool UI invalid config: ${DiagnosticReportSanitizer.yesNo(input.workerPoolUiInvalidConfig)}")
            input.workerHealthLastCheckAtMs?.let { at ->
                val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
                    .format(Date(at))
                appendLine("Last health check time: $formatted")
            }
            snapshot.selectedWorker?.let { worker ->
                appendLine(
                    "Selected worker URL: ${WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains)}",
                )
            }
            workers.forEach { worker ->
                appendLine("- ${worker.name}:")
                appendLine("  enabled=${worker.enabled}")
                appendLine("  state=${worker.state.name}")
                worker.latencyMs?.let { appendLine("  latencyMs=$it") }
                appendLine("  failureCount=${worker.failureCount}")
                worker.lastSuccessAt?.let {
                    appendLine("  lastSuccessAt=${formatReportTimestamp(it)}")
                }
                worker.lastFailureAt?.let {
                    appendLine("  lastFailureAt=${formatReportTimestamp(it)}")
                }
                worker.lastErrorCode?.let { appendLine("  lastErrorCode=$it") }
                worker.lastCheckedAt?.let {
                    appendLine("  lastCheckedAt=${formatReportTimestamp(it)}")
                }
                appendLine(
                    "  maskedUrl=${WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains)}",
                )
            }
        }
    }

    private fun formatReportTimestamp(epochMs: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(epochMs))
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
