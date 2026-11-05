package com.amurcanov.tgwsproxy

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp

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
    // The source order is held constant until pointer-up; this keeps the pointer input
    // alive through a multi-row gesture instead of canceling it after one swap.
    var draggedRoute by remember(selectedNetwork) { mutableStateOf<RouteKind?>(null) }
    var dragDistancePx by remember(selectedNetwork) { mutableFloatStateOf(0f) }
    val rowStridePx = with(LocalDensity.current) { 85.dp.toPx() }
    val dragStartIndex = policy.activeRoutes.indexOf(draggedRoute)
    val dragStartDisplayIndex = policy.displayOrder.indexOf(draggedRoute)
    val activeSlots = policy.displayOrder.indices.filter {
        policy.displayOrder[it] in policy.enabledRoutes
    }
    val dragTargetIndex = RouteDragTarget.indexForDisplaySlots(
        dragStartIndex, policy.displayOrder, policy.activeRoutes, dragDistancePx, rowStridePx
    )
    val dragTargetDisplayIndex = if (dragStartIndex >= 0) {
        activeSlots.getOrNull(dragTargetIndex) ?: dragStartDisplayIndex
    } else {
        RouteDragTarget.index(
            dragStartDisplayIndex, policy.displayOrder.size, dragDistancePx, rowStridePx
        )
    }

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
            policy.displayOrder.forEachIndexed { position, route ->
                key(route) {
                    if (position > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                        )
                    }
                    val index = policy.activeRoutes.indexOf(route)
                    val dragging = draggedRoute == route && dragStartDisplayIndex >= 0
                    val shifting = when {
                        dragStartDisplayIndex < 0 || dragging -> 0f
                        dragStartIndex < 0 -> when {
                            position > dragStartDisplayIndex &&
                                position <= dragTargetDisplayIndex -> -rowStridePx
                            position >= dragTargetDisplayIndex &&
                                position < dragStartDisplayIndex -> rowStridePx
                            else -> 0f
                        }
                        index < 0 -> 0f
                        dragTargetIndex > dragStartIndex &&
                            index > dragStartIndex && index <= dragTargetIndex ->
                            (activeSlots[index - 1] - activeSlots[index]) * rowStridePx
                        dragTargetIndex < dragStartIndex &&
                            index >= dragTargetIndex && index < dragStartIndex ->
                            (activeSlots[index + 1] - activeSlots[index]) * rowStridePx
                        else -> 0f
                    }
                    val animatedShift by animateFloatAsState(
                        targetValue = shifting,
                        animationSpec = tween(durationMillis = 150),
                        label = "Route priority shift",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragDistancePx else animatedShift
                                shadowElevation = if (dragging) 5.dp.toPx() else 0f
                                alpha = 1f
                            }
                            .then(
                                if (dragging) Modifier.background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(8.dp),
                                ) else Modifier
                            ),
                    ) {
                        RoutePriorityRow(
                            route = route,
                            activeIndex = index,
                            activeCount = policy.activeRoutes.size,
                            policy = policy,
                            configurationRequired = (route == RouteKind.WORKER_WS && !workerConfigured) ||
                                (route == RouteKind.AWG_WARP && !awgConfigured),
                            workerConfigured = workerConfigured,
                            awgConfigured = awgConfigured,
                            onPolicyChange = onPolicyChange,
                            onDragStart = {
                                draggedRoute = route
                                dragDistancePx = 0f
                            },
                            onDragBy = { delta ->
                                if (draggedRoute == route) {
                                    val minimum = if (index >= 0) {
                                        (activeSlots.first() - activeSlots[index]) * rowStridePx
                                    } else {
                                        -position * rowStridePx
                                    }
                                    val maximum = if (index >= 0) {
                                        (activeSlots.last() - activeSlots[index]) * rowStridePx
                                    } else {
                                        (policy.displayOrder.lastIndex - position) * rowStridePx
                                    }
                                    dragDistancePx = (dragDistancePx + delta).coerceIn(minimum, maximum)
                                }
                            },
                            onDragEnd = {
                                if (draggedRoute == route) {
                                    val updated = if (index >= 0) {
                                        val target = RouteDragTarget.indexForDisplaySlots(
                                            index, policy.displayOrder, policy.activeRoutes,
                                            dragDistancePx, rowStridePx
                                        )
                                        NetworkRoutePolicyEditor.moveEnabledRoute(
                                            policy, index, target
                                        )
                                    } else {
                                        val target = RouteDragTarget.index(
                                            position, policy.displayOrder.size,
                                            dragDistancePx, rowStridePx
                                        )
                                        NetworkRoutePolicyEditor.moveInactiveRoute(
                                            policy, route, target
                                        )
                                    }
                                    draggedRoute = null
                                    dragDistancePx = 0f
                                    if (updated.displayOrder != policy.displayOrder) {
                                        onPolicyChange(updated)
                                    }
                                }
                            },
                            onDragCancel = {
                                draggedRoute = null
                                dragDistancePx = 0f
                            },
                        )
                    }
                }
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
    workerConfigured: Boolean,
    awgConfigured: Boolean,
    onPolicyChange: (NetworkRoutePolicy) -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val context = LocalContext.current
    val enabled = activeIndex >= 0
    val routeName = stringResource(route.priorityLabel())
    val dragLabel = stringResource(R.string.routes_drag_label, routeName)
    val upLabel = stringResource(R.string.routes_up_label, routeName)
    val downLabel = stringResource(R.string.routes_down_label, routeName)
    val toggleLabel = stringResource(R.string.routes_toggle_label, routeName)
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDragBy)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentCancel by rememberUpdatedState(onDragCancel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routes.row.${route.prefValue}")
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The grip reorders both enabled and disabled rows without toggling either.
        Box(
            modifier = Modifier
                .width(28.dp)
                .heightIn(min = 84.dp)
                .semantics { contentDescription = dragLabel }
                .testTag("routes.drag.${route.prefValue}")
                .pointerInput(route) {
                    detectDragGestures(
                        onDragStart = { currentStart() },
                        onDragEnd = { currentEnd() },
                        onDragCancel = { currentCancel() },
                    ) { change, delta ->
                        change.consume()
                        currentDrag(delta.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                    val updated = NetworkRoutePolicyEditor.setRouteEnabled(policy, route, checked)
                    if (!NetworkRoutePolicyEditor.hasConfiguredOutboundRoute(
                            updated, workerConfigured, awgConfigured
                        )
                    ) {
                        Toast.makeText(context, R.string.routes_no_configured_route, Toast.LENGTH_SHORT).show()
                    } else {
                        onPolicyChange(updated)
                    }
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
