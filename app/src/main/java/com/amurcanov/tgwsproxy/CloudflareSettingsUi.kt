package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerPoolSettingsScreen
import com.amurcanov.tgwsproxy.worker.WorkerUrlSanitizer
import com.amurcanov.tgwsproxy.worker.WorkerValidationError
import java.text.DateFormat
import java.util.Date

enum class CloudflareSettingsPage {
    OVERVIEW,
    WORKER_POOL,
    PROXY_DOMAINS,
    DOMAIN_LIST,
}

private enum class CfDomainListFilter {
    ALL,
    MANUAL,
    UPSTREAM,
    BUILTIN,
    COOLDOWN,
    FAILED,
    UNCHECKED,
}

data class CfDomainsSummary(
    val activeCount: Int,
    val cooldownCount: Int,
    val manualCount: Int,
    val upstreamCount: Int,
    val builtInCount: Int,
    val totalRows: Int,
)

@Composable
fun CloudflareSettingsScreen(
    page: CloudflareSettingsPage,
    onPageChange: (CloudflareSettingsPage) -> Unit,
    isProxyRunning: Boolean,
    workerDomainText: String,
    workerEnabled: Boolean,
    workerNormalizeHint: String?,
    workerPoolEnabled: Boolean,
    workerPoolWorkers: List<WorkerEndpoint>,
    workerPoolSelectedWorker: WorkerEndpoint?,
    workerPoolUiState: com.amurcanov.tgwsproxy.worker.WorkerPoolUiState,
    maskDomainsInSettings: Boolean,
    onWorkerDomainChange: (String) -> Unit,
    onWorkerDomainBlur: () -> Unit,
    onWorkerEnabledChange: (Boolean) -> Unit,
    onWorkerPoolEnabledChange: (Boolean) -> Unit,
    workerDestinationMode: com.amurcanov.tgwsproxy.worker.WorkerDestinationMode,
    flowsealMediaFixEnabled: Boolean,
    flowsealMediaFixDcText: String,
    flowsealMediaFixIpText: String,
    flowsealDcOnlyEnabled: Boolean,
    onFlowsealDcOnlyEnabledChange: (Boolean) -> Unit,
    onWorkerDestinationModeChange: (com.amurcanov.tgwsproxy.worker.WorkerDestinationMode) -> Unit,
    onFlowsealMediaFixEnabledChange: (Boolean) -> Unit,
    onFlowsealMediaFixDcChange: (String) -> Unit,
    onFlowsealMediaFixIpChange: (String) -> Unit,
    onFlowsealDestinationSettingsBlur: () -> Unit,
    onSelectionStrategyChange: (com.amurcanov.tgwsproxy.worker.WorkerSelectionStrategy) -> Unit,
    onSelectWorker: (String) -> Unit,
    onAddWorker: (name: String, url: String, enabled: Boolean, priority: Int) -> WorkerValidationError?,
    onUpdateWorker: (WorkerEndpoint) -> WorkerValidationError?,
    onDeleteWorker: (String) -> Unit,
    onSetWorkerEnabled: (String, Boolean) -> Unit,
    onCheckWorker: (String) -> Unit,
    onCheckAllWorkers: () -> Unit,
    onWorkerPoolScreenOpened: () -> Unit = {},
    onOpenDiagnosticsFromWorkerPool: () -> Unit = {},
    onTestWorker: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
    manualCfDomainsText: String,
    invalidManualCfDomains: List<String>,
    onManualCfDomainsChange: (String) -> Unit,
    cfDomainRows: List<CfDomainHealth>,
    cfDomainLastCheckAtMs: Long?,
    upstreamState: CfDomainUpstreamState,
    autoUpdateEnabled: Boolean,
    mirrorEnabled: Boolean,
    mirrorUrl: String,
    mirrorValidationError: String?,
    isUpdateRunning: Boolean,
    isDiagRunning: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onMirrorEnabledChange: (Boolean) -> Unit,
    onMirrorUrlChange: (String) -> Unit,
    onTestCfDomains: () -> Unit,
    onUpdateUpstream: () -> Unit,
    onResetCooldown: () -> Unit,
    onTestPrimarySource: () -> Unit,
    onTestMirrorSource: () -> Unit,
    overviewFooter: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val summary = rememberCfDomainsSummary(cfDomainRows, upstreamState, manualCfDomainsText)
    val lastUpdateLabel = remember(upstreamState.lastSuccessfulUpdateAtMs) {
        formatCfTimestamp(context, upstreamState.lastSuccessfulUpdateAtMs)
    }

    when (page) {
        CloudflareSettingsPage.OVERVIEW -> {
            CloudflareOverviewScreen(
                modifier = modifier,
                isProxyRunning = isProxyRunning,
                workerConfigured = WorkerDomain.normalize(workerDomainText).isNotBlank() ||
                    workerPoolWorkers.any { it.enabled },
                workerDomainText = workerDomainText,
                workerPoolEnabled = workerPoolEnabled,
                workerPoolSelectedWorker = workerPoolSelectedWorker,
                workerPoolWorkerCount = workerPoolWorkers.size,
                maskDomainsInSettings = maskDomainsInSettings,
                domainsSummary = summary,
                lastUpdateLabel = lastUpdateLabel,
                upstreamState = upstreamState,
                isDiagRunning = isDiagRunning,
                onOpenWorkerPool = { onPageChange(CloudflareSettingsPage.WORKER_POOL) },
                onOpenProxyDomains = { onPageChange(CloudflareSettingsPage.PROXY_DOMAINS) },
                onTestCfDomains = onTestCfDomains,
                onOpenWorkerHelp = onOpenWorkerHelp,
                overviewFooter = overviewFooter,
            )
        }
        CloudflareSettingsPage.WORKER_POOL -> {
            CloudflareSubpageScaffold(
                titleRes = R.string.cf_worker_pool_title,
                onBack = { onPageChange(CloudflareSettingsPage.OVERVIEW) },
                modifier = modifier,
            ) {
                WorkerPoolSettingsScreen(
                    uiState = workerPoolUiState,
                    legacyWorkerDomainText = workerDomainText,
                    legacyWorkerEnabled = workerEnabled,
                    legacyWorkerNormalizeHint = workerNormalizeHint,
                    existingWorkerUrls = workerPoolWorkers.map { it.url },
                    workerDestinationMode = workerDestinationMode,
                    flowsealMediaFixEnabled = flowsealMediaFixEnabled,
                    flowsealMediaFixDcText = flowsealMediaFixDcText,
                    flowsealMediaFixIpText = flowsealMediaFixIpText,
                    flowsealDcOnlyEnabled = flowsealDcOnlyEnabled,
                    onFlowsealDcOnlyEnabledChange = onFlowsealDcOnlyEnabledChange,
                    onWorkerDestinationModeChange = onWorkerDestinationModeChange,
                    onFlowsealMediaFixEnabledChange = onFlowsealMediaFixEnabledChange,
                    onFlowsealMediaFixDcChange = onFlowsealMediaFixDcChange,
                    onFlowsealMediaFixIpChange = onFlowsealMediaFixIpChange,
                    onFlowsealDestinationSettingsBlur = onFlowsealDestinationSettingsBlur,
                    onScreenOpened = onWorkerPoolScreenOpened,
                    onPoolEnabledChange = onWorkerPoolEnabledChange,
                    onSelectionStrategyChange = onSelectionStrategyChange,
                    onSelectWorker = onSelectWorker,
                    onAddWorker = onAddWorker,
                    onUpdateWorker = onUpdateWorker,
                    onDeleteWorker = onDeleteWorker,
                    onSetWorkerEnabled = onSetWorkerEnabled,
                    onCheckWorker = onCheckWorker,
                    onCheckAllWorkers = onCheckAllWorkers,
                    onOpenDiagnostics = onOpenDiagnosticsFromWorkerPool,
                    onLegacyWorkerDomainChange = onWorkerDomainChange,
                    onLegacyWorkerDomainBlur = onWorkerDomainBlur,
                    onLegacyWorkerEnabledChange = onWorkerEnabledChange,
                    onTestLegacyWorker = onTestWorker,
                    onOpenWorkerHelp = onOpenWorkerHelp,
                )
            }
        }
        CloudflareSettingsPage.PROXY_DOMAINS -> {
            CloudflareSubpageScaffold(
                titleRes = R.string.cf_proxy_domains_title,
                onBack = { onPageChange(CloudflareSettingsPage.OVERVIEW) },
                modifier = modifier,
            ) {
                CfProxyDomainsSettingsScreen(
                    summary = summary,
                    lastCheckAtMs = cfDomainLastCheckAtMs,
                    upstreamState = upstreamState,
                    manualCfDomainsText = manualCfDomainsText,
                    invalidManualCfDomains = invalidManualCfDomains,
                    autoUpdateEnabled = autoUpdateEnabled,
                    mirrorEnabled = mirrorEnabled,
                    mirrorUrl = mirrorUrl,
                    mirrorValidationError = mirrorValidationError,
                    isUpdateRunning = isUpdateRunning,
                    isProxyRunning = isProxyRunning,
                    isDiagRunning = isDiagRunning,
                    onManualCfDomainsChange = onManualCfDomainsChange,
                    onAutoUpdateChange = onAutoUpdateChange,
                    onMirrorEnabledChange = onMirrorEnabledChange,
                    onMirrorUrlChange = onMirrorUrlChange,
                    onTestCfDomains = onTestCfDomains,
                    onUpdateUpstream = onUpdateUpstream,
                    onResetCooldown = onResetCooldown,
                    onTestPrimarySource = onTestPrimarySource,
                    onTestMirrorSource = onTestMirrorSource,
                    onOpenDomainList = { onPageChange(CloudflareSettingsPage.DOMAIN_LIST) },
                )
            }
        }
        CloudflareSettingsPage.DOMAIN_LIST -> {
            CloudflareSubpageScaffold(
                titleRes = R.string.cf_domain_list_title,
                onBack = { onPageChange(CloudflareSettingsPage.PROXY_DOMAINS) },
                modifier = modifier,
            ) {
                CfDomainListScreen(rows = cfDomainRows)
            }
        }
    }
}

