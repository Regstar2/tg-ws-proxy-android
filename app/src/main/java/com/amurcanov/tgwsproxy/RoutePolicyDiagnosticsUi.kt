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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RoutePolicyDiagnosticsCard(
    snapshot: RoutePolicyDiagnosticsSnapshot,
    reconfigureStatus: ReconfigureStatus,
    onCopyDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val enabledRoutes = snapshot.policy.enabledRoutesInOrder()
        .joinToString(", ") { RoutePolicyDisplayNames.routeLabel(context, it) }
    val preferred = snapshot.policy.preferredRoute
        ?.let { RoutePolicyDisplayNames.routeLabel(context, it) }
        ?: stringResource(R.string.common_none)
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.route_policy_diagnostics_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            CompactDiagnosticLine(
                stringResource(
                    R.string.route_policy_compact_network,
                    RoutePolicyDisplayNames.networkTypeLabel(context, snapshot.profile.type),
                ),
            )
            CompactDiagnosticLine(
                stringResource(
                    R.string.route_policy_compact_source,
                    RoutePolicyDisplayNames.sourceLabel(context, snapshot.source),
                ),
            )
            CompactDiagnosticLine(
                stringResource(
                    R.string.route_policy_compact_legacy_mode,
                    stringResource(snapshot.legacyMode.displayLabelRes()),
                ),
            )
            CompactDiagnosticLine(
                stringResource(R.string.route_policy_compact_enabled_routes, enabledRoutes),
            )
            CompactDiagnosticLine(
                stringResource(R.string.route_policy_compact_preferred, preferred),
            )
            CompactDiagnosticLine(
                stringResource(
                    R.string.route_policy_compact_fallback,
                    stringResource(if (snapshot.policy.allowFallback) R.string.common_yes else R.string.common_no),
                ),
            )
            CompactDiagnosticLine(
                stringResource(
                    R.string.route_policy_compact_saved_policy,
                    stringResource(if (snapshot.hasSavedPolicyForType) R.string.common_yes else R.string.common_no),
                ),
            )
            CompactDiagnosticLine(reconfigureStatusLabel(reconfigureStatus))
            FilledTonalButton(
                onClick = onCopyDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.route_policy_copy_diagnostics))
            }
        }
    }
}

@Composable
private fun CompactDiagnosticLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun reconfigureStatusLabel(status: ReconfigureStatus): String {
    val context = LocalContext.current
    val network = status.networkType?.let {
        RoutePolicyDisplayNames.networkTypeLabel(context, it)
    }.orEmpty()
    return when (status.type) {
        ReconfigureStatusType.NONE -> stringResource(R.string.route_policy_last_reconfigure_none)
        ReconfigureStatusType.SUCCESS -> stringResource(R.string.route_policy_last_reconfigure_success, network)
        ReconfigureStatusType.SKIPPED -> stringResource(R.string.route_policy_last_reconfigure_skipped, status.message)
        ReconfigureStatusType.FAILED -> stringResource(R.string.route_policy_last_reconfigure_failed, status.message)
    }
}

private fun NetworkRoutePolicy.enabledRoutesInOrder(): List<RouteKind> {
    return NetworkRoutePolicyEditor.routeOrder.filter { it in enabledRoutes }
}
