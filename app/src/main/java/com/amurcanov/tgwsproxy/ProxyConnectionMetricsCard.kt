package com.amurcanov.tgwsproxy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ProxyConnectionMetricsCard(
    ui: ProxyUiMetrics,
    showMetrics: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showMetrics) {
        return
    }
    val context = LocalContext.current
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val statusLabel = when (ui.serviceStatus) {
        ProxyServiceStatus.RUNNING -> stringResource(R.string.status_running)
        ProxyServiceStatus.STARTING -> stringResource(R.string.notification_status_starting)
        ProxyServiceStatus.RECONNECTING -> stringResource(R.string.notification_status_reconnecting)
        ProxyServiceStatus.ERROR -> stringResource(R.string.status_error)
        ProxyServiceStatus.STOPPED -> stringResource(R.string.status_stopped)
    }
    val routeLabel = RouteDisplayNames.routeLabel(context, ui.runtime.route)
    val hasTraffic = ui.downloadBps > 0.0 || ui.uploadBps > 0.0
    val speed = if (hasTraffic) {
        ConnectionMetricsFormatter.formatSpeedPair(
            ui.downloadBps,
            ui.uploadBps,
            stringResource(R.string.metrics_idle),
        )
    } else {
        stringResource(R.string.metrics_no_traffic)
    }
    val latency = ConnectionMetricsFormatter.formatLatency(ui.runtime.lastLatencyMs)
    val connections = ui.runtime.activeConnections.toString()
    val lastError = ui.runtime.lastError.trim()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.metrics_state_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(stringResource(R.string.metrics_status), statusLabel)
            MetricLine(stringResource(R.string.metrics_route), routeLabel)
            MetricLine(stringResource(R.string.metrics_speed), speed)
            MetricLine(stringResource(R.string.metrics_latency), latency)
            MetricLine(stringResource(R.string.metrics_connections), connections)
            if (lastError.isNotBlank()) {
                MetricLine(stringResource(R.string.metrics_last_error), lastError)
            }
            Text(
                text = if (detailsExpanded) {
                    stringResource(R.string.metrics_hide_details)
                } else {
                    stringResource(R.string.metrics_show_details)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { detailsExpanded = !detailsExpanded }
                    .padding(top = 8.dp),
            )
            AnimatedVisibility(visible = detailsExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.worker_pool_metrics_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_hits_misses_label),
                        stringResource(
                            R.string.pool_metrics_hits_misses,
                            ui.runtime.workerPoolHits,
                            ui.runtime.workerPoolMisses,
                        ),
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_idle_label),
                        ui.runtime.workerPoolIdle.toString(),
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_errors_label),
                        ui.runtime.workerPoolErrors.toString(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.cf_pool_metrics_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_hits_misses_label),
                        stringResource(
                            R.string.pool_metrics_hits_misses,
                            ui.runtime.cfPoolHits,
                            ui.runtime.cfPoolMisses,
                        ),
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_idle_label),
                        ui.runtime.cfPoolIdle.toString(),
                    )
                    MetricLine(
                        stringResource(R.string.pool_metrics_errors_label),
                        ui.runtime.cfPoolErrors.toString(),
                    )
                    if (lastError.isBlank()) {
                        MetricLine(
                            stringResource(R.string.metrics_last_error),
                            stringResource(R.string.metrics_none),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
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
        )
    }
}
