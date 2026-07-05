package com.amurcanov.tgwsproxy.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.amurcanov.tgwsproxy.NetworkProfileProvider
import com.amurcanov.tgwsproxy.PersistentLogStore
import com.amurcanov.tgwsproxy.PersistentLoggingPrefsStore
import com.amurcanov.tgwsproxy.ProxyRuntimeState
import com.amurcanov.tgwsproxy.routeprobe.RouteDiagnosticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

object DiagnosticReportContextFactory {
    private val routeEventMarkers = listOf(
        "Route selected",
        "Route connect started",
        "Route success",
        "Route failed",
        "Fallback activated",
        "Active route changed",
        "Connection closed",
        "Diagnostics run started",
        "Diagnostics target result",
    )

    suspend fun build(
        context: Context,
        repository: RouteDiagnosticsRepository,
        reportUi: DiagnosticReportUiContext,
        recentLogLines: List<String>,
    ): DiagnosticReportContext = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val persistent = PersistentLoggingPrefsStore.load(prefs)
        val runtimeBase = reportUi.enrichedRuntimeRoute ?: ProxyRuntimeState.uiMetrics.value.runtime.routeRuntime
        val runtime = runtimeBase
        val probeResults = repository.getLastProbeResults()
        val logsDir = File(context.filesDir, "logs").absolutePath
        val totalBytes = PersistentLogStore.totalSizeBytes(context)
        val tail = readPersistentLogTail(context, DiagnosticReportConfig.DEFAULT_PERSISTENT_LOG_TAIL_LINES)
        val routeEvents = recentLogLines.filter { line ->
            routeEventMarkers.any { marker -> line.contains(marker, ignoreCase = true) }
        }.takeLast(DiagnosticReportConfig.DEFAULT_ROUTE_EVENT_LINES)

        DiagnosticReportContext(
            appVersionName = reportUi.versionName,
            appVersionCode = reportUi.versionCode,
            buildType = reportUi.buildType,
            packageName = context.packageName,
            androidRelease = Build.VERSION.RELEASE ?: "UNKNOWN",
            sdkInt = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER ?: "UNKNOWN",
            deviceModel = Build.MODEL ?: "UNKNOWN",
            networkProfile = NetworkProfileProvider.current(context),
            isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isVpnDetected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            isMetered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            activeTransport = transportLabel(caps),
            runtimeRoute = RuntimeRouteUiModel.from(runtime),
            probeResults = probeResults,
            probeUiResults = reportUi.probeUiResults,
            connectionMode = reportUi.connectionMode,
            proxyPort = reportUi.proxyPort,
            workerConfigured = reportUi.workerDomain.isNotBlank() ||
                (reportUi.workerPoolSnapshot?.workers?.any { it.enabled } == true),
            workerDomain = reportUi.workerDomain,
            workerPoolSnapshot = reportUi.workerPoolSnapshot,
            enrichedRuntimeRoute = runtime,
            cfProxyConfigured = reportUi.cfProxyConfigured,
            maskDomains = reportUi.maskDomains,
            fallbackEnabled = reportUi.fallbackEnabled,
            diagnosticsEnabled = reportUi.diagnosticsEnabled,
            persistentLogs = persistent,
            persistentLogsTotalBytes = totalBytes,
            persistentLogsDirectory = logsDir,
            recentLogLines = recentLogLines.takeLast(DiagnosticReportConfig.DEFAULT_REPORT_LOG_LINES),
            recentRouteEventLines = routeEvents,
            persistentLogTailLines = tail,
        )
    }

    private fun transportLabel(caps: NetworkCapabilities?): String {
        if (caps == null) return "UNKNOWN"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private suspend fun readPersistentLogTail(context: Context, maxLines: Int): List<String> =
        withContext(Dispatchers.IO) {
            val files = PersistentLogStore.listLogFiles(context)
                .filter { it.name.endsWith(".log") }
                .sortedByDescending { it.lastModified() }
            if (files.isEmpty()) return@withContext emptyList()
            val lines = mutableListOf<String>()
            for (file in files) {
                if (lines.size >= maxLines) break
                lines.addAll(0, readTailLines(file, maxLines - lines.size))
            }
            lines.takeLast(maxLines).map { DiagnosticReportSanitizer.sanitize(it) }
        }

    internal fun readTailLines(file: File, maxLines: Int): List<String> {
        if (!file.exists() || maxLines <= 0) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val chunkSize = 8192
                var pointer = raf.length()
                val sb = StringBuilder()
                val collected = ArrayDeque<String>()
                while (pointer > 0 && collected.size < maxLines) {
                    val readSize = minOf(chunkSize.toLong(), pointer).toInt()
                    pointer -= readSize
                    raf.seek(pointer)
                    val buf = ByteArray(readSize)
                    raf.readFully(buf)
                    sb.insert(0, String(buf, Charsets.UTF_8))
                    val parts = sb.toString().split('\n')
                    sb.clear()
                    if (parts.isNotEmpty()) {
                        sb.append(parts[0])
                    }
                    for (i in parts.size - 1 downTo if (parts.size > 1) 1 else 0) {
                        val line = parts[i].trimEnd('\r')
                        if (line.isBlank()) continue
                        collected.addFirst(line)
                        if (collected.size >= maxLines) break
                    }
                }
                collected.toList()
            }
        }.getOrDefault(emptyList())
    }
}

data class DiagnosticReportUiContext(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val connectionMode: String,
    val proxyPort: String,
    val workerDomain: String,
    val workerPoolSnapshot: com.amurcanov.tgwsproxy.worker.WorkerPoolReportSnapshot? = null,
    val enrichedRuntimeRoute: com.amurcanov.tgwsproxy.RouteRuntimeState? = null,
    val cfProxyConfigured: Boolean,
    val maskDomains: Boolean,
    val fallbackEnabled: Boolean,
    val diagnosticsEnabled: Boolean = true,
    val probeUiResults: List<RouteProbeUiModel> = emptyList(),
)
