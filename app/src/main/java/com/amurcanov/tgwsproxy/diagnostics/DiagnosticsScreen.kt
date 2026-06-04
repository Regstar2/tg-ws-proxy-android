package com.amurcanov.tgwsproxy.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
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
                    CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
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
