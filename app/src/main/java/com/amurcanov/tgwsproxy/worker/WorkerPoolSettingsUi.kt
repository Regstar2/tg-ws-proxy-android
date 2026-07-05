package com.amurcanov.tgwsproxy.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.WorkerDomain

@Composable
fun WorkerPoolSettingsScreen(
    poolEnabled: Boolean,
    workers: List<WorkerEndpoint>,
    selectedWorker: WorkerEndpoint?,
    legacyWorkerDomainText: String,
    legacyWorkerEnabled: Boolean,
    legacyWorkerNormalizeHint: String?,
    isProxyRunning: Boolean,
    isDiagRunning: Boolean,
    maskDomains: Boolean,
    onPoolEnabledChange: (Boolean) -> Unit,
    onSelectWorker: (String) -> Unit,
    onAddWorker: (name: String, url: String, enabled: Boolean) -> WorkerValidationError?,
    onUpdateWorker: (WorkerEndpoint) -> WorkerValidationError?,
    onDeleteWorker: (String) -> Unit,
    onSetWorkerEnabled: (String, Boolean) -> Unit,
    onLegacyWorkerDomainChange: (String) -> Unit,
    onLegacyWorkerDomainBlur: () -> Unit,
    onLegacyWorkerEnabledChange: (Boolean) -> Unit,
    onTestLegacyWorker: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingWorker by remember { mutableStateOf<WorkerEndpoint?>(null) }
    var deletingWorker by remember { mutableStateOf<WorkerEndpoint?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.worker_pool_foundation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.worker_pool_enable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (poolEnabled) {
                        stringResource(R.string.worker_pool_enabled)
                    } else {
                        stringResource(R.string.worker_pool_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = poolEnabled,
                enabled = !isProxyRunning,
                onCheckedChange = onPoolEnabledChange,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (poolEnabled) {
            WorkerPoolSummaryCard(
                selectedWorker = selectedWorker,
                workers = workers,
                maskDomains = maskDomains,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (workers.isEmpty()) {
                Text(
                    stringResource(R.string.worker_pool_no_workers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((workers.size.coerceAtMost(4) * 132).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(workers, key = { it.id }) { worker ->
                        WorkerPoolItemCard(
                            worker = worker,
                            isSelected = selectedWorker?.id == worker.id,
                            maskDomains = maskDomains,
                            isProxyRunning = isProxyRunning,
                            onSelect = { onSelectWorker(worker.id) },
                            onEdit = { editingWorker = worker },
                            onDelete = { deletingWorker = worker },
                            onEnabledChange = { enabled -> onSetWorkerEnabled(worker.id, enabled) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = { showAddDialog = true },
                enabled = !isProxyRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.worker_pool_add_worker))
            }
        } else {
            LegacyWorkerCard(
                workerDomainText = legacyWorkerDomainText,
                workerEnabled = legacyWorkerEnabled,
                workerNormalizeHint = legacyWorkerNormalizeHint,
                isProxyRunning = isProxyRunning,
                isDiagRunning = isDiagRunning,
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
            onDismiss = { showAddDialog = false },
            onSave = { name, url, enabled ->
                val error = onAddWorker(name, url, enabled)
                if (error == null) {
                    showAddDialog = false
                }
                error
            },
        )
    }

    editingWorker?.let { worker ->
        WorkerEditDialog(
            titleRes = R.string.worker_pool_edit_worker,
            initialName = worker.name,
            initialUrl = worker.url,
            initialEnabled = worker.enabled,
            onDismiss = { editingWorker = null },
            onSave = { name, url, enabled ->
                val error = onUpdateWorker(
                    worker.copy(
                        name = name,
                        url = url,
                        enabled = enabled,
                        state = if (enabled) {
                            if (worker.state == WorkerHealthState.DISABLED) WorkerHealthState.UNKNOWN else worker.state
                        } else {
                            WorkerHealthState.DISABLED
                        },
                    ),
                )
                if (error == null) {
                    editingWorker = null
                }
                error
            },
        )
    }

    deletingWorker?.let { worker ->
        AlertDialog(
            onDismissRequest = { deletingWorker = null },
            title = { Text(stringResource(R.string.worker_pool_delete_confirmation_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.worker_pool_delete_confirmation_message,
                        worker.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteWorker(worker.id)
                        deletingWorker = null
                    },
                ) {
                    Text(stringResource(R.string.worker_pool_delete_worker))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingWorker = null }) {
                    Text(stringResource(R.string.worker_pool_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkerPoolSummaryCard(
    selectedWorker: WorkerEndpoint?,
    workers: List<WorkerEndpoint>,
    maskDomains: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.worker_pool_selected_worker),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                selectedWorker?.name ?: stringResource(R.string.worker_pool_no_selected_worker),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            val enabledCount = workers.count { it.enabled }
            val disabledCount = workers.size - enabledCount
            Text(
                stringResource(R.string.worker_pool_workers_count, workers.size),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_pool_enabled_workers_count, enabledCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.worker_pool_disabled_workers_count, disabledCount),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            selectedWorker?.let { worker ->
                com.amurcanov.tgwsproxy.MetricLine(
                    stringResource(R.string.worker_pool_selected_worker_url),
                    WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
                )
            }
        }
    }
}

@Composable
private fun WorkerPoolItemCard(
    worker: WorkerEndpoint,
    isSelected: Boolean,
    maskDomains: Boolean,
    isProxyRunning: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(worker.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isSelected) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.worker_pool_selected)) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (worker.enabled) {
                    stringResource(R.string.worker_pool_worker_enabled)
                } else {
                    stringResource(R.string.worker_pool_worker_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.worker_pool_worker_state_label,
                    stringResource(
                        when (worker.state) {
                            WorkerHealthState.UNKNOWN -> R.string.worker_pool_worker_state_unknown
                            WorkerHealthState.HEALTHY -> R.string.worker_pool_worker_state_healthy
                            WorkerHealthState.DEGRADED -> R.string.worker_pool_worker_state_degraded
                            WorkerHealthState.DEAD -> R.string.worker_pool_worker_state_dead
                            WorkerHealthState.DISABLED -> R.string.worker_pool_worker_state_disabled
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.worker_pool_worker_enabled), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = worker.enabled,
                    enabled = !isProxyRunning,
                    onCheckedChange = onEnabledChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSelect,
                    enabled = !isProxyRunning && worker.enabled && !isSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.worker_pool_select_worker))
                }
                OutlinedButton(onClick = onEdit, enabled = !isProxyRunning, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.worker_pool_edit_worker))
                }
                OutlinedButton(onClick = onDelete, enabled = !isProxyRunning, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.worker_pool_delete_worker))
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
    val normalizedDomain = remember(workerDomainText) { WorkerDomain.normalize(workerDomainText) }
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
                supportingText = { Text(stringResource(R.string.worker_domain_helper)) },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            onWorkerDomainBlur()
                        }
                    },
                singleLine = true,
            )
            workerNormalizeHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            WorkerDomain.validationWarning(normalizedDomain)?.let { warnRes ->
                Text(
                    stringResource(warnRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cf_worker_enable), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = workerEnabled,
                    enabled = !isProxyRunning,
                    onCheckedChange = onWorkerEnabledChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onTestWorker,
                    enabled = !isProxyRunning && !isDiagRunning,
                    modifier = Modifier.weight(1f),
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
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, enabled: Boolean) -> WorkerValidationError?,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    var validationError by remember { mutableStateOf<WorkerValidationError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.worker_pool_worker_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.worker_pool_worker_url)) },
                    placeholder = { Text(stringResource(R.string.worker_domain_placeholder)) },
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
                        stringResource(
                            when (error) {
                                WorkerValidationError.EMPTY_NAME -> R.string.worker_pool_empty_name
                                WorkerValidationError.EMPTY_URL,
                                WorkerValidationError.INVALID_URL,
                                -> R.string.worker_pool_invalid_url
                            },
                        ),
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
                    validationError = onSave(name, url, enabled)
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
