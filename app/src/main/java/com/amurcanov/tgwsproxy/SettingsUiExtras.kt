package com.amurcanov.tgwsproxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun RuntimeLogsDialog(
    logs: List<String>,
    logsEnabled: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.logs_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!logsEnabled) {
                    Text(
                        stringResource(R.string.logs_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val scroll = rememberScrollState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        scroll.animateScrollTo(scroll.maxValue)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 360.dp)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(12.dp),
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                stringResource(R.string.logs_empty_state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            logs.forEach { line ->
                                Text(
                                    formatLogLine(line),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.copy_logs))
                    }
                    OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.save_logs))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.info_close))
                    }
                }
            }
        }
    }
}

@Composable
fun CompactDiagnosticsSection(
    lastStatus: String,
    isRunning: Boolean,
    isDiagRunning: Boolean,
    onProbeAll: () -> Unit,
    onProbeDirect: () -> Unit,
    onProbeWorker: () -> Unit,
    onProbeCf: () -> Unit,
    onProbeTcp: () -> Unit,
) {
    if (lastStatus.isNotBlank()) {
        Text(
            stringResource(R.string.diag_last_result, lastStatus),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    FilledTonalButton(
        onClick = onProbeAll,
        enabled = !isRunning && !isDiagRunning,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Text(stringResource(R.string.diag_probe_all))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onProbeDirect,
                enabled = !isRunning && !isDiagRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.diag_short_direct))
            }
            OutlinedButton(
                onClick = onProbeWorker,
                enabled = !isRunning && !isDiagRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.diag_short_worker))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onProbeCf,
                enabled = !isRunning && !isDiagRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.diag_short_cf))
            }
            OutlinedButton(
                onClick = onProbeTcp,
                enabled = !isRunning && !isDiagRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.diag_short_tcp))
            }
        }
    }
}
