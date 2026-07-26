package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProxyFrontendSettingsSection(
    frontendType: LocalProxyFrontendType,
    mtProtoConfig: MtProtoProxyConfig,
    mtProtoUiStatus: MtProtoUiStatus,
    controlsEnabled: Boolean,
    onFrontendTypeChange: (LocalProxyFrontendType) -> Unit,
    onMtProtoFakeTlsDomainChange: (String) -> Unit,
    onMtProtoFakeTlsPassthroughChange: (Boolean) -> Unit,
    onRegenerateSecret: () -> Unit,
    onCopyTelegramLink: () -> Unit,
    onShareTelegramLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(
        titleRes = R.string.proxy_frontend_mode_label,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FrontendChoiceChip(
                selected = frontendType == LocalProxyFrontendType.SOCKS5,
                title = stringResource(R.string.proxy_frontend_socks5_title),
                subtitle = stringResource(R.string.proxy_frontend_socks5_subtitle),
                enabled = controlsEnabled,
                onClick = { onFrontendTypeChange(LocalProxyFrontendType.SOCKS5) },
            )
            FrontendChoiceChip(
                selected = frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
                title = stringResource(R.string.proxy_frontend_mtproto_title),
                subtitle = stringResource(R.string.proxy_frontend_mtproto_subtitle),
                enabled = controlsEnabled,
                onClick = { onFrontendTypeChange(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) },
            )
        }

        if (frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
            MtProtoFrontendDetails(
                config = mtProtoConfig,
                status = mtProtoUiStatus,
                controlsEnabled = controlsEnabled,
                onFakeTlsDomainChange = onMtProtoFakeTlsDomainChange,
                onFakeTlsPassthroughChange = onMtProtoFakeTlsPassthroughChange,
                onRegenerateSecret = onRegenerateSecret,
                onCopyTelegramLink = onCopyTelegramLink,
                onShareTelegramLink = onShareTelegramLink,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrontendChoiceChip(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MtProtoFrontendDetails(
    config: MtProtoProxyConfig,
    status: MtProtoUiStatus,
    controlsEnabled: Boolean,
    onFakeTlsDomainChange: (String) -> Unit,
    onFakeTlsPassthroughChange: (Boolean) -> Unit,
    onRegenerateSecret: () -> Unit,
    onCopyTelegramLink: () -> Unit,
    onShareTelegramLink: () -> Unit,
) {
    val isFakeTlsDomainValid = config.fakeTlsDomain.isBlank() ||
        MtProtoFakeTlsDomain.isValid(MtProtoFakeTlsDomain.normalize(config.fakeTlsDomain))
    val fakeTlsPassthroughAvailable = config.fakeTlsDomain.isNotBlank() &&
        isFakeTlsDomainValid
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.mtproto_experimental_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }

    OutlinedTextField(
        value = config.host,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(stringResource(R.string.mtproto_local_host_label)) },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        singleLine = true,
    )

    OutlinedTextField(
        value = config.fakeTlsDomain,
        onValueChange = { value ->
            if (value.length <= 253) {
                onFakeTlsDomainChange(value)
            }
        },
        enabled = controlsEnabled,
        isError = config.fakeTlsDomain.isNotBlank() && !isFakeTlsDomainValid,
        label = { Text(stringResource(R.string.mtproto_fake_tls_domain_label)) },
        supportingText = {
            if (config.fakeTlsDomain.isNotBlank() && !isFakeTlsDomainValid) {
                Text(stringResource(R.string.mtproto_validation_fake_tls_domain_invalid))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        singleLine = true,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mtproto_fake_tls_passthrough_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = config.fakeTlsPassthrough && fakeTlsPassthroughAvailable,
            onCheckedChange = onFakeTlsPassthroughChange,
            enabled = controlsEnabled && fakeTlsPassthroughAvailable,
        )
    }

    MetricLine(
        stringResource(R.string.mtproto_secret_status_label),
        stringResource(R.string.mtproto_secret_status_configured),
    )
    MetricLine(
        stringResource(R.string.mtproto_runtime_status_label),
        stringResource(status.labelRes()),
    )

    if (status == MtProtoUiStatus.MTPROTO_LOCAL_ONLY_LIMITED) {
        Text(
            text = stringResource(R.string.mtproto_local_only_limited_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onRegenerateSecret,
            enabled = controlsEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.mtproto_regenerate_secret))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCopyTelegramLink,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.mtproto_copy_link))
            }
            OutlinedButton(
                onClick = onShareTelegramLink,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.mtproto_share_link))
            }
        }
    }
}
