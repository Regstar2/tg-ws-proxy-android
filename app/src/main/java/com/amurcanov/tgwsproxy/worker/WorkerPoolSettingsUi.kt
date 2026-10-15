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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    legacyWorkerTransport: WorkerTransport,
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
    onAddWorker: (name: String, url: String, enabled: Boolean, priority: Int, transport: WorkerTransport) -> WorkerValidationError?,
    onUpdateWorker: (WorkerEndpoint) -> WorkerValidationError?,
    onDeleteWorker: (String) -> Unit,
    onSetWorkerEnabled: (String, Boolean) -> Unit,
    onCheckWorker: (String) -> Unit,
    onCheckAllWorkers: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onLegacyWorkerDomainChange: (String) -> Unit,
    onLegacyWorkerDomainBlur: () -> Unit,
    onLegacyWorkerEnabledChange: (Boolean) -> Unit,
    onLegacyWorkerTransportChange: (WorkerTransport) -> Unit,
    onTestLegacyWorker: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var deletingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var disablingWorker by remember { mutableStateOf<WorkerItemUiModel?>(null) }
    var strategyExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    val presentation = workerScreenAPresentation(
        poolEnabled = uiState.poolEnabled,
        proxyRunning = uiState.isProxyRunning,
        totalWorkers = uiState.workers.size,
        enabledWorkers = uiState.summary.enabledCount,
        checkingAll = uiState.isCheckingAllWorkers,
        activeRoute = uiState.runtime.activeRoute,
    )

    LaunchedEffect(Unit) {
        onScreenOpened()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        WorkerPoolEnableRow(
            poolEnabled = uiState.poolEnabled,
            isProxyRunning = uiState.isProxyRunning,
            onPoolEnabledChange = onPoolEnabledChange,
        )
        Spacer(modifier = Modifier.height(12.dp))
        WorkerPoolConnectionSection(
            uiState = uiState,
            legacyWorkerDomainText = legacyWorkerDomainText,
            legacyWorkerTransport = legacyWorkerTransport,
            showActualRuntime = presentation.showActualRuntime,
            onOpenDiagnostics = onOpenDiagnostics,
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.poolEnabled) {
            WorkerPoolStrategySection(
                strategy = uiState.strategy,
                expanded = strategyExpanded,
                isProxyRunning = uiState.isProxyRunning,
                onToggleExpanded = { strategyExpanded = !strategyExpanded },
                onStrategyChange = onSelectionStrategyChange,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.worker_screen_a_my_workers, uiState.workers.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_screen_a_worker_counts, uiState.summary.enabledCount, uiState.summary.disabledCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            WorkerPoolQuickActions(
                uiState = uiState,
                canCheckAll = presentation.canCheckAll,
                onCheckAllWorkers = onCheckAllWorkers,
                onAddWorker = { showAddDialog = true },
                onImportWorkers = { showImportDialog = true },
            )
            Spacer(modifier = Modifier.height(10.dp))

            // A single warning followed by the full list, including disabled Workers.
            if (presentation.showNoEnabledWarning) {
                WorkerPoolWarningBanner(WorkerPoolConfigWarning.NO_ENABLED_WORKERS)
                Spacer(modifier = Modifier.height(10.dp))
            } else if (uiState.configWarning != null) {
                WorkerPoolWarningBanner(uiState.configWarning)
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (presentation.showEmptyState) {
                WorkerPoolEmptyState()
            } else {
                uiState.workers.forEach { item ->
                    WorkerPoolItemCardPolished(
                        item = item,
                        isChecking = uiState.checkingWorkerIds.contains(item.id),
                        isCheckingAll = uiState.isCheckingAllWorkers,
                        showActualRuntime = presentation.showActualRuntime,
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
        } else {
            LegacyWorkerCard(
                workerDomainText = legacyWorkerDomainText,
                workerEnabled = legacyWorkerEnabled,
                workerTransport = legacyWorkerTransport,
                workerNormalizeHint = legacyWorkerNormalizeHint,
                isProxyRunning = uiState.isProxyRunning,
                isDiagRunning = uiState.isDiagRunning,
                onWorkerDomainChange = onLegacyWorkerDomainChange,
                onWorkerDomainBlur = onLegacyWorkerDomainBlur,
                onWorkerEnabledChange = onLegacyWorkerEnabledChange,
                onWorkerTransportChange = onLegacyWorkerTransportChange,
                onTestWorker = onTestLegacyWorker,
                onOpenWorkerHelp = onOpenWorkerHelp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(stringResource(if (advancedExpanded) R.string.worker_screen_a_advanced_hide else R.string.worker_screen_a_advanced))
        }
        AnimatedVisibility(visible = advancedExpanded) {
            Column {
                WorkerPoolFlowsealDcOnlySection(
                    enabled = flowsealDcOnlyEnabled,
                    isProxyRunning = uiState.isProxyRunning,
                    onEnabledChange = onFlowsealDcOnlyEnabledChange,
                )
                if (uiState.poolEnabled) {
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
                }
            }
        }
    }

    if (showImportDialog) {
        WorkerImportDialog(
            existingUrls = existingWorkerUrls,
            proxyRunning = uiState.isProxyRunning,
            onDismiss = { showImportDialog = false },
            onImport = { name, url, transport -> onAddWorker(name, url, true, 0, transport) },
            onDone = { showImportDialog = false },
        )
    }

    if (showAddDialog) {
        WorkerEditDialog(
            titleRes = R.string.worker_pool_add_worker,
            initialName = "",
            initialUrl = "",
            initialEnabled = true,
            initialPriority = 0,
            initialTransport = WorkerTransport.CHUNK_RELAY,
            isProxyRunning = uiState.isProxyRunning,
            existingUrls = existingWorkerUrls,
            onDismiss = { showAddDialog = false },
            onSave = { name, url, enabled, priority, transport ->
                val error = onAddWorker(name, url, enabled, priority, transport)
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
            initialTransport = item.endpoint.transport,
            isProxyRunning = uiState.isProxyRunning,
            existingUrls = existingWorkerUrls.filter { it != item.endpoint.url },
            onDismiss = { editingWorker = null },
            onSave = { name, url, enabled, priority, transport ->
                val error = onUpdateWorker(
                    item.endpoint.copy(
                        name = name,
                        url = url,
                        enabled = enabled,
                        priority = priority,
                        transport = transport,
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
                    enabled = !uiState.isProxyRunning,
                    onClick = {
                        if (uiState.isProxyRunning) return@TextButton
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
                    enabled = !uiState.isProxyRunning,
                    onClick = {
                        if (uiState.isProxyRunning) return@TextButton
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.worker_pool_enable), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.worker_screen_a_pool_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = poolEnabled, enabled = !isProxyRunning, onCheckedChange = onPoolEnabledChange)
        }
    }
}

/** Multiline label/value block: large-font and long Worker domains must never share a narrow row. */
@Composable
private fun WorkerOverviewValue(label: String, value: String, domain: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (domain) workerScreenAReadableAddress(value) else value,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            softWrap = true,
        )
    }
}

/** Concise single overview; diagnostics intentionally remain distinct from MTProto validation. */
@Composable
private fun WorkerPoolConnectionSection(
    uiState: WorkerPoolUiState,
    legacyWorkerDomainText: String,
    legacyWorkerTransport: WorkerTransport,
    showActualRuntime: Boolean,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val active = uiState.workers.firstOrNull { it.isRuntimeWorker }
    val selected = uiState.workers.firstOrNull { it.isSelected }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.worker_screen_a_connection),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(if (uiState.isProxyRunning) R.string.worker_screen_a_proxy_running else R.string.worker_screen_a_proxy_stopped),
                style = MaterialTheme.typography.labelMedium,
                color = if (uiState.isProxyRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp, bottom = 7.dp),
            )
            if (uiState.poolEnabled) {
                WorkerOverviewValue(
                    stringResource(R.string.worker_pool_selected_worker),
                    selected?.name ?: stringResource(R.string.worker_pool_no_selected_worker),
                )
                selected?.let {
                    WorkerOverviewValue(
                        stringResource(R.string.worker_transport_label),
                        stringResource(workerTransportLabel(it.endpoint.transport)),
                    )
                }
            } else {
                WorkerOverviewValue(
                    stringResource(R.string.cf_worker_pool_legacy_worker),
                    if (legacyWorkerDomainText.isBlank()) stringResource(R.string.worker_pool_no_selected_worker)
                    else WorkerUrlSanitizer.maskForDisplay(legacyWorkerDomainText, uiState.maskDomains),
                    domain = true,
                )
                WorkerOverviewValue(
                    stringResource(R.string.worker_transport_label),
                    stringResource(workerTransportLabel(legacyWorkerTransport)),
                )
            }
            if (uiState.runtime.configuredMode.isNotBlank()) {
                WorkerOverviewValue(
                    stringResource(R.string.route_runtime_configured_mode),
                    RouteDisplayNames.modeLabel(context, uiState.runtime.configuredMode),
                )
            }
            if (uiState.isProxyRunning) {
                WorkerOverviewValue(
                    stringResource(R.string.route_runtime_active_route),
                    RouteDisplayNames.currentRouteLabel(context, uiState.runtime.activeRoute, true),
                )
                if (showActualRuntime) {
                    WorkerOverviewValue(
                        stringResource(R.string.worker_pool_runtime_worker),
                        uiState.summary.runtimeWorkerName,
                    )
                    active?.let {
                        WorkerOverviewValue(
                            stringResource(R.string.worker_screen_a_actual_transport),
                            stringResource(workerTransportLabel(it.endpoint.transport)),
                        )
                    }
                }
                uiState.runtime.failoverReason.takeIf { it.isNotBlank() }?.let {
                    WorkerOverviewValue(stringResource(R.string.worker_pool_last_failover_reason), it)
                }
            }
            Text(
                stringResource(R.string.worker_screen_a_diagnostic_brief),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.worker_pool_open_diagnostics))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.worker_pool_strategy_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(strategy.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(strategy.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(onClick = onToggleExpanded) {
                Text(
                    stringResource(if (expanded) R.string.worker_pool_details_hide else R.string.worker_screen_a_change_strategy),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    WorkerSelectionStrategy.entries.forEach { entry ->
                        val selected = entry == strategy.strategy
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(
                                selected = selected,
                                enabled = !isProxyRunning,
                                onClick = { if (!selected) onStrategyChange(entry) },
                            )
                            Column(modifier = Modifier.weight(1f).padding(top = 7.dp)) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkerPoolQuickActions(
    uiState: WorkerPoolUiState,
    canCheckAll: Boolean,
    onCheckAllWorkers: () -> Unit,
    onAddWorker: () -> Unit,
    onImportWorkers: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalButton(onClick = onAddWorker, enabled = !uiState.isProxyRunning) {
            Text(stringResource(R.string.worker_pool_add_worker))
        }
        OutlinedButton(onClick = onCheckAllWorkers, enabled = canCheckAll) {
            if (uiState.isCheckingAllWorkers) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(if (uiState.isCheckingAllWorkers) R.string.worker_health_checking else R.string.worker_pool_check_all_workers),
            )
        }
        TextButton(onClick = onImportWorkers, enabled = !uiState.isProxyRunning) {
            Text(stringResource(R.string.worker_screen_a_import))
        }
    }
}

@Composable
private fun WorkerPoolEmptyState() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.worker_pool_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.worker_screen_a_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkerPoolItemCardPolished(
    item: WorkerItemUiModel,
    isChecking: Boolean,
    isCheckingAll: Boolean,
    showActualRuntime: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
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
                        stringResource(workerTransportLabel(item.endpoint.transport)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        workerScreenAReadableAddress(item.maskedUrl),
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
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.worker_screen_a_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (item.canSelect) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.worker_pool_select_worker)) },
                            onClick = { menuExpanded = false; onSelect() },
                        )
                    }
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
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = item.canCheck && !isChecking && !isCheckingAll,
                ) {
                    if (isChecking || isCheckingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(R.string.worker_pool_check_worker))
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(stringResource(if (detailsExpanded) R.string.worker_pool_details_hide else R.string.worker_pool_details))
                }
            }
            if (item.tagLabelResIds.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.tagLabelResIds.filter { it != R.string.worker_pool_runtime_worker || showActualRuntime }.forEach { tagRes ->
                        AssistChip(onClick = {}, label = { Text(stringResource(tagRes)) })
                    }
                }
            }
            AnimatedVisibility(visible = detailsExpanded) {
                Column {
                    Text(
                        stringResource(R.string.worker_screen_a_diagnostic_caution),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    }
}

@Composable
private fun LegacyWorkerCard(
    workerDomainText: String,
    workerEnabled: Boolean,
    workerTransport: WorkerTransport,
    workerNormalizeHint: String?,
    isProxyRunning: Boolean,
    isDiagRunning: Boolean,
    onWorkerDomainChange: (String) -> Unit,
    onWorkerDomainBlur: () -> Unit,
    onWorkerEnabledChange: (Boolean) -> Unit,
    onWorkerTransportChange: (WorkerTransport) -> Unit,
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
            WorkerTransportSelector(workerTransport, !isProxyRunning, onWorkerTransportChange)
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
    initialTransport: WorkerTransport,
    isProxyRunning: Boolean,
    existingUrls: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, enabled: Boolean, priority: Int, transport: WorkerTransport) -> WorkerValidationError?,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    var priorityText by rememberSaveable { mutableStateOf(initialPriority.toString()) }
    var transport by rememberSaveable { mutableStateOf(initialTransport) }
    var validationError by remember { mutableStateOf<WorkerValidationError?>(null) }
    var extraFieldsExpanded by rememberSaveable { mutableStateOf(false) }
    val normalizedUrl = remember(url) { WorkerDomain.normalize(url) }
    val duplicateUrl = normalizedUrl.isNotBlank() &&
        existingUrls.any { WorkerDomain.normalize(it).equals(normalizedUrl, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validationError = null },
                    enabled = !isProxyRunning,
                    label = { Text(stringResource(R.string.worker_pool_worker_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; validationError = null },
                    enabled = !isProxyRunning,
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
                WorkerTransportSelector(transport, !isProxyRunning, { transport = it })
                TextButton(onClick = { extraFieldsExpanded = !extraFieldsExpanded }) {
                    Text(stringResource(
                        if (extraFieldsExpanded) R.string.worker_screen_a_extra_hide else R.string.worker_screen_a_extra_show,
                    ))
                }
                AnimatedVisibility(visible = extraFieldsExpanded) {
                    Column {
                        OutlinedTextField(
                            value = priorityText,
                            onValueChange = { priorityText = it.filter { ch -> ch.isDigit() || ch == '-' }; validationError = null },
                            enabled = !isProxyRunning,
                            label = { Text(stringResource(R.string.worker_pool_worker_priority)) },
                            supportingText = { Text(stringResource(R.string.worker_pool_worker_priority_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.worker_pool_worker_enabled))
                            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !isProxyRunning)
                        }
                    }
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
                enabled = !isProxyRunning && !duplicateUrl,
                onClick = {
                    if (duplicateUrl || isProxyRunning) return@TextButton
                    val priority = priorityText.toIntOrNull() ?: 0
                    validationError = onSave(name.trim(), url.trim(), enabled, priority, transport)
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

/**
 * Explicit batch import through the existing persistence callback. Validates every row and
 * de-duplicates before applying; transport is always chosen by the user (never guessed).
 */
@Composable
private fun WorkerImportDialog(
    existingUrls: List<String>,
    proxyRunning: Boolean,
    onDismiss: () -> Unit,
    onImport: (String, String, WorkerTransport) -> WorkerValidationError?,
    onDone: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var transport by rememberSaveable { mutableStateOf(WorkerTransport.CHUNK_RELAY) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val normalized = urls.map { WorkerDomain.normalize(it).lowercase() }
    val exists = existingUrls.map { WorkerDomain.normalize(it).lowercase() }.toSet()
    val valid = urls.isNotEmpty() && urls.size <= 30 &&
        normalized.size == normalized.toSet().size &&
        normalized.none { it in exists } &&
        urls.all { WorkerEndpointValidator.validate(it, it) == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.worker_screen_a_import)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.worker_screen_a_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                WorkerTransportSelector(transport, !proxyRunning, { transport = it })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; message = null },
                    label = { Text(stringResource(R.string.worker_screen_a_domains)) },
                    enabled = !proxyRunning,
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (urls.isNotEmpty() && !valid) {
                    Text(
                        stringResource(R.string.worker_screen_a_import_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !proxyRunning && valid,
                onClick = {
                    var accepted = 0
                    for (url in urls) {
                        val error = onImport(WorkerDomain.normalize(url), url, transport)
                        if (error != null) {
                            message = context.getString(R.string.worker_screen_a_import_error, accepted + 1, error.name)
                            break
                        }
                        accepted++
                    }
                    if (accepted == urls.size) onDone()
                },
            ) { Text(stringResource(R.string.worker_screen_a_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.worker_pool_cancel)) } },
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
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
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

private fun workerTransportLabel(transport: WorkerTransport): Int = when (transport) {
    WorkerTransport.CHUNK_RELAY -> R.string.worker_transport_chunk_relay
    WorkerTransport.LEGACY_WEBSOCKET -> R.string.worker_transport_legacy_websocket
}

@Composable
private fun WorkerTransportSelector(
    transport: WorkerTransport,
    enabled: Boolean,
    onChange: (WorkerTransport) -> Unit,
) {
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.worker_transport_label), style = MaterialTheme.typography.labelLarge)
        WorkerTransport.entries.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = transport == option,
                    onClick = { onChange(option) },
                    enabled = enabled,
                )
                Text(
                    stringResource(
                        if (option == WorkerTransport.CHUNK_RELAY)
                            R.string.worker_screen_a_chunk_short
                        else R.string.worker_screen_a_legacy_short,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (transport == WorkerTransport.LEGACY_WEBSOCKET) {
            Text(
                stringResource(R.string.worker_screen_a_legacy_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = { helpExpanded = !helpExpanded }) {
            Text(stringResource(if (helpExpanded) R.string.worker_screen_a_help_hide else R.string.worker_screen_a_help_show))
        }
        AnimatedVisibility(visible = helpExpanded) {
            Text(
                stringResource(R.string.worker_transport_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
