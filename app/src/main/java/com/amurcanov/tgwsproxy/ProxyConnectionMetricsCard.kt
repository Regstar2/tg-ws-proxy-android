package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val running = ui.serviceStatus == ProxyServiceStatus.RUNNING ||
        ui.serviceStatus == ProxyServiceStatus.RECONNECTING
    val connectionStatus = if (running) {
        stringResource(R.string.metrics_connected)
    } else {
        stringResource(R.string.metrics_disconnected)
    }
    val routeLabel = if (running) {
        ui.runtime.routeRuntime.activeRouteLabel(context, running)
    } else {
        stringResource(R.string.metrics_route_unavailable)
    }
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
    val connections = ui.runtime.activeConnections.toString()

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
            MetricLine(stringResource(R.string.metrics_connection), connectionStatus)
            MetricLine(stringResource(R.string.metrics_speed), speed)
            MetricLine(stringResource(R.string.metrics_route), routeLabel)
            MetricLine(stringResource(R.string.metrics_connections), connections)
        }
    }
}

@Composable
internal fun MetricLine(label: String, value: String) {
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