@Composable
private fun CloudflareSubpageScaffold(
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
private fun CloudflareOverviewScreen(
    isProxyRunning: Boolean,
    workerConfigured: Boolean,
    workerDomainText: String,
    workerPoolEnabled: Boolean,
    workerPoolSelectedWorker: WorkerEndpoint?,
    workerPoolWorkerCount: Int,
    maskDomainsInSettings: Boolean,
    domainsSummary: CfDomainsSummary,
    lastUpdateLabel: String,
    upstreamState: CfDomainUpstreamState,
    isDiagRunning: Boolean,
    onOpenWorkerPool: () -> Unit,
    onOpenProxyDomains: () -> Unit,
    onTestCfDomains: () -> Unit,
    onOpenWorkerHelp: () -> Unit,
    overviewFooter: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workerStatus = if (workerConfigured) {
        stringResource(R.string.cf_worker_status_configured)
    } else {
        stringResource(R.string.cf_worker_status_not_configured)
    }
    val poolStatus = if (workerPoolEnabled) {
        stringResource(R.string.worker_pool_enabled)
    } else {
        stringResource(R.string.cf_worker_pool_status_disabled)
    }
    val selectedWorker = remember(workerPoolEnabled, workerPoolSelectedWorker, workerDomainText) {
        when {
            workerPoolEnabled && workerPoolSelectedWorker != null -> workerPoolSelectedWorker.name
            workerPoolEnabled -> context.getString(R.string.worker_pool_no_selected_worker)
            else -> {
                val normalized = WorkerDomain.normalize(workerDomainText)
                if (normalized.isBlank()) {
                    context.getString(R.string.cf_worker_pool_no_selected)
                } else {
                    WorkerUrlSanitizer.maskForDisplay(normalized, maskDomainsInSettings)
                }
            }
        }
    }
    val manualCount = remember(domainsSummary.manualCount) { domainsSummary.manualCount }
    val proxySourceLabel = remember(upstreamState.domains.size, manualCount) {
        when {
            upstreamState.domains.isNotEmpty() -> context.getString(R.string.cf_proxy_domains_source_upstream)
            manualCount > 0 -> context.getString(R.string.cf_proxy_domains_source_manual)
            else -> context.getString(R.string.cf_proxy_domains_source_builtin)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProxyRunning) {
            SettingsSectionLockedSummary(R.string.settings_proxy_running_banner)
            Spacer(modifier = Modifier.height(8.dp))
        }

        CfSectionCard(titleRes = R.string.cf_summary_title) {
            MetricLine(stringResource(R.string.cf_worker_status), workerStatus)
            MetricLine(stringResource(R.string.cf_worker_pool_status), poolStatus)
            MetricLine(
                stringResource(R.string.cf_proxy_status),
                stringResource(R.string.cf_proxy_domains_count_value, domainsSummary.activeCount),
            )
            MetricLine(stringResource(R.string.cf_last_update), lastUpdateLabel)
        }

        Spacer(modifier = Modifier.height(10.dp))

        CfSectionCard(titleRes = R.string.cf_worker_pool_title) {
            MetricLine(stringResource(R.string.cf_worker_pool_selected), selectedWorker)
            if (workerPoolEnabled) {
                Text(
                    stringResource(R.string.worker_pool_workers_count, workerPoolWorkerCount),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onOpenWorkerPool) {
                Text(stringResource(R.string.cf_worker_pool_open))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        CfSectionCard(titleRes = R.string.cf_proxy_domains_title) {
            MetricLine(
                stringResource(R.string.cf_proxy_domains_active_count),
                domainsSummary.activeCount.toString(),
            )
            MetricLine(
                stringResource(R.string.cf_proxy_domains_cooldown_count),
                domainsSummary.cooldownCount.toString(),
            )
            MetricLine(stringResource(R.string.cf_proxy_domains_source), proxySourceLabel)
            TextButton(onClick = onOpenProxyDomains) {
                Text(stringResource(R.string.cf_proxy_domains_manage))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            stringResource(R.string.settings_quick_actions_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onTestCfDomains,
                enabled = !isProxyRunning && !isDiagRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cf_domains_test))
            }
            OutlinedButton(
                onClick = onOpenWorkerHelp,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cf_worker_help_link))
            }
        }

        overviewFooter()
    }
}

@Composable
private fun CfProxyDomainsSettingsScreen(
    summary: CfDomainsSummary,
    lastCheckAtMs: Long?,
    upstreamState: CfDomainUpstreamState,
    manualCfDomainsText: String,
    invalidManualCfDomains: List<String>,
    autoUpdateEnabled: Boolean,
    mirrorEnabled: Boolean,
    mirrorUrl: String,
    mirrorValidationError: String?,
    isUpdateRunning: Boolean,
    isProxyRunning: Boolean,
    isDiagRunning: Boolean,
    onManualCfDomainsChange: (String) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onMirrorEnabledChange: (Boolean) -> Unit,
    onMirrorUrlChange: (String) -> Unit,
    onTestCfDomains: () -> Unit,
    onUpdateUpstream: () -> Unit,
    onResetCooldown: () -> Unit,
    onTestPrimarySource: () -> Unit,
    onTestMirrorSource: () -> Unit,
    onOpenDomainList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CfDomainsSummaryCard(
            summary = summary,
            lastCheckAtMs = lastCheckAtMs,
            upstreamState = upstreamState,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CfDomainActionsCard(
            isProxyRunning = isProxyRunning,
            isDiagRunning = isDiagRunning,
            isUpdateRunning = isUpdateRunning,
            onTestCfDomains = onTestCfDomains,
            onUpdateUpstream = onUpdateUpstream,
            onResetCooldown = onResetCooldown,
            onOpenDomainList = onOpenDomainList,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CfDomainSourcesCard(
            autoUpdateEnabled = autoUpdateEnabled,
            mirrorEnabled = mirrorEnabled,
            mirrorUrl = mirrorUrl,
            mirrorValidationError = mirrorValidationError,
            isUpdateRunning = isUpdateRunning,
            onAutoUpdateChange = onAutoUpdateChange,
            onMirrorEnabledChange = onMirrorEnabledChange,
            onMirrorUrlChange = onMirrorUrlChange,
            onTestPrimarySource = onTestPrimarySource,
            onTestMirrorSource = onTestMirrorSource,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.cf_domains_manual),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = manualCfDomainsText,
            onValueChange = onManualCfDomainsChange,
            enabled = !isProxyRunning,
            label = { Text(stringResource(R.string.cf_domain_label)) },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
        )
        if (invalidManualCfDomains.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.cf_domains_invalid_entries,
                    invalidManualCfDomains.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CfDomainsSummaryCard(
    summary: CfDomainsSummary,
    lastCheckAtMs: Long?,
    upstreamState: CfDomainUpstreamState,
) {
    val context = LocalContext.current
    val lastCheck = remember(lastCheckAtMs) { formatCfTimestamp(context, lastCheckAtMs) }
    val lastUpdate = remember(upstreamState.lastSuccessfulUpdateAtMs) {
        formatCfTimestamp(context, upstreamState.lastSuccessfulUpdateAtMs)
    }
    val lastSource = upstreamState.lastSuccessfulSource?.let { stringResource(it.labelKey()) }
        ?: stringResource(R.string.cf_domains_none)
    val lastError = upstreamState.lastError ?: stringResource(R.string.cf_domains_none)

    CfSectionCard(titleRes = R.string.cf_domains_title) {
        MetricLine(
            stringResource(R.string.cf_proxy_domains_active_count),
            summary.activeCount.toString(),
        )
        MetricLine(
            stringResource(R.string.cf_proxy_domains_cooldown_count),
            summary.cooldownCount.toString(),
        )
        MetricLine(stringResource(R.string.cf_domains_last_check), lastCheck)
        MetricLine(stringResource(R.string.cf_domains_last_update), lastUpdate)
        MetricLine(stringResource(R.string.cf_update_last_success_source), lastSource)
        MetricLine(stringResource(R.string.cf_domains_last_update_error), lastError)
    }
}

@Composable
private fun CfDomainActionsCard(
    isProxyRunning: Boolean,
    isDiagRunning: Boolean,
    isUpdateRunning: Boolean,
    onTestCfDomains: () -> Unit,
    onUpdateUpstream: () -> Unit,
    onResetCooldown: () -> Unit,
    onOpenDomainList: () -> Unit,
) {
    CfSectionCard(titleRes = R.string.cf_proxy_domains_actions_title) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CfDomainActionButton(
                text = stringResource(R.string.cf_domains_test),
                icon = Icons.Default.Refresh,
                onClick = onTestCfDomains,
                enabled = !isProxyRunning && !isDiagRunning,
            )
            CfDomainActionButton(
                text = if (isUpdateRunning) {
                    stringResource(R.string.cf_domains_update_running)
                } else {
                    stringResource(R.string.cf_domains_update_all)
                },
                icon = Icons.Default.Refresh,
                onClick = onUpdateUpstream,
                enabled = !isUpdateRunning,
                primary = true,
            )
            CfDomainActionButton(
                text = stringResource(R.string.cf_domains_reset_cooldown),
                icon = Icons.Default.Refresh,
                onClick = onResetCooldown,
            )
            CfDomainActionButton(
                text = stringResource(R.string.cf_proxy_domains_open_list),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onOpenDomainList,
            )
        }
    }
}

@Composable
private fun CfDomainSourcesCard(
    autoUpdateEnabled: Boolean,
    mirrorEnabled: Boolean,
    mirrorUrl: String,
    mirrorValidationError: String?,
    isUpdateRunning: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onMirrorEnabledChange: (Boolean) -> Unit,
    onMirrorUrlChange: (String) -> Unit,
    onTestPrimarySource: () -> Unit,
    onTestMirrorSource: () -> Unit,
) {
    val primaryHost = remember { safeUrlForLog(CfDomainUpdateConfig.PRIMARY_URL) }
    CfSectionCard(titleRes = R.string.cf_proxy_domains_sources) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cf_domains_auto_update), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = autoUpdateEnabled, onCheckedChange = onAutoUpdateChange)
        }
        Text(
            "${stringResource(R.string.cf_update_source_primary)}: $primaryHost",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cf_update_mirror_enable), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = mirrorEnabled, onCheckedChange = onMirrorEnabledChange)
        }
        OutlinedTextField(
            value = mirrorUrl,
            onValueChange = onMirrorUrlChange,
            enabled = mirrorEnabled,
            label = { Text(stringResource(R.string.cf_update_mirror_url)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            singleLine = true,
        )
        if (mirrorValidationError != null) {
            Text(
                stringResource(R.string.cf_update_mirror_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CfDomainActionButton(
                text = stringResource(R.string.cf_update_test_primary),
                icon = Icons.Default.Refresh,
                onClick = onTestPrimarySource,
                enabled = !isUpdateRunning,
            )
            CfDomainActionButton(
                text = stringResource(R.string.cf_update_test_mirror),
                icon = Icons.Default.Refresh,
                onClick = onTestMirrorSource,
                enabled = !isUpdateRunning && mirrorEnabled,
            )
        }
    }
}

@Composable
private fun CfDomainActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val buttonModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
    val shape = RoundedCornerShape(12.dp)
    if (primary) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
            CfDomainActionContent(icon = icon, text = text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
        ) {
            CfDomainActionContent(icon = icon, text = text)
        }
    }
}

