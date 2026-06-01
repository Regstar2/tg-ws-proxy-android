package com.amurcanov.tgwsproxy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun AdaptiveRoutingPanel(
    profile: NetworkProfile,
    strategy: AutoStrategy,
    stats: List<RouteStatSnapshot>,
    maskDomainsInReport: Boolean,
    includeDomainsInLogExport: Boolean,
    detailsExpanded: Boolean,
    onDetailsExpandedChange: (Boolean) -> Unit,
    onStrategyChange: (AutoStrategy) -> Unit,
    onMaskDomainsChange: (Boolean) -> Unit,
    onIncludeDomainsInLogsChange: (Boolean) -> Unit,
    onCopyReport: () -> Unit,
    onResetAll: () -> Unit,
    onResetNetwork: () -> Unit,
) {
    val context = LocalContext.current
    val networkTypeLabel = when (profile.type) {
        NetworkProfileType.WIFI -> stringResource(R.string.adaptive_network_wifi)
        NetworkProfileType.MOBILE -> stringResource(R.string.adaptive_network_mobile)
        NetworkProfileType.UNKNOWN -> stringResource(R.string.adaptive_network_unknown)
    }
    val networkLabel = profile.label.ifBlank { networkTypeLabel }
    val hints = buildAdaptiveSelectionHints(context, stats)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.adaptive_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DetailsExpandButton(
                expanded = detailsExpanded,
                onExpandedChange = onDetailsExpandedChange,
            )
        }
        Text(
            text = "${stringResource(R.string.adaptive_network_label)}: $networkLabel",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${stringResource(R.string.adaptive_network_type)}: $networkTypeLabel",
            style = MaterialTheme.typography.bodySmall,
        )
        AutoStrategySelector(
            strategy = strategy,
            onStrategyChange = onStrategyChange,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!detailsExpanded && hints.isNotEmpty()) {
            Text(
                text = stringResource(R.string.adaptive_hints_collapsed, hints.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        AnimatedVisibility(visible = detailsExpanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (hints.isNotEmpty()) {
                    Text(
                        stringResource(R.string.adaptive_selection_summary),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    hints.forEach { hint ->
                        Text(
                            "• $hint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.adaptive_mask_domains),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = maskDomainsInReport, onCheckedChange = onMaskDomainsChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.adaptive_include_domains_logs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = includeDomainsInLogExport, onCheckedChange = onIncludeDomainsInLogsChange)
                }
                OutlinedButton(
                    onClick = onCopyReport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text(stringResource(R.string.adaptive_copy_report))
                }
                Text(
                    stringResource(R.string.adaptive_route_stats),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (stats.isEmpty()) {
                    Text(
                        stringResource(R.string.adaptive_stats_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    stats.forEach { row ->
                        AdaptiveRouteStatRow(row)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onResetNetwork,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.adaptive_reset_network_short))
                    }
                    OutlinedButton(
                        onClick = onResetAll,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.adaptive_reset_all_short))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveRouteStatRow(row: RouteStatSnapshot) {
    val now = System.currentTimeMillis()
    val routeLabel = when (row.routeType) {
        "direct_ws" -> stringResource(R.string.adaptive_route_direct)
        "cf_worker_ws" -> stringResource(R.string.adaptive_route_worker)
        "cf_proxy_ws" -> stringResource(R.string.adaptive_route_cf)
        "tcp_fallback" -> stringResource(R.string.adaptive_route_tcp)
        else -> row.routeType
    }
    val inCooldown = row.cooldownUntilMs > now
    val statusLabel = if (inCooldown) {
        stringResource(R.string.adaptive_status_cooldown)
    } else {
        stringResource(R.string.adaptive_status_active)
    }
    val untilText = row.cooldownUntilMs.takeIf { inCooldown }?.let {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
    }
    Text(
        "$routeLabel: $statusLabel",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        "${stringResource(R.string.adaptive_stat_successes)} ${row.successCount}, " +
            "${stringResource(R.string.adaptive_stat_failures)} ${row.failureCount}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (row.averageLatencyMs > 0) {
        Text(
            "${stringResource(R.string.adaptive_stat_avg_latency)}: ${row.averageLatencyMs} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    row.lastFailureReason?.takeIf { it.isNotBlank() }?.let { reason ->
        Text(
            "${stringResource(R.string.adaptive_status_reason)}: $reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    untilText?.let {
        Text(
            "${stringResource(R.string.adaptive_status_until)}: $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildAdaptiveSelectionHints(context: android.content.Context, stats: List<RouteStatSnapshot>): List<String> {
    val now = System.currentTimeMillis()
    val hints = mutableListOf<String>()
    stats.find { it.routeType == "direct_ws" }?.let { direct ->
        if (direct.cooldownUntilMs > now) {
            hints += if (direct.lastFailureReason?.contains("302", ignoreCase = true) == true) {
                context.getString(R.string.adaptive_explain_direct_302)
            } else {
                context.getString(R.string.adaptive_explain_direct_cooldown)
            }
        }
    }
    stats.find { it.routeType == "cf_proxy_ws" }?.let { cf ->
        if (cf.cooldownUntilMs > now) {
            hints += if (cf.lastFailureReason?.contains("429", ignoreCase = true) == true) {
                context.getString(R.string.adaptive_explain_cf_429)
            } else {
                context.getString(R.string.adaptive_explain_cf_cooldown)
            }
        }
    }
    stats.find { it.routeType == "tcp_fallback" }?.let { tcp ->
        if (tcp.failureCount >= 2) {
            hints += context.getString(R.string.adaptive_explain_tcp_timeouts)
        }
    }
    return hints.take(5)
}

@Composable
private fun strategyLabel(strategy: AutoStrategy): String {
    return when (strategy) {
        AutoStrategy.BALANCED -> stringResource(R.string.adaptive_strategy_balanced)
        AutoStrategy.DIRECT_PREFERRED -> stringResource(R.string.adaptive_strategy_direct)
        AutoStrategy.WORKER_PREFERRED -> stringResource(R.string.adaptive_strategy_worker)
        AutoStrategy.CF_PREFERRED -> stringResource(R.string.adaptive_strategy_cf)
        AutoStrategy.STRICT_FAST_FAILOVER -> stringResource(R.string.adaptive_strategy_fast_failover)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoStrategySelector(
    strategy: AutoStrategy,
    onStrategyChange: (AutoStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = strategyLabel(strategy),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.adaptive_strategy_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            AutoStrategy.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(strategyLabel(option)) },
                    onClick = {
                        onStrategyChange(option)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
