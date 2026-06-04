package com.amurcanov.tgwsproxy.routeprobe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.MetricLine
import com.amurcanov.tgwsproxy.R
import java.text.DateFormat
import java.util.Date

@Composable
fun RouteProbeCoreBlock(
    summary: RouteProbeSummary?,
    snapshot: RouteProbeSnapshot?,
    isRunning: Boolean,
    onRunProbe: () -> Unit,
    onOpenDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.route_probe_core_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        FilledTonalButton(
            onClick = onRunProbe,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (isRunning) {
                    stringResource(R.string.route_probe_checking)
                } else {
                    stringResource(R.string.route_probe_check_all)
                },
            )
        }
        snapshot?.let { snap ->
            snap.target?.let { target ->
                MetricLine(
                    stringResource(R.string.route_probe_last_target),
                    RouteProbeDisplayNames.targetLabel(context, target),
                )
            }
            snap.status?.let { status ->
                MetricLine(
                    stringResource(R.string.route_probe_last_status),
                    RouteProbeDisplayNames.statusLabel(context, status),
                )
            }
            if (snap.checkedAtMs > 0L) {
                val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(snap.checkedAtMs))
                MetricLine(stringResource(R.string.route_probe_last_checked), formatted)
            }
        }
        summary?.results?.forEach { result ->
            Text(
                text = RouteProbeDisplayNames.summaryLine(context, result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!isRunning && summary == null) {
            Text(
                stringResource(R.string.route_probe_no_report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        onOpenDiagnostics?.let { open ->
            TextButton(
                onClick = open,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_open_screen))
            }
        }
    }
}
