package com.amurcanov.tgwsproxy.worker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.RouteDisplayNames
import com.amurcanov.tgwsproxy.WorkerDomain
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkerPoolSettingsScreen(
    uiState: WorkerPoolUiState,
    legacyWorkerDomainText: String,
    legacyWorkerEnabled: Boolean,
    legacyWorkerNormalizeHint: String?,
    existingWorkerUrls: List<String> = emptyList(),
    workerDestinationMode: WorkerDestinationMode = WorkerDestinationMode.PRESERVE_ORIGINAL_DST,
    flowsealMediaFixEnabled: Boolean = false,
    flowsealMediaFixDcText: String = WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC.toString(),
    flowsealMediaFixIpText: String = WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP,
    flowsealDcOnlyEnabled: Boolean = false,
    onFlowsealDcOnlyEnabledChange: (Boolean) -> Unit = {},
    onWorkerDestinationModeChange: (WorkerDestinationMode) -> Unit = {},
    onFlowsealMediaFixEnabledChange: (Boolean) -> Unit = {},
    onFlowsealMediaFixDcChange: (String) -> Unit = {},
    onFlowsealMediaFixIpChange: (String) -> Unit = {},
    onFlowsealDestinationSettingsBlur: () -> Unit = {},
    onScreenOpened: () -> Unit = {},
    onPoolEnabledChange: (Boolean) -> Unit,
    onSelectionStrategyChange: (WorkerSelectionStrategy) -> Unit,
    onSelectWorker: (String) -> Unit,
    onAddWorker: (name: String, url: String, enabled: Boolean, priority: Int) -> WorkerValidationError?,
    onUpdateWorker: (WorkerEndpoint) -> WorkerValidationError?,
    onDeleteWorker: (String) -> Unit,
    onSetWorkerEnabled: (String, Boolean) -> Unit,
    onCheckWorker: (String) -> Unit,
    onCheckAllWorkers: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onLegacyWorkerDomainChange: (String) -> Unit,
    onLegacyWorkerDomainBlur: () -> Unit,
    onLegacyWorkerEnabledChange: (Boolean) -> Unit,
    onTestLegacyWorker: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var deletingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var disablingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var strategyExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onScreenOpened()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        WorkerPoolEnableRow(
            poolEnabled = uiState.poolEnabled,
            isProxyRunning = uiState.isProxyRunning,
            onPoolEnabledChange = onPoolEnabledChange,
        )

        Spacer(modifier = Modifier.height(12.dp))

        WorkerPoolFlowsealDcOnlySection(
            enabled = flowsealDcOnlyEnabled,
            isProxyRunning = uiState.isProxyRunning,
            onEnabledChange = onFlowsealDcOnlyEnabledChange,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.poolEnabled) {
            WorkerPoolSummarySection(uiState.summary)
            Spacer(modifier = Modifier.height(10.dp))
            WorkerPoolStrategySection(
                strategy = uiState.strategy,
                expanded = strategyExpanded,
                isProxyRunning = uiState.isProxyRunning,
                onToggleExpanded = { strategyExpanded = !strategyExpanded },
                onStrategyChange = onSelectionStrategyChange,
            )
            Spacer(modifier = Modifier.height(10.dp))
            WorkerPoolDestinationModeSection(
                destinationMode = workerDestinationMode,
                mediaFixEnabled = flowsealMediaFixEnabled,
                mediaFixDcText = flowsealMediaFixDcText,
                mediaFixIpText = flowsealMediaFixIpText,
                isProxyRunning = uiState.isProxyRunning,
                onDestinationModeChange = onWorkerDestinationModeChange,
                onMediaFixEnabledChange = onFlowsealMediaFixEnabledChange,
                onMediaFixDcChange = onFlowsealMediaFixDcChange,
                onMediaFixIpChange = onFlowsealMediaFixIpChange,
                onSettingsBlur = onFlowsealDestinationSettingsBlur,
            )
            Spacer(modifier = Modifier.height(10.dp))
            WorkerPoolRuntimeSection(uiState.runtime, context)
            Spacer(modifier = Modifier.height(10.dp))
            uiState.configWarning?.let { warning ->
                WorkerPoolWarningBanner(warning)
                Spacer(modifier = Modifier.height(10.dp))
            }
            WorkerPoolQuickActions(
                uiState = uiState,
                onCheckAllWorkers = onCheckAllWorkers,
                onAddWorker = { showAddDialog = true },
                onOpenDiagnostics = onOpenDiagnostics,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (uiState.contentState) {
                WorkerPoolContentState.EMPTY -> WorkerPoolEmptyState(onAddWorker = { showAddDialog = true })
                WorkerPoolContentState.NO_ENABLED -> WorkerPoolNoEnabledState(onAddWorker = { showAddDialog = true })
                WorkerPoolContentState.INVALID_CONFIG,
                WorkerPoolContentState.CONTENT,
                -> {
                    uiState.workers.forEach { item ->
                        WorkerPoolItemCardPolished(
                            item = item,
                            isChecking = uiState.checkingWorkerIds.contains(item.id),
                            isCheckingAll = uiState.isCheckingAllWorkers,
                            onSelect = { onSelectWorker(item.id) },
                            onEdit = {
                                WorkerPoolUiLogger.workerEditOpened(item.id)
                                editingWorker = item
                            },
                            onDelete = { deletingWorker = item },
                            onCheck = { onCheckWorker(item.id) },
                            onDisable = { disablingWorker = item },
                            onEnable = { onSetWorkerEnabled(item.id, true) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        } else {
            LegacyWorkerCard(
                workerDomainText = legacyWorkerDomainText,
                workerEnabled = legacyWorkerEnabled,
                workerNormalizeHint = legacyWorkerNormalizeHint,
                isProxyRunning = uiState.isProxyRunning,
                isDiagRunning = uiState.isDiagRunning,
                onWorkerDomainChange = onLegacyWorkerDomainChange,
                onWorkerDomainBlur = onLegacyWorkerDomainBlur,
                onWorkerEnabledChange = onLegacyWorkerEnabledChange,
                onTestWorker = onTestLegacyWorker,
                onOpenWorkerHelp = onOpenWorkerHelp,
            )
        }
    }

    if (showAddDialog) {
        WorkerEditDialog(
            titleRes = R.string.worker_pool_add_worker,
            initialName = "",
            initialUrl = "",
            initialEnabled = true,
            initialPriority = 0,
            existingUrls = existingWorkerUrls,
            onDismiss = { showAddDialog = false },
            onSave = { name, url, enabled, priority ->
                val error = onAddWorker(name, url, enabled, priority)
                if (error == null) showAddDialog = false
                error
            },
        )
    }

    editingWorker?.let { item ->
        WorkerEditDialog(
            titleRes = R.string.worker_pool_edit_worker,
            initialName = item.name,
            initialUrl = item.endpoint.url,
            initialEnabled = item.enabled,
            initialPriority = item.priority,
            existingUrls = existingWorkerUrls.filter { it != item.endpoint.url },
            onDismiss = { editingWorker = null },
            onSave = { name, url, enabled, priority ->
                val error = onUpdateWorker(
                    item.endpoint.copy(
                        name = name,
                        url = url,
                        enabled = enabled,
                        priority = priority,
                        state = if (enabled) {
                            if (item.endpoint.state == WorkerHealthState.DISABLED) {
                                WorkerHealthState.UNKNOWN
                            } else {
                                item.endpoint.state
                            }
                        } else {
                            WorkerHealthState.DISABLED
                        },
                    ),
                )
                if (error == null) editingWorker = null
                error
            },
        )
    }

    deletingWorker?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingWorker = null },
            title = { Text(stringResource(R.string.worker_pool_delete_confirmation_title)) },
            text = { Text(stringResource(R.string.worker_pool_delete_confirmation_message, item.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteWorker(item.id)
                        deletingWorker = null
                    },
                ) {
                    Text(stringResource(R.string.worker_pool_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingWorker = null }) {
                    Text(stringResource(R.string.worker_pool_cancel))
                }
            },
        )
    }

    disablingWorker?.let { item ->
        val enabledAfter = uiState.workers.count { it.enabled && it.id != item.id }
        AlertDialog(
            onDismissRequest = { disablingWorker = null },
            title = { Text(stringResource(R.string.worker_pool_disable_selected_confirmation_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.worker_pool_disable_selected_confirmation_message))
                    if (item.isSelected && enabledAfter == 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.worker_pool_no_enabled_after_disable_warning),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetWorkerEnabled(item.id, false)
                        disablingWorker = null
                    },
                ) {
                    Text(stringResource(R.string.worker_pool_disable_worker))
                }
            },
            dismissButton = {
                TextButton(onClick = { disablingWorker = null }) {
                    Text(stringResource(R.string.worker_pool_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkerPoolEnableRow(
    poolEnabled: Boolean,
    isProxyRunning: Boolean,
    onPoolEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.worker_pool_enable), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (poolEnabled) R.string.worker_pool_enabled else R.string.worker_pool_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = poolEnabled, enabled = !isProxyRunning, onCheckedChange = onPoolEnabledChange)
    }
}

@Composable
private fun WorkerPoolSummarySection(summary: WorkerPoolSummaryUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.worker_pool_summary_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_enable),
                stringResource(summary.poolEnabledLabelRes),
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_strategy_current),
                stringResource(summary.strategyLabelRes),
            )
            Text(
                stringResource(R.string.worker_pool_workers_total, summary.totalCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_pool_workers_enabled_count, summary.enabledCount),
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.healthyCount > 0) {
                Text(
                    stringResource(R.string.worker_pool_workers_healthy_count, summary.healthyCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (summary.degradedCount > 0) {
                Text(
                    stringResource(R.string.worker_pool_workers_degraded_count, summary.degradedCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (summary.deadCount > 0) {
                Text(
                    stringResource(R.string.worker_pool_workers_dead_count, summary.deadCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (summary.unknownCount > 0) {
                Text(
                    stringResource(R.string.worker_pool_workers_unknown_count, summary.unknownCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_selected_worker),
                summary.selectedWorkerName,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_runtime_worker),
                summary.runtimeWorkerName,
            )
            summary.lastCheckTimeLabel?.let { checked ->
                Text(
                    stringResource(R.string.worker_pool_last_checked, checked),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            summary.lastFailoverReasonLabel?.let { reason ->
                com.amurcanov.tgwsproxy.MetricLine(
                    stringResource(R.string.worker_pool_last_failover_reason),
                    reason,
                )
            }
        }
    }
}

@Composable
private fun WorkerPoolStrategySection(
    strategy: WorkerStrategyUiModel,
    expanded: Boolean,
    isProxyRunning: Boolean,
    onToggleExpanded: () -> Unit,
    onStrategyChange: (WorkerSelectionStrategy) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.worker_pool_strategy_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onToggleExpanded) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.worker_pool_details_hide)
                        } else {
                            stringResource(R.string.worker_pool_details)
                        },
                    )
                }
            }
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_strategy_current),
                stringResource(strategy.labelRes),
            )
            Text(
                stringResource(strategy.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            AnimatedVisibility(visible = expanded) {
                Column {
                    WorkerSelectionStrategy.entries.forEach { entry ->
                        val selected = entry == strategy.strategy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                enabled = !isProxyRunning,
                                onClick = { if (!selected) onStrategyChange(entry) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(WorkerSelectionUiMapper.strategyLabelRes(entry)))
                                Text(
                                    stringResource(WorkerSelectionUiMapper.strategyDescriptionRes(entry)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerPoolRuntimeSection(runtime: WorkerRuntimeUiModel, context: android.content.Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.worker_pool_runtime_state_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.route_runtime_configured_mode),
                RouteDisplayNames.modeLabel(context, runtime.configuredMode),
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.route_runtime_selected_route),
                RouteDisplayNames.routeLabel(context, runtime.selectedRoute),
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.route_runtime_active_route),
                RouteDisplayNames.routeLabel(context, runtime.activeRoute),
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_selected_worker),
                runtime.selectedWorkerName,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_runtime_worker),
                runtime.runtimeWorkerName,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_last_successful_worker),
                runtime.lastSuccessfulWorkerName,
            )
            com.amurcanov.tgwsproxy.MetricLine(
                stringResource(R.string.worker_pool_last_failed_worker),
                runtime.lastFailedWorkerName,
            )
            if (runtime.failoverReason.isNotBlank()) {
                com.amurcanov.tgwsproxy.MetricLine(
                    stringResource(R.string.worker_pool_last_failover_reason),
                    runtime.failoverReason,
                )
            }
            if (runtime.failoverAttemptCount > 0) {
                com.amurcanov.tgwsproxy.MetricLine(
                    stringResource(R.string.worker_failover_attempts),
                    runtime.failoverAttemptCount.toString(),
                )
            }
        }
    }
}

@Composable
private fun WorkerPoolWarningBanner(warning: WorkerPoolConfigWarning) {
    val (titleRes, descRes) = when (warning) {
        WorkerPoolConfigWarning.NO_ENABLED_WORKERS ->
            R.string.worker_pool_no_enabled_title to R.string.worker_pool_no_enabled_description
        WorkerPoolConfigWarning.SELECTED_NOT_FOUND,
        WorkerPoolConfigWarning.SELECTED_DISABLED,
        WorkerPoolConfigWarning.SELECTED_UNAVAILABLE,
        ->
            R.string.worker_pool_invalid_config_title to R.string.worker_pool_invalid_config_description
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(titleRes), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WorkerPoolQuickActions(
    uiState: WorkerPoolUiState,
    onCheckAllWorkers: () -> Unit,
    onAddWorker: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onAddWorker,
            enabled = !uiState.isProxyRunning,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.worker_pool_add_worker))
        }
        OutlinedButton(
            onClick = onCheckAllWorkers,
            enabled = !uiState.isProxyRunning && !uiState.isCheckingAllWorkers &&
                uiState.workers.any { it.enabled },
            modifier = Modifier.weight(1f),
        ) {
            if (uiState.isCheckingAllWorkers) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 6.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (uiState.isCheckingAllWorkers) {
                    stringResource(R.string.worker_health_checking)
                } else {
                    stringResource(R.string.worker_pool_check_all_workers)
                },
            )
        }
    }
    OutlinedButton(
        onClick = onOpenDiagnostics,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(stringResource(R.string.worker_pool_open_diagnostics))
    }
}

