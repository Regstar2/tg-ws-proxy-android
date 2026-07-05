package com.amurcanov.tgwsproxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class RoutesSettingsPage {
    OVERVIEW,
    WIFI,
    MOBILE,
    ADVANCED,
}

@Composable
fun RoutesSettingsScreen(
    page: RoutesSettingsPage,
    onPageChange: (RoutesSettingsPage) -> Unit,
    currentProfile: NetworkProfile,
    connectionMode: ConnectionMode,
    isProxyRunning: Boolean,
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    hasSavedWifiPolicy: Boolean,
    hasSavedMobilePolicy: Boolean,
    policySnapshot: RoutePolicyDiagnosticsSnapshot,
    reconfigureStatus: ReconfigureStatus,
    routeProbeReport: EffectiveRouteProbeReport?,
    isRouteProbeRunning: Boolean,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onApplyRecommendedPreset: () -> Unit,
    onWifiPolicyChange: (NetworkRoutePolicy) -> Unit,
    onMobilePolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetWifiPolicy: () -> Unit,
    onResetMobilePolicy: () -> Unit,
    onCopyPolicyDiagnostics: () -> Unit,
    onRunRouteProbe: () -> Unit,
    onCopyRouteProbeReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        RoutesSettingsPage.OVERVIEW -> {
            RoutesOverview(
                modifier = modifier,
                currentProfile = currentProfile,
                connectionMode = connectionMode,
                isProxyRunning = isProxyRunning,
                wifiPolicy = wifiPolicy,
                mobilePolicy = mobilePolicy,
                hasSavedWifiPolicy = hasSavedWifiPolicy,
                hasSavedMobilePolicy = hasSavedMobilePolicy,
                policySnapshot = policySnapshot,
                onConnectionModeChange = onConnectionModeChange,
                onApplyRecommendedPreset = onApplyRecommendedPreset,
                onOpenWifi = { onPageChange(RoutesSettingsPage.WIFI) },
                onOpenMobile = { onPageChange(RoutesSettingsPage.MOBILE) },
                onOpenAdvanced = { onPageChange(RoutesSettingsPage.ADVANCED) },
            )
        }
        RoutesSettingsPage.WIFI -> {
            RoutesSubpageScaffold(
                titleRes = R.string.routes_wifi_profile_title,
                onBack = { onPageChange(RoutesSettingsPage.OVERVIEW) },
                modifier = modifier,
            ) {
                RouteProfileEditor(
                    profileType = NetworkProfileType.WIFI,
                    policy = wifiPolicy,
                    isCurrentProfile = currentProfile.type == NetworkProfileType.WIFI,
                    hasSavedPolicy = hasSavedWifiPolicy,
                    isProxyRunning = isProxyRunning,
                    onPolicyChange = onWifiPolicyChange,
                    onResetPolicy = onResetWifiPolicy,
                )
            }
        }
        RoutesSettingsPage.MOBILE -> {
            RoutesSubpageScaffold(
                titleRes = R.string.routes_mobile_profile_title,
                onBack = { onPageChange(RoutesSettingsPage.OVERVIEW) },
                modifier = modifier,
            ) {
                RouteProfileEditor(
                    profileType = NetworkProfileType.MOBILE,
                    policy = mobilePolicy,
                    isCurrentProfile = currentProfile.type == NetworkProfileType.MOBILE,
                    hasSavedPolicy = hasSavedMobilePolicy,
                    isProxyRunning = isProxyRunning,
                    onPolicyChange = onMobilePolicyChange,
                    onResetPolicy = onResetMobilePolicy,
                )
            }
        }
        RoutesSettingsPage.ADVANCED -> {
            RoutesSubpageScaffold(
                titleRes = R.string.routes_overview_advanced_title,
                onBack = { onPageChange(RoutesSettingsPage.OVERVIEW) },
                modifier = modifier,
            ) {
                RouteAdvancedDiagnostics(
                    policySnapshot = policySnapshot,
                    reconfigureStatus = reconfigureStatus,
                    routeProbeReport = routeProbeReport,
                    isRouteProbeRunning = isRouteProbeRunning,
                    onCopyPolicyDiagnostics = onCopyPolicyDiagnostics,
                    onRunRouteProbe = onRunRouteProbe,
                    onCopyRouteProbeReport = onCopyRouteProbeReport,
                )
            }
        }
    }
}

