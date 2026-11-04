package com.amurcanov.tgwsproxy

import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Exactly one route list per selected network. No legacy mode/preset/preferred/Auto controls.
 * Editing a route changes the repository immediately through onPolicyChange.
 */
@Composable
internal fun OrderedRoutesSettings(
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    onWifiPolicyChange: (NetworkRoutePolicy) -> Unit,
    onMobilePolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetWifiPolicy: () -> Unit,
    onResetMobilePolicy: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var selectedType by rememberSaveable { mutableStateOf(NetworkProfileType.WIFI) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val selectedPolicy = if (selectedType == NetworkProfileType.WIFI) wifiPolicy else mobilePolicy
    val policy = NetworkRoutePolicyEditor.normalize(selectedPolicy)
    val onPolicyChange = if (selectedType == NetworkProfileType.WIFI) onWifiPolicyChange else onMobilePolicyChange

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedType == NetworkProfileType.WIFI,
                onClick = { selectedType = NetworkProfileType.WIFI },
                label = { Text(stringResource(R.string.routes_profile_wifi_tab)) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selectedType == NetworkProfileType.MOBILE,
                onClick = { selectedType = NetworkProfileType.MOBILE },
                label = { Text(stringResource(R.string.routes_profile_mobile_tab)) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.routes_order_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.routes_enabled_count, policy.activeRoutes.size, RouteKind.entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
                (policy.activeRoutes + policy.inactiveRoutes).forEach { route ->
                    val index = policy.activeRoutes.indexOf(route)
                    OrderedRouteRow(
                        route = route,
                        activeIndex = index,
                        activeCount = policy.activeRoutes.size,
                        policy = policy,
                        onPolicyChange = onPolicyChange,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.routes_fallback_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (policy.activeRoutes.size == 1) {
                stringResource(R.string.routes_no_fallback)
            } else {
                policy.activeRoutes.joinToString(" → ") { route ->
                    stringResource(route.orderedRouteLabelRes())
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { showResetConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.routes_reset_profile))
        }
        TextButton(onClick = onOpenDiagnostics) {
            Text(stringResource(R.string.routes_diagnostics_advanced))
        }
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.routes_reset_profile)) },
            text = { Text(stringResource(R.string.routes_reset_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirmation = false
                    if (selectedType == NetworkProfileType.WIFI) onResetWifiPolicy()
                    else onResetMobilePolicy()
                }) { Text(stringResource(R.string.routes_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.routes_reset_cancel))
                }
            },
        )
    }
}

@Composable
private fun OrderedRouteRow(
    route: RouteKind,
    activeIndex: Int,
    activeCount: Int,
    policy: NetworkRoutePolicy,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
) {
    val context = LocalContext.current
    val enabled = activeIndex >= 0
    val label = stringResource(route.orderedRouteLabelRes())
    val dragDescription = stringResource(R.string.routes_drag_route, label)
    val moveUpDescription = stringResource(R.string.routes_move_up, label)
    val moveDownDescription = stringResource(R.string.routes_move_down, label)
    var dragDistance = 0f
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "⋮⋮",
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(42.dp)
                .semantics { contentDescription = dragDescription }
                .then(
                    if (enabled) Modifier.pointerInput(route, policy.activeRoutes) {
                        detectDragGestures(
                            onDragStart = { dragDistance = 0f },
                            onDrag = { change, drag ->
                                change.consume()
                                dragDistance += drag.y
                                val step = 65.dp.toPx()
                                if (abs(dragDistance) >= step) {
                                    val destination = (activeIndex + if (dragDistance > 0f) 1 else -1)
                                        .coerceIn(0, activeCount - 1)
                                    onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(policy, activeIndex, destination))
                                    dragDistance = 0f
                                }
                            },
                        )
                    } else Modifier,
                )
                .padding(top = 10.dp),
        )
        Text(
            text = if (enabled) (activeIndex + 1).toString() else "",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.routes_inactive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            IconButton(
                onClick = { onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(policy, activeIndex, activeIndex - 1)) },
                enabled = enabled && activeIndex > 0,
                modifier = Modifier.semantics { contentDescription = moveUpDescription },
            ) { Text("↑") }
            IconButton(
                onClick = { onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(policy, activeIndex, activeIndex + 1)) },
                enabled = enabled && activeIndex < activeCount - 1,
                modifier = Modifier.semantics { contentDescription = moveDownDescription },
            ) { Text("↓") }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (!checked && activeCount == 1) {
                    Toast.makeText(context, context.getString(R.string.routes_last_route_error), Toast.LENGTH_SHORT).show()
                } else {
                    onPolicyChange(NetworkRoutePolicyEditor.setRouteEnabled(policy, route, checked))
                }
            },
            modifier = Modifier.semantics {
                contentDescription = context.getString(R.string.routes_toggle_route, label)
            },
        )
    }
}

private fun RouteKind.orderedRouteLabelRes(): Int = when (this) {
    RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
    RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
    RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
    RouteKind.AWG_WARP -> R.string.route_policy_awg_warp
    RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
}