@Composable
private fun CfDomainActionContent(
    icon: ImageVector,
    text: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CfDomainListScreen(
    rows: List<CfDomainHealth>,
    modifier: Modifier = Modifier,
) {
    var filterOrdinal by rememberSaveable { mutableIntStateOf(CfDomainListFilter.ALL.ordinal) }
    val filter = CfDomainListFilter.entries.getOrElse(filterOrdinal) { CfDomainListFilter.ALL }
    val nowMs = System.currentTimeMillis()
    val filteredRows by remember(rows, filter) {
        derivedStateOf {
            rows.filter { row ->
                val status = row.status(nowMs)
                when (filter) {
                    CfDomainListFilter.ALL -> true
                    CfDomainListFilter.MANUAL -> row.source == CfDomainSource.MANUAL
                    CfDomainListFilter.UPSTREAM -> row.source == CfDomainSource.CACHED_UPSTREAM
                    CfDomainListFilter.BUILTIN -> row.source == CfDomainSource.BUILT_IN
                    CfDomainListFilter.COOLDOWN -> status == CfDomainStatus.COOLDOWN
                    CfDomainListFilter.FAILED -> status == CfDomainStatus.FAILED
                    CfDomainListFilter.UNCHECKED -> status == CfDomainStatus.UNCHECKED
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CfDomainFilterChip(CfDomainListFilter.ALL, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.MANUAL, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.UPSTREAM, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.BUILTIN, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.FAILED, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.COOLDOWN, filter) { filterOrdinal = it.ordinal }
            CfDomainFilterChip(CfDomainListFilter.UNCHECKED, filter) { filterOrdinal = it.ordinal }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (filteredRows.isEmpty()) {
            Text(
                stringResource(R.string.cf_domains_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val listHeight = (filteredRows.size.coerceAtLeast(1) * 56).coerceAtMost(480).dp
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredRows, key = { "${it.source.name}:${it.domain}" }) { row ->
                    CfDomainHealthRow(row = row)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CfDomainFilterChip(
    value: CfDomainListFilter,
    selected: CfDomainListFilter,
    onSelect: (CfDomainListFilter) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = {
            Text(
                stringResource(
                    when (value) {
                        CfDomainListFilter.ALL -> R.string.cf_domain_list_filter_all
                        CfDomainListFilter.MANUAL -> R.string.cf_domain_list_filter_manual
                        CfDomainListFilter.UPSTREAM -> R.string.cf_domain_list_filter_upstream
                        CfDomainListFilter.BUILTIN -> R.string.cf_domain_list_filter_builtin
                        CfDomainListFilter.FAILED -> R.string.cf_domain_list_filter_failed
                        CfDomainListFilter.COOLDOWN -> R.string.cf_domain_list_filter_cooldown
                        CfDomainListFilter.UNCHECKED -> R.string.cf_domain_list_filter_unchecked
                    },
                ),
            )
        },
    )
}

@Composable
private fun CfDomainHealthRow(row: CfDomainHealth) {
    val context = LocalContext.current
    val status = row.status()
    val source = when (row.source) {
        CfDomainSource.MANUAL -> stringResource(R.string.cf_domains_source_manual)
        CfDomainSource.BUILT_IN -> stringResource(R.string.cf_domains_source_builtin)
        CfDomainSource.CACHED_UPSTREAM -> stringResource(R.string.cf_domains_source_cached)
    }
    val lastErrorLabel = stringResource(R.string.cf_domains_last_error)
    val cooldownLabel = stringResource(R.string.cf_domains_cooldown_until)
    val cooldown = row.cooldownUntilMs?.takeIf { it > System.currentTimeMillis() }?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            "${row.domain} · $source · ${cfDomainStatusLabel(context, status)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        val detail = buildList {
            row.lastLatencyMs?.let { add("${it} ms") }
            row.lastFailureReason?.let { add("$lastErrorLabel: $it") }
            cooldown?.let { add("$cooldownLabel: $it") }
        }.joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CfSectionCard(
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
private fun rememberCfDomainsSummary(
    rows: List<CfDomainHealth>,
    upstreamState: CfDomainUpstreamState,
    manualCfDomainsText: String,
): CfDomainsSummary {
    val nowMs = System.currentTimeMillis()
    return remember(rows, upstreamState.domains, manualCfDomainsText) {
        val manualCount = CfManualDomainList.parse(manualCfDomainsText).domains.size
        CfDomainsSummary(
            activeCount = rows.count { it.status(nowMs) != CfDomainStatus.COOLDOWN },
            cooldownCount = rows.count { it.status(nowMs) == CfDomainStatus.COOLDOWN },
            manualCount = manualCount,
            upstreamCount = upstreamState.domains.size,
            builtInCount = CfDomain.builtInDomains.size,
            totalRows = rows.size,
        )
    }
}

private fun formatCfTimestamp(context: android.content.Context, atMs: Long?): String {
    return atMs?.takeIf { it > 0 }?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    } ?: context.getString(R.string.cf_domains_unchecked)
}

private fun cfDomainStatusLabel(context: android.content.Context, status: CfDomainStatus): String {
    return context.getString(
        when (status) {
            CfDomainStatus.OK -> R.string.cf_domains_status_ok
            CfDomainStatus.FAILED -> R.string.cf_domains_status_failed
            CfDomainStatus.COOLDOWN -> R.string.cf_domains_status_cooldown
            CfDomainStatus.UNCHECKED -> R.string.cf_domains_status_unchecked
        },
    )
}