@Composable
private fun RoutesSubpageScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
private fun RoutesOverview(
    currentProfile: NetworkProfile,
    connectionMode: ConnectionMode,
    isProxyRunning: Boolean,
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    hasSavedWifiPolicy: Boolean,
    hasSavedMobilePolicy: Boolean,
    policySnapshot: RoutePolicyDiagnosticsSnapshot,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onApplyRecommendedPreset: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenMobile: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentNetworkLabel = remember(currentProfile.type) {
        RoutePolicyDisplayNames.networkTypeLabel(context, currentProfile.type)
    }
    val currentProfileLabel = remember(policySnapshot.source, policySnapshot.hasSavedPolicyForType) {
        if (policySnapshot.hasSavedPolicyForType) {
            context.getString(R.string.routes_profile_configured)
        } else {
            RoutePolicyDisplayNames.sourceLabel(context, policySnapshot.source)
        }
    }
    val currentPreferenceLabel = remember(policySnapshot.policy.preferredRoute) {
        policySnapshot.policy.preferredRoute
            ?.let { RoutePolicyDisplayNames.routeLabel(context, it) }
            ?: context.getString(R.string.common_none)
    }
    val wifiSummary = remember(wifiPolicy) { formatPolicySummary(context, wifiPolicy) }
    val mobileSummary = remember(mobilePolicy) { formatPolicySummary(context, mobilePolicy) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProxyRunning) {
            SettingsSectionLockedSummary(R.string.settings_routes_locked)
            Spacer(modifier = Modifier.height(8.dp))
        }

        RoutesSectionCard(titleRes = R.string.routes_overview_current_title) {
            MetricLine(stringResource(R.string.routes_current_network), currentNetworkLabel)
            MetricLine(stringResource(R.string.routes_current_profile), currentProfileLabel)
            MetricLine(stringResource(R.string.routes_current_preference), currentPreferenceLabel)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            stringResource(R.string.connection_mode_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        ConnectionModePicker(
            connectionMode = connectionMode,
            enabled = !isProxyRunning,
            onConnectionModeChange = onConnectionModeChange,
        )
        connectionMode.hintRes()?.let { hintRes ->
            Text(
                stringResource(hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        RoutesSectionCard(titleRes = R.string.routes_overview_recommended_title) {
            Text(
                stringResource(R.string.route_policy_recommended_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedButton(
                onClick = onApplyRecommendedPreset,
                enabled = !isProxyRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.route_policy_recommended_apply))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            stringResource(R.string.routes_overview_profiles_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        RouteProfileSummaryRow(
            title = stringResource(R.string.route_policy_wifi_title),
            summary = wifiSummary,
            isActiveNow = currentProfile.type == NetworkProfileType.WIFI,
            isConfigured = hasSavedWifiPolicy,
            actionLabel = stringResource(R.string.routes_open_wifi),
            onClick = onOpenWifi,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RouteProfileSummaryRow(
            title = stringResource(R.string.route_policy_mobile_title),
            summary = mobileSummary,
            isActiveNow = currentProfile.type == NetworkProfileType.MOBILE,
            isConfigured = hasSavedMobilePolicy,
            actionLabel = stringResource(R.string.routes_open_mobile),
            onClick = onOpenMobile,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            stringResource(R.string.routes_overview_advanced_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        RoutesNavigationRow(
            title = stringResource(R.string.routes_diagnostics_advanced),
            subtitle = stringResource(R.string.routes_open_advanced),
            onClick = onOpenAdvanced,
        )
    }
}

@Composable
private fun ConnectionModePicker(
    connectionMode: ConnectionMode,
    enabled: Boolean,
    onConnectionModeChange: (ConnectionMode) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(connectionMode.displayLabelRes()))
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ConnectionMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.displayLabelRes())) },
                    onClick = {
                        expanded = false
                        onConnectionModeChange(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteProfileSummaryRow(
    title: String,
    summary: String,
    isActiveNow: Boolean,
    isConfigured: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isConfigured) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.routes_profile_configured)) },
                    )
                }
                if (isActiveNow) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.routes_profile_active_now)) },
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(
                onClick = onClick,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(actionLabel)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RoutesNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RouteProfileEditor(
    profileType: NetworkProfileType,
    policy: NetworkRoutePolicy,
    isCurrentProfile: Boolean,
    hasSavedPolicy: Boolean,
    isProxyRunning: Boolean,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedPolicy = NetworkRoutePolicyEditor.normalize(policy.copy(networkType = profileType))
    val controlsEnabled = !isProxyRunning
    val routeOrder = remember { NetworkRoutePolicyEditor.routeOrder }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProxyRunning) {
            Text(
                stringResource(R.string.route_policy_stop_to_edit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (isCurrentProfile) {
                Spacer(modifier = Modifier.width(6.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.routes_profile_active_now)) },
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.route_policy_enabled_routes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        routeOrder.forEach { route ->
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
                    stringResource(R.string.route_policy_allow_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.route_policy_allow_fallback_hint),
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
            onClick = onResetPolicy,
            enabled = controlsEnabled && hasSavedPolicy,
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.route_policy_reset))
        }
        Text(
            stringResource(R.string.route_policy_legacy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
fun RouteAdvancedDiagnostics(
    policySnapshot: RoutePolicyDiagnosticsSnapshot,
    reconfigureStatus: ReconfigureStatus,
    routeProbeReport: EffectiveRouteProbeReport?,
    isRouteProbeRunning: Boolean,
    onCopyPolicyDiagnostics: () -> Unit,
    onRunRouteProbe: () -> Unit,
    onCopyRouteProbeReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoutePolicyDiagnosticsCard(
            snapshot = policySnapshot,
            reconfigureStatus = reconfigureStatus,
            onCopyDiagnostics = onCopyPolicyDiagnostics,
        )
        RouteLevelDiagnosticsCard(
            report = routeProbeReport,
            isRunning = isRouteProbeRunning,
            onRunProbe = onRunRouteProbe,
            onCopyReport = onCopyRouteProbeReport,
        )
    }
}

@Composable
private fun RoutesSectionCard(
    titleRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
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
            Text(stringResource(route.labelRes()), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(route.hintRes()),
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

private fun formatPolicySummary(context: android.content.Context, policy: NetworkRoutePolicy): String {
    val normalized = NetworkRoutePolicyEditor.normalize(policy)
    val routes = normalized.enabledRoutesInOrder()
        .joinToString(", ") { context.getString(it.labelRes()) }
    val preferred = normalized.preferredRoute
        ?.let { context.getString(it.labelRes()) }
        ?: context.getString(R.string.common_none)
    return context.getString(R.string.route_policy_compact_enabled_routes, routes) +
        " · " +
        context.getString(R.string.route_policy_compact_preferred, preferred)
}

private fun NetworkRoutePolicy.enabledRoutesInOrder(): List<RouteKind> {
    return NetworkRoutePolicyEditor.routeOrder.filter { it in enabledRoutes }
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
