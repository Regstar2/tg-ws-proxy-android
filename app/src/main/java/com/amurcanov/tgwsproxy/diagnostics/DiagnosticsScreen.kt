package com.amurcanov.tgwsproxy.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.MetricLine
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.RouteDisplayNames
import com.amurcanov.tgwsproxy.RouteFailureReason
import com.amurcanov.tgwsproxy.WorkerHealthStateLabels
import com.amurcanov.tgwsproxy.worker.WorkerFailoverUiMapper
import com.amurcanov.tgwsproxy.worker.WorkerHealthUiMapper
import com.amurcanov.tgwsproxy.worker.WorkerSelectionUiMapper
import com.amurcanov.tgwsproxy.worker.WorkerPoolUiState
import com.amurcanov.tgwsproxy.worker.WorkerSelectionStrategy
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsScreenState,
    onCheckAll: () -> Unit,
    onCheckDirect: () -> Unit,
    onCheckWorker: () -> Unit,
    onCheckCloudflare: () -> Unit,
    onCheckNetwork: () -> Unit,
    onCheckTelegram: () -> Unit,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit,
    workerPoolHealth: WorkerPoolHealthSummaryUi? = null,
    isCheckingWorkerHealth: Boolean = false,
    onCheckAllWorkers: (() -> Unit)? = null,
    workerPoolUiState: WorkerPoolUiState? = null,
    onOpenWorkerPool: (() -> Unit)? = null,
    workerFailoverSummary: WorkerFailoverSummaryUi? = null,
    workerSelectionSummary: WorkerSelectionSummaryUi? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isChecking) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.checkingLabel ?: stringResource(R.string.diagnostics_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.screenError) {
            Text(
                stringResource(R.string.diagnostics_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        FilledTonalButton(
            onClick = onCheckAll,
            enabled = !state.isChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_check_all))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCheckDirect, enabled = !state.isChecking) {
                Text(stringResource(R.string.diagnostics_check_direct))
            }
            OutlinedButton(onClick = onCheckWorker, enabled = !state.isChecking) {
                Text(stringResource(R.string.diagnostics_check_worker))
            }
            OutlinedButton(onClick = onCheckCloudflare, enabled = !state.isChecking) {
                Text(stringResource(R.string.diagnostics_check_cloudflare))
            }
            OutlinedButton(onClick = onCheckNetwork, enabled = !state.isChecking) {
                Text(stringResource(R.string.diagnostics_check_network))
            }
            OutlinedButton(
                onClick = onCheckTelegram,
                enabled = !state.isChecking,
            ) {
                Text(stringResource(R.string.diagnostics_check_telegram))
            }
        }

        state.runtimeRoute?.let { runtime ->
            RuntimeRouteReadOnlyBlock(runtime)
        }

        workerPoolUiState?.takeIf { it.poolEnabled && it.summary.totalCount > 0 }?.let { poolState ->
            WorkerPoolDiagnosticsSummaryBlock(
                uiState = poolState,
                isChecking = isCheckingWorkerHealth,
                onCheckAllWorkers = onCheckAllWorkers,
                onOpenWorkerPool = onOpenWorkerPool,
            )
        } ?: run {
            workerPoolHealth?.takeIf { it.workersCount > 0 }?.let { summary ->
                WorkerPoolHealthSummaryBlock(
                    summary = summary,
                    isChecking = isCheckingWorkerHealth,
                    onCheckAllWorkers = onCheckAllWorkers,
                )
            }

            workerFailoverSummary?.takeIf { it.enabledWorkersCount > 0 }?.let { summary ->
                WorkerFailoverSummaryBlock(summary = summary)
            }

            workerSelectionSummary?.takeIf {
                it.candidateCount > 0 || it.strategy != WorkerSelectionStrategy.MANUAL
            }?.let { summary ->
                WorkerSelectionSummaryBlock(summary = summary)
            }
        }

        DiagnosticReportActionsCard(
            isGenerating = state.isGeneratingReport,
            persistentLogsEnabled = state.persistentLogsEnabled,
            persistentLogsSizeLabel = state.persistentLogsSizeLabel,
            onCopyReport = onCopyReport,
            onShareReport = onShareReport,
        )

        if (!state.hasRunOnce && !state.isChecking) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.diagnostics_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.diagnostics_empty_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            state.lastRunAtMs?.let { at ->
                val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(at))
                Text(
                    stringResource(R.string.diagnostics_last_run, formatted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.results.forEach { card ->
                RouteProbeResultCard(card)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticReportActionsCard(
    isGenerating: Boolean,
    persistentLogsEnabled: Boolean,
    persistentLogsSizeLabel: String,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.diagnostic_report_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostic_report_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isGenerating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.diagnostic_report_generating),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onCopyReport,
                    enabled = !isGenerating,
                ) {
                    Text(stringResource(R.string.diagnostic_report_copy))
                }
                OutlinedButton(
                    onClick = onShareReport,
                    enabled = !isGenerating,
                ) {
                    Text(stringResource(R.string.diagnostic_report_share))
                }
            }
            Text(
                stringResource(
                    if (persistentLogsEnabled) {
                        R.string.persistent_logs_enabled
                    } else {
                        R.string.persistent_logs_disabled
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (persistentLogsSizeLabel.isNotBlank()) {
                Text(
                    stringResource(R.string.persistent_logs_size, persistentLogsSizeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.persistent_logs_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkerPoolDiagnosticsSummaryBlock(
    uiState: WorkerPoolUiState,
    isChecking: Boolean,
    onCheckAllWorkers: (() -> Unit)?,
    onOpenWorkerPool: (() -> Unit)?,
) {
    val summary = uiState.summary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.worker_health_summary_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_pool_strategy_current),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(uiState.strategy.labelRes),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.worker_pool_selected_worker),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(summary.selectedWorkerName, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.worker_pool_runtime_worker),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(summary.runtimeWorkerName, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.worker_health_summary_healthy, summary.healthyCount),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.worker_health_summary_degraded, summary.degradedCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_dead, summary.deadCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_disabled, summary.disabledCount),
                style = MaterialTheme.typography.bodySmall,
            )
            summary.lastCheckTimeLabel?.let { checked ->
                Text(
                    stringResource(R.string.worker_health_summary_last_check, checked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            summary.lastFailoverReasonLabel?.let { reason ->
                Text(
                    stringResource(R.string.worker_pool_last_failover_reason),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
            onCheckAllWorkers?.let { action ->
                OutlinedButton(
                    onClick = action,
                    enabled = !isChecking && summary.enabledCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        if (isChecking) {
                            stringResource(R.string.worker_health_checking)
                        } else {
                            stringResource(R.string.worker_pool_check_all_workers)
                        },
                    )
                }
            }
            onOpenWorkerPool?.let { open ->
                TextButton(
                    onClick = open,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.worker_pool_open))
                }
            }
        }
    }
}

@Composable
private fun WorkerPoolHealthSummaryBlock(
    summary: WorkerPoolHealthSummaryUi,
    isChecking: Boolean,
    onCheckAllWorkers: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.worker_health_summary_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_health_summary_count, summary.workersCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_healthy, summary.healthyCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_degraded, summary.degradedCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_dead, summary.deadCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_health_summary_disabled, summary.disabledCount),
                style = MaterialTheme.typography.bodySmall,
            )
            summary.selectedWorkerState?.let { workerState ->
                Text(
                    stringResource(
                        R.string.worker_health_summary_selected_state,
                        stringResource(WorkerHealthUiMapper.stateLabelRes(workerState)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            summary.lastHealthCheckAtMs?.let { at ->
                val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(at))
                Text(
                    stringResource(R.string.worker_health_summary_last_check, formatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            onCheckAllWorkers?.let { action ->
                OutlinedButton(
                    onClick = action,
                    enabled = !isChecking && summary.workersCount > summary.disabledCount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        if (isChecking) {
                            stringResource(R.string.worker_health_checking)
                        } else {
                            stringResource(R.string.worker_health_check_all)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkerFailoverSummaryBlock(summary: WorkerFailoverSummaryUi) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.worker_failover_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_failover_summary_enabled, summary.enabledWorkersCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_failover_summary_candidates, summary.candidateCount),
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.selectedWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_selected_worker),
                    summary.selectedWorkerName,
                )
            }
            if (summary.runtimeWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_runtime_worker),
                    summary.runtimeWorkerName,
                )
            }
            if (summary.lastSuccessfulWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_last_successful_worker),
                    summary.lastSuccessfulWorkerName,
                )
            }
            if (summary.lastFailedWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_last_failed_worker),
                    summary.lastFailedWorkerName,
                )
            }
            if (summary.failoverReason.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_reason),
                    WorkerFailoverUiMapper.reasonLabel(context, summary.failoverReason),
                )
            }
            if (summary.attemptCount > 0) {
                MetricLine(
                    stringResource(R.string.worker_failover_attempts),
                    summary.attemptCount.toString(),
                )
            }
        }
    }
}

