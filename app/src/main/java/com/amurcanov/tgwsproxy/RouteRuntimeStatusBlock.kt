package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.amurcanov.tgwsproxy.worker.WorkerFailoverUiMapper
import com.amurcanov.tgwsproxy.worker.WorkerSelectionUiMapper

@Composable
fun RouteRuntimeStatusBlock(
    routeState: RouteRuntimeState,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.route_runtime_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_configured_mode),
            routeState.configuredModeLabel(context),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_selected_route),
            routeState.selectedRouteLabel(context, running),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_active_route),
            routeState.activeRouteLabel(context, running),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_last_success),
            routeState.lastSuccessRouteLabel(context),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_last_failure),
            routeState.lastFailedRouteLabel(context),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_fallback_reason),
            routeState.fallbackReasonLabel(context),
        )
        RouteRuntimeMetricLine(
            stringResource(R.string.route_runtime_network),
            routeState.networkTypeLabel(context),
        )
        if (routeState.workerSelectionStrategy.isNotBlank()) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_selection_current_strategy),
                stringResource(
                    WorkerSelectionUiMapper.strategyLabelRes(
                        com.amurcanov.tgwsproxy.worker.WorkerSelectionStrategy.fromPref(
                            routeState.workerSelectionStrategy,
                        ),
                    ),
                ),
            )
            if (routeState.workerSelectionReason.isNotBlank()) {
                RouteRuntimeMetricLine(
                    stringResource(R.string.worker_selection_last_reason),
                    WorkerSelectionUiMapper.reasonLabel(context, routeState.workerSelectionReason),
                )
            }
            if (routeState.workerCandidateCount > 0) {
                RouteRuntimeMetricLine(
                    stringResource(R.string.worker_selection_candidate_count),
                    routeState.workerCandidateCount.toString(),
                )
            }
        }
        if (routeState.selectedWorkerId.isNotBlank() || routeState.selectedWorkerName.isNotBlank()) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_failover_selected_worker),
                routeState.selectedWorkerName.ifBlank { routeState.selectedWorkerId },
            )
        }
        if (routeState.currentWorkerId.isNotBlank() ||
            routeState.currentWorkerName.isNotBlank() ||
            routeState.currentWorkerDomain.isNotBlank()
        ) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_failover_runtime_worker),
                routeState.currentWorkerLabel(context),
            )
            RouteRuntimeMetricLine(
                stringResource(R.string.route_runtime_worker_state),
                routeState.currentWorkerStateLabel(context),
            )
        }
        if (routeState.lastSuccessfulWorkerName.isNotBlank() || routeState.lastSuccessfulWorkerId.isNotBlank()) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_failover_last_successful_worker),
                routeState.lastSuccessfulWorkerName.ifBlank { routeState.lastSuccessfulWorkerId },
            )
        }
        if (routeState.lastFailedWorkerName.isNotBlank() || routeState.lastFailedWorkerId.isNotBlank()) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_failover_last_failed_worker),
                routeState.lastFailedWorkerName.ifBlank { routeState.lastFailedWorkerId },
            )
        }
        if (routeState.workerFailoverActive || routeState.workerFailoverReason.isNotBlank()) {
            RouteRuntimeMetricLine(
                stringResource(R.string.worker_failover_active),
                stringResource(
                    if (routeState.workerFailoverActive) {
                        R.string.worker_failover_enabled
                    } else {
                        R.string.worker_failover_inactive
                    },
                ),
            )
            if (routeState.workerFailoverReason.isNotBlank()) {
                RouteRuntimeMetricLine(
                    stringResource(R.string.worker_failover_reason),
                    WorkerFailoverUiMapper.reasonLabel(context, routeState.workerFailoverReason),
                )
            }
            if (routeState.workerFailoverAttemptCount > 0) {
                RouteRuntimeMetricLine(
                    stringResource(R.string.worker_failover_attempts),
                    routeState.workerFailoverAttemptCount.toString(),
                )
            }
        }
    }
}

@Composable
private fun RouteRuntimeMetricLine(label: String, value: String) {
    MetricLine(label, value)
}