@Composable
private fun WorkerPoolEmptyState(onAddWorker: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.worker_pool_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_pool_empty_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FilledTonalButton(onClick = onAddWorker) {
                Text(stringResource(R.string.worker_pool_add_worker))
            }
        }
    }
}

@Composable
private fun WorkerPoolNoEnabledState(onAddWorker: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.worker_pool_no_enabled_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_pool_no_enabled_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FilledTonalButton(onClick = onAddWorker) {
                Text(stringResource(R.string.worker_pool_add_worker))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkerPoolItemCardPolished(
    item: WorkerItemUiModel,
    isChecking: Boolean,
    isCheckingAll: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.maskedUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(
                            R.string.worker_pool_worker_status_line,
                            stringResource(item.enabledLabelRes),
                            stringResource(item.healthStateLabelRes),
                            item.latencyMs?.let { stringResource(R.string.worker_pool_latency, it) }
                                ?: stringResource(R.string.worker_pool_status_unknown),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (item.canSelect) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.worker_pool_select_worker)) },
                            onClick = { menuExpanded = false; onSelect() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.worker_pool_check_worker)) },
                        enabled = item.canCheck && !isChecking && !isCheckingAll,
                        onClick = { menuExpanded = false; onCheck() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.worker_pool_edit_worker)) },
                        enabled = item.canEdit,
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    if (item.enabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.worker_pool_disable_worker)) },
                            enabled = item.canEdit,
                            onClick = { menuExpanded = false; onDisable() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.worker_pool_enable_worker)) },
                            enabled = item.canEdit,
                            onClick = { menuExpanded = false; onEnable() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.worker_pool_delete_worker)) },
                        enabled = item.canDelete,
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
            if (item.tagLabelResIds.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.tagLabelResIds.forEach { tagRes ->
                        AssistChip(onClick = {}, label = { Text(stringResource(tagRes)) })
                    }
                }
            }
            item.lastCheckedLabel?.let { checked ->
                Text(
                    stringResource(R.string.worker_pool_last_checked, checked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.enabled) {
                Text(
                    stringResource(R.string.worker_health_skipped_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                item.lastErrorLabelRes?.let { errorRes ->
                    Text(
                        stringResource(R.string.worker_pool_last_error, stringResource(errorRes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (item.failureCount > 0) {
                    Text(
                        stringResource(R.string.worker_pool_failures, item.failureCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyWorkerCard(
    workerDomainText: String,
    workerEnabled: Boolean,
    workerNormalizeHint: String?,
    isProxyRunning: Boolean,
    isDiagRunning: Boolean,
    onWorkerDomainChange: (String) -> Unit,
    onWorkerDomainBlur: () -> Unit,
    onWorkerEnabledChange: (Boolean) -> Unit,
    onTestWorker: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.cf_worker_pool_legacy_worker),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = workerDomainText,
                onValueChange = onWorkerDomainChange,
                enabled = !isProxyRunning,
                label = { Text(stringResource(R.string.cf_worker_domain_label)) },
                placeholder = { Text(stringResource(R.string.worker_domain_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) onWorkerDomainBlur() },
                singleLine = true,
            )
            workerNormalizeHint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cf_worker_enable))
                Switch(checked = workerEnabled, enabled = !isProxyRunning, onCheckedChange = onWorkerEnabledChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTestWorker,
                    enabled = !isProxyRunning && !isDiagRunning,
                ) {
                    Text(stringResource(R.string.cf_worker_test))
                }
                TextButton(onClick = onOpenWorkerHelp) {
                    Text(stringResource(R.string.cf_worker_help_link))
                }
            }
        }
    }
}

@Composable
private fun WorkerEditDialog(
    titleRes: Int,
    initialName: String,
    initialUrl: String,
    initialEnabled: Boolean,
    initialPriority: Int,
    existingUrls: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, enabled: Boolean, priority: Int) -> WorkerValidationError?,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    var priorityText by rememberSaveable { mutableStateOf(initialPriority.toString()) }
    var validationError by remember { mutableStateOf<WorkerValidationError?>(null) }
    val normalizedUrl = remember(url) { WorkerDomain.normalize(url) }
    val duplicateUrl = normalizedUrl.isNotBlank() &&
        existingUrls.any { WorkerDomain.normalize(it).equals(normalizedUrl, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validationError = null },
                    label = { Text(stringResource(R.string.worker_pool_worker_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; validationError = null },
                    label = { Text(stringResource(R.string.worker_pool_worker_url)) },
                    placeholder = { Text(stringResource(R.string.worker_domain_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duplicateUrl) {
                    Text(
                        stringResource(R.string.worker_pool_duplicate_url_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it.filter { ch -> ch.isDigit() || ch == '-' }; validationError = null },
                    label = { Text(stringResource(R.string.worker_pool_worker_priority)) },
                    supportingText = { Text(stringResource(R.string.worker_pool_worker_priority_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.worker_pool_worker_enabled))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                validationError?.let { error ->
                    Text(
                        when (error) {
                            WorkerValidationError.EMPTY_NAME -> stringResource(R.string.worker_pool_empty_name)
                            WorkerValidationError.EMPTY_URL,
                            WorkerValidationError.INVALID_URL,
                            -> stringResource(R.string.worker_pool_invalid_url)
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (duplicateUrl) return@TextButton
                    val priority = priorityText.toIntOrNull() ?: 0
                    validationError = onSave(name.trim(), url.trim(), enabled, priority)
                },
            ) {
                Text(stringResource(R.string.worker_pool_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.worker_pool_cancel))
            }
        },
    )
}

@Composable
private fun WorkerPoolFlowsealDcOnlySection(
    enabled: Boolean,
    isProxyRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.flowseal_dc_only_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.flowseal_dc_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !isProxyRunning,
                )
            }
            if (enabled) {
                Text(
                    stringResource(R.string.flowseal_dc_only_active_value, FlowsealDcPreset.FLOWSEAL_DC, FlowsealDcPreset.FLOWSEAL_IP),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WorkerPoolDestinationModeSection(
    destinationMode: WorkerDestinationMode,
    mediaFixEnabled: Boolean,
    mediaFixDcText: String,
    mediaFixIpText: String,
    isProxyRunning: Boolean,
    onDestinationModeChange: (WorkerDestinationMode) -> Unit,
    onMediaFixEnabledChange: (Boolean) -> Unit,
    onMediaFixDcChange: (String) -> Unit,
    onMediaFixIpChange: (String) -> Unit,
    onSettingsBlur: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.worker_destination_mode_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_destination_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            WorkerDestinationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = destinationMode == mode,
                        onClick = { if (!isProxyRunning) onDestinationModeChange(mode) },
                        enabled = !isProxyRunning,
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            when (mode) {
                                WorkerDestinationMode.PRESERVE_ORIGINAL_DST ->
                                    stringResource(R.string.worker_destination_mode_preserve)
                                WorkerDestinationMode.FLOWSEAL_DC_MAP ->
                                    stringResource(R.string.worker_destination_mode_dc_map)
                                WorkerDestinationMode.FLOWSEAL_MEDIA_DC4_FIX ->
                                    stringResource(R.string.worker_destination_mode_media_dc4_fix)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (destinationMode == WorkerDestinationMode.FLOWSEAL_MEDIA_DC4_FIX) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.flowseal_media_fix_enabled))
                    Switch(
                        checked = mediaFixEnabled,
                        onCheckedChange = onMediaFixEnabledChange,
                        enabled = !isProxyRunning,
                    )
                }
                OutlinedTextField(
                    value = mediaFixDcText,
                    onValueChange = {
                        if (it.all { ch -> ch.isDigit() }) onMediaFixDcChange(it)
                    },
                    label = { Text(stringResource(R.string.flowseal_media_fix_dc)) },
                    enabled = !isProxyRunning && mediaFixEnabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onFocusChanged { if (!it.isFocused) onSettingsBlur() },
                )
                OutlinedTextField(
                    value = mediaFixIpText,
                    onValueChange = {
                        if (it.all { ch -> ch.isDigit() || ch == '.' }) onMediaFixIpChange(it)
                    },
                    label = { Text(stringResource(R.string.flowseal_media_fix_ip)) },
                    supportingText = { Text(stringResource(R.string.flowseal_media_fix_ip_hint)) },
                    enabled = !isProxyRunning && mediaFixEnabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onFocusChanged { if (!it.isFocused) onSettingsBlur() },
                )
            }
        }
    }
}
