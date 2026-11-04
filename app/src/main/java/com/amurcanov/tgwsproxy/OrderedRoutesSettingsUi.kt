package com.amurcanov.tgwsproxy

import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Compact ordered route editor with independent network profiles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderedRoutesSettings(
    wifiPolicy: NetworkRoutePolicy,
    mobilePolicy: NetworkRoutePolicy,
    onWifiPolicyChange: (NetworkRoutePolicy) -> Unit,
    onMobilePolicyChange: (NetworkRoutePolicy) -> Unit,
    onResetWifiPolicy: () -> Unit,
    onResetMobilePolicy: () -> Unit,
    reconfigureStatus: ReconfigureStatus? = null,
    workerConfigured: Boolean = false,
    awgConfigured: Boolean = false,
    onOpenDiagnostics: () -> Unit = {},
) {
    var selectedNetwork by rememberSaveable { mutableStateOf(NetworkProfileType.WIFI) }
    var confirmReset by remember { mutableStateOf(false) }
    val policy = NetworkRoutePolicyEditor.normalize(
        if (selectedNetwork == NetworkProfileType.WIFI) wifiPolicy else mobilePolicy
    )
    val onPolicyChange = if (selectedNetwork == NetworkProfileType.WIFI) onWifiPolicyChange else onMobilePolicyChange

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.routes_network_profile),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                selected = selectedNetwork == NetworkProfileType.WIFI,
                onClick = { selectedNetwork = NetworkProfileType.WIFI },
                icon = {},
                label = {
                    Text(
                        stringResource(R.string.routes_wifi_tab),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("routes.tab.wifi"),
            )
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                selected = selectedNetwork == NetworkProfileType.MOBILE,
                onClick = { selectedNetwork = NetworkProfileType.MOBILE },
                icon = {},
                label = {
                    Text(
                        stringResource(R.string.routes_mobile_tab),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("routes.tab.mobile"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.routes_priority_heading),
                style = MaterialTheme.typography.titleSmall,
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

        // One compact settings list, rather than five oversized independent cards.
        Card(
            modifier = Modifier.fillMaxWidth().testTag("routes.list"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f),
            ),
        ) {
            (policy.activeRoutes + policy.inactiveRoutes).forEachIndexed { position, route ->
                if (position > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                    )
                }
                val index = policy.activeRoutes.indexOf(route)
                RoutePriorityRow(
                    route = route,
                    activeIndex = index,
                    activeCount = policy.activeRoutes.size,
                    policy = policy,
                    configurationRequired = (route == RouteKind.WORKER_WS && !workerConfigured) ||
                        (route == RouteKind.AWG_WARP && !awgConfigured),
                    onPolicyChange = onPolicyChange,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(R.string.routes_fallback_heading),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                val context = LocalContext.current
                Text(
                    policy.activeRoutes.joinToString("  →  ") { context.getString(it.priorityLabel()) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("routes.chain"),
                )
                Text(
                    if (policy.activeRoutes.size == 1) stringResource(R.string.routes_fallback_none)
                    else stringResource(R.string.routes_fallback_next, policy.activeRoutes.size),
                    style = MaterialTheme.typography.labelSmall,
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("routes.reset"),
        ) {
            Text(stringResource(R.string.routes_reset_selected))
        }
        TextButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.align(Alignment.End).testTag("routes.diagnostics"),
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
    configurationRequired: Boolean,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
) {
    val context = LocalContext.current
    val enabled = activeIndex >= 0
    val routeName = stringResource(route.priorityLabel())
    val dragLabel = stringResource(R.string.routes_drag_label, routeName)
    val upLabel = stringResource(R.string.routes_up_label, routeName)
    val downLabel = stringResource(R.string.routes_down_label, routeName)
    val toggleLabel = stringResource(R.string.routes_toggle_label, routeName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routes.row.${route.prefValue}")
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The grip remains the only draggable surface; it never reorders inactive routes.
        Box(
            modifier = Modifier
                .width(28.dp)
                .heightIn(min = 84.dp)
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
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 0.8f else 0.35f
                ),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = if (enabled) (activeIndex + 1).toString() else "—",
            modifier = Modifier.width(18.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = routeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!enabled) {
                Text(
                    stringResource(R.string.routes_inactive_text),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (configurationRequired) {
                Text(
                    stringResource(R.string.routes_configuration_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.width(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(
                        policy, activeIndex, activeIndex - 1
                    ))
                },
                enabled = enabled && activeIndex > 0,
                modifier = Modifier.size(40.dp)
                    .semantics { contentDescription = upLabel }
                    .testTag("routes.up.${route.prefValue}"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(
                onClick = {
                    onPolicyChange(NetworkRoutePolicyEditor.moveEnabledRoute(
                        policy, activeIndex, activeIndex + 1
                    ))
                },
                enabled = enabled && activeIndex < activeCount - 1,
                modifier = Modifier.size(40.dp)
                    .semantics { contentDescription = downLabel }
                    .testTag("routes.down.${route.prefValue}"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
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
            modifier = Modifier
                .semantics { contentDescription = toggleLabel }
                .testTag("routes.toggle.${route.prefValue}"),
        )
    }

}

internal fun RouteKind.priorityLabel(): Int = when (this) {
    RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
    RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
    RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
    RouteKind.AWG_WARP -> R.string.route_policy_awg_warp
    RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
}
