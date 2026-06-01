package com.amurcanov.tgwsproxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun LegacyConnectionModeSection(
    cfProxyEnabled: Boolean,
    cfProxyPriority: Boolean,
    cfProxyOnly: Boolean,
    controlsEnabled: Boolean,
    onCfProxyEnabledChange: (Boolean) -> Unit,
    onCfProxyPriorityChange: (Boolean) -> Unit,
    onCfProxyOnlyChange: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text = if (expanded) {
            stringResource(R.string.legacy_connection_hide)
        } else {
            stringResource(R.string.legacy_connection_show)
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    )
    if (!expanded) {
        return
    }
    Text(
        text = stringResource(R.string.legacy_connection_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    LegacySwitchRow(
        title = stringResource(R.string.cf_proxy_title),
        hint = stringResource(R.string.cf_proxy_hint),
        checked = cfProxyEnabled,
        enabled = controlsEnabled,
        onCheckedChange = onCfProxyEnabledChange,
    )
    LegacySwitchRow(
        title = stringResource(R.string.cf_first_title),
        hint = stringResource(R.string.cf_first_hint),
        checked = cfProxyPriority,
        enabled = controlsEnabled && cfProxyEnabled,
        onCheckedChange = onCfProxyPriorityChange,
    )
    LegacySwitchRow(
        title = stringResource(R.string.cf_only_title),
        hint = stringResource(R.string.cf_only_hint),
        checked = cfProxyOnly,
        enabled = controlsEnabled,
        onCheckedChange = onCfProxyOnlyChange,
    )
}

@Composable
private fun LegacySwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
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
