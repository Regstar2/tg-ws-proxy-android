package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    }
}

@Composable
private fun RouteRuntimeMetricLine(label: String, value: String) {
    MetricLine(label, value)
}