@Composable
private fun WorkerSelectionSummaryBlock(summary: WorkerSelectionSummaryUi) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.worker_selection_strategy_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(
                stringResource(R.string.worker_selection_current_strategy),
                stringResource(WorkerSelectionUiMapper.strategyLabelRes(summary.strategy)),
            )
            MetricLine(
                stringResource(R.string.worker_selection_last_reason),
                WorkerSelectionUiMapper.reasonLabel(context, summary.selectionReason),
            )
            MetricLine(
                stringResource(R.string.worker_selection_candidate_count),
                summary.candidateCount.toString(),
            )
            if (summary.runtimeWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_selection_runtime_worker),
                    summary.runtimeWorkerName,
                )
            }
            if (summary.candidateNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.worker_selection_candidate_order),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                summary.candidateNames.forEachIndexed { index, name ->
                    Text(
                        "${index + 1}. $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeRouteReadOnlyBlock(runtime: RuntimeRouteUiModel) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.diagnostics_runtime_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(
                stringResource(R.string.route_runtime_configured_mode),
                RouteDisplayNames.modeLabel(context, runtime.configuredMode),
            )
            MetricLine(
                stringResource(R.string.route_runtime_selected_route),
                RouteDisplayNames.currentRouteLabel(context, runtime.selectedRoute, true),
            )
            MetricLine(
                stringResource(R.string.route_runtime_active_route),
                RouteDisplayNames.currentRouteLabel(context, runtime.activeRoute, true),
            )
            MetricLine(
                stringResource(R.string.route_runtime_last_success),
                RouteDisplayNames.routeLabel(context, runtime.lastSuccessfulRoute),
            )
            MetricLine(
                stringResource(R.string.route_runtime_last_failure),
                RouteDisplayNames.routeLabel(context, runtime.lastFailedRoute),
            )
            MetricLine(
                stringResource(R.string.route_runtime_fallback_reason),
                RouteFailureReason.label(context, runtime.fallbackReason),
            )
            if (runtime.currentWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.route_runtime_current_worker),
                    runtime.currentWorkerName,
                )
                runtime.currentWorkerState?.let { state ->
                    MetricLine(
                        stringResource(R.string.route_runtime_worker_state),
                        WorkerHealthStateLabels.label(context, state),
                    )
                }
            }
            if (runtime.selectedWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_selected_worker),
                    runtime.selectedWorkerName,
                )
            }
            if (runtime.runtimeWorkerName.isNotBlank()) {
                MetricLine(
                    stringResource(R.string.worker_failover_runtime_worker),
                    runtime.runtimeWorkerName,
                )
            }
            if (runtime.workerFailoverReason.isNotBlank() || runtime.workerFailoverActive) {
                MetricLine(
                    stringResource(R.string.worker_failover_active),
                    stringResource(
                        if (runtime.workerFailoverActive) {
                            R.string.worker_failover_enabled
                        } else {
                            R.string.worker_failover_inactive
                        },
                    ),
                )
                if (runtime.workerFailoverReason.isNotBlank()) {
                    MetricLine(
                        stringResource(R.string.worker_failover_reason),
                        WorkerFailoverUiMapper.reasonLabel(context, runtime.workerFailoverReason),
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteProbeResultCard(model: RouteProbeUiModel) {
    val context = LocalContext.current
    var detailsExpanded by rememberSaveable(model.target.name) { mutableStateOf(false) }
    val statusColor = when (model.status) {
        RouteProbeStatus.OK -> MaterialTheme.colorScheme.primary
        RouteProbeStatus.FAIL, RouteProbeStatus.TIMEOUT -> MaterialTheme.colorScheme.error
        RouteProbeStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(RouteProbeUiMapper.targetLabelRes(model.target)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(
                stringResource(R.string.diagnostics_status_label),
                stringResource(RouteProbeUiMapper.statusLabelRes(model.status)),
                valueColor = statusColor,
            )
            model.latencyMs?.let { ms ->
                MetricLine(
                    stringResource(R.string.diagnostics_latency),
                    context.getString(R.string.diagnostics_latency_ms, ms),
                )
            }
            if (model.errorCode != com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode.NONE) {
                MetricLine(
                    stringResource(R.string.diagnostics_error_code_label),
                    stringResource(RouteProbeUiMapper.errorCodeLabelRes(model.errorCode)),
                )
            }
            model.lastCheckedAtMs?.let { at ->
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
                MetricLine(stringResource(R.string.diagnostics_last_checked), time)
            }
            if (model.shortDetails.isNotBlank()) {
                Text(
                    model.shortDetails,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (model.steps.isNotEmpty()) {
                val summary = model.steps.joinToString(" · ") { step ->
                    val stepName = context.getString(RouteProbeUiMapper.stepLabelRes(step.step))
                    val st = context.getString(RouteProbeUiMapper.statusLabelRes(step.status))
                    "$stepName: $st"
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(
                        if (detailsExpanded) {
                            stringResource(R.string.diagnostics_hide_details)
                        } else {
                            stringResource(R.string.diagnostics_details)
                        },
                    )
                }
                AnimatedVisibility(visible = detailsExpanded) {
                    Column {
                        model.steps.forEach { step ->
                            val line = buildString {
                                append(context.getString(RouteProbeUiMapper.stepLabelRes(step.step)))
                                append(": ")
                                append(context.getString(RouteProbeUiMapper.statusLabelRes(step.status)))
                                if (step.latencyMs > 0) {
                                    append(" (")
                                    append(step.latencyMs)
                                    append(" ms)")
                                }
                                if (step.errorCode != com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode.NONE) {
                                    append(" — ")
                                    append(context.getString(RouteProbeUiMapper.errorCodeLabelRes(step.errorCode)))
                                }
                            }
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            if (model.target == RouteProbeTarget.TELEGRAM_REACHABILITY &&
                model.status == RouteProbeStatus.UNSUPPORTED
            ) {
                Text(
                    stringResource(R.string.diagnostics_telegram_unsupported_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}
