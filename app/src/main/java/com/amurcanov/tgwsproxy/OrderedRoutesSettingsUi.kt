package com.amurcanov.tgwsproxy

import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Visual structure follows the approved TGWSProxy_Routes_Revised.html mockup. */
@Composable
internal fun OrderedRoutesSettings(
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    onWifiPolicyChange: (NetworkRoutePolicy) -> Unit,
    onMobilePolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetWifiPolicy: () -> Unit,
    onResetMobilePolicy: () -> Unit,
    reconfigureStatus: ReconfigureStatus? = null,
    onOpenDiagnostics: () -> Unit = {},
) {
    var selectedNetwork by rememberSaveable { mutableStateOf(NetworkProfileType.WIFI) }
    var confirmReset by remember { mutableStateOf(false) }
    val policy = NetworkRoutePolicyEditor.normalize(
        if (selectedNetwork == NetworkProfileType.WIFI) wifiPolicy else mobilePolicy
    )
    val onPolicyChange = if (selectedNetwork == NetworkProfileType.WIFI) onWifiPolicyChange else onMobilePolicyChange

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.routes_network_profile), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = selectedNetwork == NetworkProfileType.WIFI,
                onClick = { selectedNetwork = NetworkProfileType.WIFI },
                label = { Text(stringResource(R.string.routes_wifi_tab)) },
                modifier = Modifier.weight(1f).testTag("routes.tab.wifi"),
            )
            FilterChip(
                selected = selectedNetwork == NetworkProfileType.MOBILE,
                onClick = { selectedNetwork = NetworkProfileType.MOBILE },
                label = { Text(stringResource(R.string.routes_mobile_tab)) },
                modifier = Modifier.weight(1f).testTag("routes.tab.mobile"),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.routes_priority_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.routes_enabled_counter, policy.activeRoutes.size, RouteKind.entries.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("routes.count"),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (policy.activeRoutes + policy.inactiveRoutes).forEach { route ->
                val index = policy.activeRoutes.indexOf(route)
                RoutePriorityRow(
                    route = route,
                    activeIndex = index,
                    activeCount = policy.activeRoutes.size,
                    policy = policy,
                    onPolicyChange = onPolicyChange,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.routes_fallback_heading), fontWeight = FontWeight.SemiBold)
                val context = LocalContext.current
                Text(
                    policy.activeRoutes.joinToString("  →  ") { context.getString(it.priorityLabel()) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("routes.chain"),
                )
                Text(
                    if (policy.activeRoutes.size == 1) stringResource(R.string.routes_fallback_none)
                    else stringResource(R.string.routes_fallback_next, policy.activeRoutes.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (reconfigureStatus?.type == ReconfigureStatusType.FAILED &&
            reconfigureStatus.networkType == selectedNetwork
        ) {
            Text(
                stringResource(R.string.routes_live_apply_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("routes.apply.error"),
            )
        }

        OutlinedButton(
            onClick = { confirmReset = true },
            modifier = Modifier.fillMaxWidth().testTag("routes.reset"),
        ) {
            Text(stringResource(R.string.routes_reset_selected))
        }
        TextButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.fillMaxWidth().testTag("routes.diagnostics"),
        ) {
            Text(stringResource(R.string.routes_overview_advanced_title))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.routes_reset_selected)) },
            text = { Text(stringResource(R.string.routes_reset_question)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    if (selectedNetwork == NetworkProfileType.WIFI) onResetWifiPolicy() else onResetMobilePolicy()
                }, modifier = Modifier.testTag("routes.reset.confirm")) {
                    Text(stringResource(R.string.routes_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.routes_reset_cancel))
                }
            },
        )
    }
}

@Composable
private fun RoutePriorityRow(
    route: RouteKind,
    activeIndex: Int,
    activeCount: Int,
    policy: NetworkRoutePolicy,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
) {
    val context = LocalContext.current
    val enabled = activeIndex >= 0
    val routeName = stringResource(route.priorityLabel())
    val dragLabel = stringResource(R.string.routes_drag_label, routeName)
    val upLabel = stringResource(R.string.routes_up_label, routeName)
    val downLabel = stringResource(R.string.routes_down_label, routeName)
    val toggleLabel = stringResource(R.string.routes_toggle_label, routeName)

    Card(
        modifier = Modifier.fillMaxWidth().testTag("routes.row.${route.prefValue}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "⋮⋮",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
                    .semantics { contentDescription = dragLabel }
                    .testTag("routes.drag.${route.prefValue}")
                    .then(
                        if (enabled) Modifier.pointerInput(route, policy.activeRoutes) {
                            var distance = 0f
                            detectDragGestures(onDragStart = { distance = 0f }) { change, delta ->
                                change.consume()
                                distance += delta.y
                                if (abs(distance) > 55.dp.toPx()) {
                                    val newIndex = (activeIndex + if (distance > 0f) 1 else -1)
                                        .coerceIn(0, activeCount - 1)
                                    onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(
                                        policy, activeIndex, newIndex
                                    ))
                                    distance = 0f
                                }
                            }
                        } else Modifier
                    )
                    .padding(vertical = 12.dp),
            )
            Text(
                if (enabled) (activeIndex + 1).toString() else "—",
                modifier = Modifier.width(22.dp),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(routeName, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!enabled) Text(
                    stringResource(R.string.routes_inactive_text),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                IconButton(
                    onClick = {
                        onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(
                            policy, activeIndex, activeIndex - 1
                        ))
                    },
                    enabled = enabled && activeIndex > 0,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = upLabel }
                        .testTag("routes.up.${route.prefValue}"),
                ) { Text("↑") }
                IconButton(
                    onClick = {
                        onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(
                            policy, activeIndex, activeIndex + 1
                        ))
                    },
                    enabled = enabled && activeIndex < activeCount - 1,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = downLabel }
                        .testTag("routes.down.${route.prefValue}"),
                ) { Text("↓") }
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (!checked && activeCount == 1) {
                        Toast.makeText(context, R.string.routes_minimum_one, Toast.LENGTH_SHORT).show()
                    } else {
                        onPolicyChange(NetworkRoutePolicyEditor.setRouteEnabled(policy, route, checked))
                    }
                },
                modifier = Modifier.semantics { contentDescription = toggleLabel }
                    .testTag("routes.toggle.${route.prefValue}"),
            )
        }
    }
}

internal fun RouteKind.priorityLabel(): Int = when (this) {
    RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
    RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
    RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
    RouteKind.AWG_WARP -> R.string.route_policy_awg_warp
    RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
}
