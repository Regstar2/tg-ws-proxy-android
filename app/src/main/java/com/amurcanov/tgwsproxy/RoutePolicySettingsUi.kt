package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RoutePolicySettingsSection(
    currentProfile: NetworkProfile,
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    hasSavedWifiPolicy: Boolean,
    hasSavedMobilePolicy: Boolean,
    isProxyRunning: Boolean,
    onWifiPolicyChange: (NetworkRoutePolicy) -> Unit,
    onMobilePolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetWifiPolicy: () -> Unit,
    onResetMobilePolicy: () -> Unit,
) {
    Text(
        text = stringResource(R.string.route_policy_section_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
    )
    Text(
        text = stringResource(R.string.route_policy_section_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Text(
        text = stringResource(R.string.route_policy_current_network, stringResource(currentProfile.type.titleRes())),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Text(
        text = stringResource(R.string.route_policy_legacy_mode_relation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    if (isProxyRunning) {
        Text(
            text = stringResource(R.string.route_policy_stop_to_edit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    RoutePolicyCard(
        title = stringResource(R.string.route_policy_wifi_title),
        networkType = NetworkProfileType.WIFI,
        policy = wifiPolicy,
        hasSavedPolicy = hasSavedWifiPolicy,
        isCurrentNetwork = currentProfile.type == NetworkProfileType.WIFI,
        isProxyRunning = isProxyRunning,
        onPolicyChange = onWifiPolicyChange,
        onReset = onResetWifiPolicy,
    )
    Spacer(modifier = Modifier.height(10.dp))
    RoutePolicyCard(
        title = stringResource(R.string.route_policy_mobile_title),
        networkType = NetworkProfileType.MOBILE,
        policy = mobilePolicy,
        hasSavedPolicy = hasSavedMobilePolicy,
        isCurrentNetwork = currentProfile.type == NetworkProfileType.MOBILE,
        isProxyRunning = isProxyRunning,
        onPolicyChange = onMobilePolicyChange,
        onReset = onResetMobilePolicy,
    )
    Text(
        text = stringResource(R.string.route_policy_legacy_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutePolicyCard(
    title: String,
    networkType: NetworkProfileType,
    policy: NetworkRoutePolicy,
    hasSavedPolicy: Boolean,
    isCurrentNetwork: Boolean,
    isProxyRunning: Boolean,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
    onReset: () -> Unit,
) {
    val normalizedPolicy = NetworkRoutePolicyEditor.normalize(policy.copy(networkType = networkType))
    val controlsEnabled = !isProxyRunning
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                if (hasSavedPolicy) {
                                    R.string.route_policy_saved_policy_badge
                                } else {
                                    R.string.route_policy_default_policy_badge
                                },
                            ),
                        )
                    },
                )
                if (isCurrentNetwork) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.route_policy_effective_now)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.route_policy_enabled_routes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NetworkRoutePolicyEditor.routeOrder.forEach { route ->
                val checked = route in normalizedPolicy.enabledRoutes
                val canDisable = normalizedPolicy.enabledRoutes.size > 1 || !checked
                RouteToggleRow(
                    route = route,
                    checked = checked,
                    enabled = controlsEnabled && canDisable,
                    onCheckedChange = { enabled ->
                        onPolicyChange(NetworkRoutePolicyEditor.setRouteEnabled(normalizedPolicy, route, enabled))
                    },
                )
            }
            RouteDropdown(
                label = stringResource(R.string.route_policy_preferred_route),
                value = normalizedPolicy.preferredRoute?.let { stringResource(it.labelRes()) }.orEmpty(),
                enabled = controlsEnabled,
            ) { close ->
                normalizedPolicy.enabledRoutesInOrder().forEach { route ->
                    DropdownMenuItem(
                        text = { Text(stringResource(route.labelRes())) },
                        onClick = {
                            onPolicyChange(NetworkRoutePolicyEditor.setPreferredRoute(normalizedPolicy, route))
                            close()
                        },
                    )
                }
            }
            RouteDropdown(
                label = stringResource(R.string.route_policy_auto_strategy),
                value = stringResource(normalizedPolicy.autoStrategy.displayLabelRes()),
                enabled = controlsEnabled,
            ) { close ->
                AutoStrategy.entries.forEach { strategy ->
                    DropdownMenuItem(
                        text = { Text(stringResource(strategy.displayLabelRes())) },
                        onClick = {
                            onPolicyChange(normalizedPolicy.copy(autoStrategy = strategy))
                            close()
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.route_policy_allow_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.route_policy_allow_fallback_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = normalizedPolicy.allowFallback,
                    enabled = controlsEnabled,
                    onCheckedChange = { onPolicyChange(normalizedPolicy.copy(allowFallback = it)) },
                )
            }
            OutlinedButton(
                onClick = onReset,
                enabled = controlsEnabled && hasSavedPolicy,
                colors = ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.route_policy_reset))
            }
        }
    }
}

@Composable
private fun RouteToggleRow(
    route: RouteKind,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(route.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(route.hintRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteDropdown(
    label: String,
    value: String,
    enabled: Boolean,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            content { expanded = false }
        }
    }
}

private fun NetworkProfileType.titleRes(): Int = when (this) {
    NetworkProfileType.WIFI -> R.string.route_policy_wifi_title
    NetworkProfileType.MOBILE -> R.string.route_policy_mobile_title
    NetworkProfileType.UNKNOWN -> R.string.route_policy_unknown_title
}

private fun RouteKind.labelRes(): Int = when (this) {
    RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
    RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
    RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
    RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
}

private fun RouteKind.hintRes(): Int = when (this) {
    RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws_hint
    RouteKind.WORKER_WS -> R.string.route_policy_worker_ws_hint
    RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws_hint
    RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback_hint
}

private fun AutoStrategy.displayLabelRes(): Int = when (this) {
    AutoStrategy.BALANCED -> R.string.adaptive_strategy_balanced
    AutoStrategy.DIRECT_PREFERRED -> R.string.adaptive_strategy_direct
    AutoStrategy.WORKER_PREFERRED -> R.string.adaptive_strategy_worker
    AutoStrategy.CF_PREFERRED -> R.string.adaptive_strategy_cf
    AutoStrategy.STRICT_FAST_FAILOVER -> R.string.adaptive_strategy_fast_failover
}

private fun NetworkRoutePolicy.enabledRoutesInOrder(): List<RouteKind> {
    return NetworkRoutePolicyEditor.routeOrder.filter { it in enabledRoutes }
}
