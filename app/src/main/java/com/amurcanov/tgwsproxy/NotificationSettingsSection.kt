package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsSection(
    prefs: NotificationPreferences,
    onChange: (NotificationPreferences) -> Unit,
) {
    val context = LocalContext.current
    var modeExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = modeExpanded,
        onExpandedChange = { modeExpanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        OutlinedTextField(
            value = when (prefs.displayMode) {
                NotificationDisplayMode.NORMAL -> stringResource(R.string.notification_mode_normal)
                NotificationDisplayMode.COMPACT -> stringResource(R.string.notification_mode_compact)
                NotificationDisplayMode.MINIMAL -> stringResource(R.string.notification_mode_minimal)
            },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.notification_display_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
            NotificationDisplayMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (mode) {
                                NotificationDisplayMode.NORMAL -> stringResource(R.string.notification_mode_normal)
                                NotificationDisplayMode.COMPACT -> stringResource(R.string.notification_mode_compact)
                                NotificationDisplayMode.MINIMAL -> stringResource(R.string.notification_mode_minimal)
                            },
                        )
                    },
                    onClick = {
                        onChange(prefs.copy(displayMode = mode))
                        modeExpanded = false
                    },
                )
            }
        }
    }
    ToggleRow(stringResource(R.string.metrics_show_in_app), prefs.showMetricsInApp) {
        onChange(prefs.copy(showMetricsInApp = it))
    }
    ToggleRow(stringResource(R.string.metrics_show_in_notification), prefs.showMetricsInNotification) {
        onChange(prefs.copy(showMetricsInNotification = it))
    }
    ToggleRow(stringResource(R.string.notification_actions_enabled), prefs.notificationActionsEnabled) {
        onChange(prefs.copy(notificationActionsEnabled = it))
    }
    OutlinedButton(
        onClick = { NotificationPreferences.openSystemNotificationSettings(context) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 12.dp),
    ) {
        Text(stringResource(R.string.open_android_notification_settings))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
