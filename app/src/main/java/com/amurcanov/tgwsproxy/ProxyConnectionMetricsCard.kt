package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val statusLabel = when (ui.serviceStatus) {
        ProxyServiceStatus.RUNNING -> stringResource(R.string.status_running)
        ProxyServiceStatus.STARTING -> stringResource(R.string.notification_status_starting)
        ProxyServiceStatus.RECONNECTING -> stringResource(R.string.notification_status_reconnecting)
        ProxyServiceStatus.ERROR -> stringResource(R.string.status_error)
        ProxyServiceStatus.STOPPED -> stringResource(R.string.status_stopped)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.metrics_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(stringResource(R.string.metrics_status), statusLabel)
            MetricLine(stringResource(R.string.metrics_route), ui.runtime.routeLabel())
            val speed = ConnectionMetricsFormatter.formatSpeedPair(
                ui.downloadBps,
                ui.uploadBps,
                stringResource(R.string.metrics_idle),
            )
            MetricLine(stringResource(R.string.metrics_speed), speed)
            MetricLine(
                stringResource(R.string.metrics_latency),
                ConnectionMetricsFormatter.formatLatency(ui.runtime.lastLatencyMs),
            )
            MetricLine(
                stringResource(R.string.metrics_connections),
                ui.runtime.activeConnections.toString(),
            )
            val lastError = ui.runtime.lastError.ifBlank { stringResource(R.string.metrics_none) }
            MetricLine(stringResource(R.string.metrics_last_error), lastError)
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
