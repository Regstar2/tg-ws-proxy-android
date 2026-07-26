package com.amurcanov.tgwsproxy.diagnostics

import android.content.Context
import android.util.Log
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ProxyRuntimeState
import com.amurcanov.tgwsproxy.RouteRuntimeState
import com.amurcanov.tgwsproxy.routeprobe.RouteDiagnosticsRepository
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeRequest
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget
import com.amurcanov.tgwsproxy.worker.WorkerPoolReportSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class DiagnosticsViewModel(
    private val repository: RouteDiagnosticsRepository = RouteDiagnosticsRepository(),
) {
    private val _state = MutableStateFlow(DiagnosticsScreenState())
    val state: StateFlow<DiagnosticsScreenState> = _state.asStateFlow()

    private val _reportEvents = MutableSharedFlow<DiagnosticReportEvent>(extraBufferCapacity = 1)
    val reportEvents: SharedFlow<DiagnosticReportEvent> = _reportEvents.asSharedFlow()

    fun updatePersistentLogsState(enabled: Boolean, sizeLabel: String) {
        _state.update { it.copy(persistentLogsEnabled = enabled, persistentLogsSizeLabel = sizeLabel) }
    }

    fun syncRuntimeRoute(routeState: RouteRuntimeState? = null) {
        val runtime = routeState ?: ProxyRuntimeState.uiMetrics.value.runtime.routeRuntime
        _state.update { it.copy(runtimeRoute = RuntimeRouteUiModel.from(runtime)) }
    }

    suspend fun refreshFrontendDiagnostics(
        context: Context,
        workerPoolSnapshot: WorkerPoolReportSnapshot? = null,
    ) {
        val snapshot = withContext(Dispatchers.IO) {
            FrontendDiagnosticsSource.read(context, workerPoolSnapshot)
        }
        _state.update { it.copy(frontendDiagnostics = snapshot) }
    }

    fun clearScreenError() {
        _state.update { it.copy(screenError = false) }
    }

    suspend fun runAll(context: Context, request: RouteProbeRequest, checkingLabel: String) {
        runTargets(context, request, RouteDiagnosticsRepository.DIAGNOSTICS_SCREEN_TARGETS, checkingLabel)
    }

    suspend fun runTarget(context: Context, request: RouteProbeRequest, target: RouteProbeTarget, checkingLabel: String) {
        runTargets(context, request, listOf(target), checkingLabel)
    }

    private suspend fun runTargets(
        context: Context,
        request: RouteProbeRequest,
        targets: List<RouteProbeTarget>,
        checkingLabel: String,
    ) {
        if (_state.value.isChecking) return
        Log.i(TAG, "Diagnostics run started: targets=${targets.map { it.name }}")
        _state.update {
            it.copy(
                isChecking = true,
                checkingLabel = checkingLabel,
                screenError = false,
            )
        }
        syncRuntimeRoute()
        val accumulated = mutableListOf<RouteProbeUiModel>()
        try {
            targets.forEach { target ->
                val result = repository.runProbe(context, target, request)
                val ui = RouteProbeUiMapper.toUiModel(result)
                accumulated.add(ui)
                _state.update { current ->
                    current.copy(
                        results = mergeResults(current.results, accumulated),
                        hasRunOnce = true,
                    )
                }
                logTargetResult(result)
            }
            val ok = accumulated.count { it.status == RouteProbeStatus.OK }
            val fail = accumulated.count {
                it.status == RouteProbeStatus.FAIL || it.status == RouteProbeStatus.TIMEOUT
            }
            val timeout = accumulated.count { it.status == RouteProbeStatus.TIMEOUT }
            Log.i(
                TAG,
                "Diagnostics run finished: okCount=$ok, failCount=$fail, timeoutCount=$timeout",
            )
            _state.update {
                it.copy(
                    lastRunAtMs = System.currentTimeMillis(),
                    results = mergeResults(emptyList(), accumulated),
                    hasRunOnce = true,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostics run failed", e)
            _state.update { it.copy(screenError = true) }
        } finally {
            _state.update { it.copy(isChecking = false, checkingLabel = null) }
        }
    }

    fun loadFromRepository() {
        val stored = repository.getLastProbeResults()
        if (stored.isEmpty()) return
        _state.update {
            it.copy(
                hasRunOnce = true,
                results = stored.map(RouteProbeUiMapper::toUiModel),
                lastRunAtMs = stored.maxOfOrNull { r -> r.finishedAtMs },
            )
        }
    }

    suspend fun copyReport(
        context: Context,
        uiContext: DiagnosticReportUiContext,
        recentLogLines: List<String>,
    ) {
        if (_state.value.isGeneratingReport) return
        _state.update { it.copy(isGeneratingReport = true) }
        try {
            syncRuntimeRoute()
            val input = DiagnosticReportContextFactory.build(
                context = context,
                repository = repository,
                reportUi = uiContext.copy(probeUiResults = _state.value.results),
                recentLogLines = recentLogLines,
            )
            val text = DiagnosticReportGenerator.generate(context, input)
            DiagnosticReportExporter.copyToClipboard(context, text)
            _reportEvents.emit(DiagnosticReportEvent.CopySuccess)
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostic report copy failed", e)
            _reportEvents.emit(DiagnosticReportEvent.Failed(R.string.diagnostic_report_failed))
        } finally {
            _state.update { it.copy(isGeneratingReport = false) }
        }
    }

    suspend fun shareReport(
        context: Context,
        uiContext: DiagnosticReportUiContext,
        recentLogLines: List<String>,
        chooserTitle: String,
    ) {
        if (_state.value.isGeneratingReport) return
        _state.update { it.copy(isGeneratingReport = true) }
        try {
            syncRuntimeRoute()
            val input = DiagnosticReportContextFactory.build(
                context = context,
                repository = repository,
                reportUi = uiContext.copy(probeUiResults = _state.value.results),
                recentLogLines = recentLogLines,
            )
            val text = DiagnosticReportGenerator.generate(context, input)
            val shareFile = DiagnosticReportExporter.writeTempReportFile(context, text)
            val intent = DiagnosticReportExporter.shareIntent(shareFile, chooserTitle)
            _reportEvents.emit(DiagnosticReportEvent.ShareReady(intent))
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostic report share failed", e)
            _reportEvents.emit(DiagnosticReportEvent.Failed(R.string.diagnostic_report_share_failed))
        } finally {
            _state.update { it.copy(isGeneratingReport = false) }
        }
    }

    private fun mergeResults(
        existing: List<RouteProbeUiModel>,
        latest: List<RouteProbeUiModel>,
    ): List<RouteProbeUiModel> {
        val map = existing.associateBy { it.target }.toMutableMap()
        latest.forEach { map[it.target] = it }
        return RouteDiagnosticsRepository.DIAGNOSTICS_SCREEN_TARGETS.map { target ->
            map[target] ?: RouteProbeUiMapper.placeholder(target)
        }
    }

    private fun logTargetResult(result: com.amurcanov.tgwsproxy.routeprobe.RouteProbeResult) {
        Log.i(
            TAG,
            "Diagnostics target result: target=${result.target.name.lowercase()}, " +
                "status=${result.status.name}, error=${result.errorCode.name}, latencyMs=${result.latencyMs}",
        )
    }

    companion object {
        private const val TAG = "TgWsProxy"

        fun onScreenOpened() {
            Log.i(TAG, "Diagnostics screen opened")
        }
    }
}
