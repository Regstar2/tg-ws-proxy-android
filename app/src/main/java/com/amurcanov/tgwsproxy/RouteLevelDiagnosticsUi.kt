package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RouteLevelDiagnosticsCard(
    report: EffectiveRouteProbeReport?,
    isRunning: Boolean,
    onRunProbe: () -> Unit,
    onCopyReport: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.route_probe_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(
                onClick = onRunProbe,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isRunning) {
                        stringResource(R.string.route_probe_running)
                    } else {
                        stringResource(R.string.route_probe_run_effective)
                    },
                )
            }
            if (report == null) {
                Text(
                    text = stringResource(R.string.route_probe_no_report),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.results.forEach { result ->
                    Text(
                        text = RouteLevelDiagnosticsFormatter.formatResultLine(context, result),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val hints = RouteLevelDiagnosticsFormatter.troubleshootingHints(context, report)
                if (hints.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.route_probe_hints_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    hints.forEach { hint ->
                        Text(
                            text = "• $hint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCopyReport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.route_probe_copy_report))
                }
            }
        }
    }
}
