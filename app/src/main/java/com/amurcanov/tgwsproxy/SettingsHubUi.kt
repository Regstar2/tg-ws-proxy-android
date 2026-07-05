package com.amurcanov.tgwsproxy

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SettingsPage {
    HOME,
    CONNECTION,
    ROUTES,
    CLOUDFLARE,
    DIAGNOSTICS_LOGS,
    APP,
}

@Composable
fun SettingsPageHeader(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        subtitleRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun SettingsSummaryCard(
    networkLabel: String,
    proxyAddress: String,
    routeLabel: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.settings_current_config_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MetricLine(stringResource(R.string.settings_network_label), networkLabel)
            MetricLine(stringResource(R.string.settings_proxy_label), proxyAddress)
            MetricLine(stringResource(R.string.settings_route_label), routeLabel)
        }
    }
}

@Composable
fun SettingsNavigationCard(
    onConnectionClick: () -> Unit,
    onRoutesClick: () -> Unit,
    onCloudflareClick: () -> Unit,
    onDiagnosticsLogsClick: () -> Unit,
    onAppClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.settings_sections_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_section_connection_title),
                subtitle = stringResource(R.string.settings_section_connection_subtitle),
                onClick = onConnectionClick,
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_section_routes_title),
                subtitle = stringResource(R.string.settings_section_routes_subtitle),
                onClick = onRoutesClick,
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_section_cloudflare_title),
                subtitle = stringResource(R.string.settings_section_cloudflare_subtitle),
                onClick = onCloudflareClick,
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_section_diagnostics_logs_title),
                subtitle = stringResource(R.string.settings_section_diagnostics_logs_subtitle),
                onClick = onDiagnosticsLogsClick,
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_section_app_title),
                subtitle = stringResource(R.string.settings_section_app_subtitle),
                onClick = onAppClick,
            )
        }
    }
}

@Composable
fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SettingsQuickActionsCard(
    onApplyRecommendedRoutes: () -> Unit,
    onCheckRoutes: () -> Unit,
    applyEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_quick_actions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(
                onClick = onApplyRecommendedRoutes,
                enabled = applyEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_apply_recommended_routes))
            }
            OutlinedButton(
                onClick = onCheckRoutes,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_check_routes))
            }
        }
    }
}

@Composable
fun SettingsHomeScreen(
    networkLabel: String,
    proxyAddress: String,
    routeLabel: String,
    applyRecommendedEnabled: Boolean,
    onNavigate: (SettingsPage) -> Unit,
    onApplyRecommendedRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSummaryCard(
            networkLabel = networkLabel,
            proxyAddress = proxyAddress,
            routeLabel = routeLabel,
        )
        SettingsNavigationCard(
            onConnectionClick = { onNavigate(SettingsPage.CONNECTION) },
            onRoutesClick = { onNavigate(SettingsPage.ROUTES) },
            onCloudflareClick = { onNavigate(SettingsPage.CLOUDFLARE) },
            onDiagnosticsLogsClick = { onNavigate(SettingsPage.DIAGNOSTICS_LOGS) },
            onAppClick = { onNavigate(SettingsPage.APP) },
        )
        SettingsQuickActionsCard(
            onApplyRecommendedRoutes = onApplyRecommendedRoutes,
            onCheckRoutes = { onNavigate(SettingsPage.DIAGNOSTICS_LOGS) },
            applyEnabled = applyRecommendedEnabled,
        )
    }
}
