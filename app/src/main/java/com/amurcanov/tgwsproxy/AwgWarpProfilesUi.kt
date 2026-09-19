package com.amurcanov.tgwsproxy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun AwgWarpOverviewCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AwgWarpProfileManager(context) }
    val profiles = remember { manager.listProfiles() }
    val selected = profiles.firstOrNull { it.selected }
    val state = selected?.metadata?.health ?: AwgWarpProfileHealth.NOT_CHECKED

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.awg_warp_profiles_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            AwgMetricLine(
                stringResource(R.string.awg_warp_selected_profile),
                selected?.metadata?.name ?: stringResource(R.string.awg_warp_not_selected),
            )
            AwgMetricLine(stringResource(R.string.awg_warp_profiles_count), profiles.size.toString())
            AwgMetricLine(
                stringResource(R.string.awg_warp_state),
                if (selected == null) stringResource(R.string.awg_warp_not_configured) else awgHealthLabel(state),
            )
            TextButton(onClick = onOpen) {
                Text(
                    if (profiles.isEmpty()) stringResource(R.string.awg_warp_setup)
                    else stringResource(R.string.awg_warp_manage_profiles),
                )
            }
        }
    }
}

@Composable
fun AwgWarpProfilesPage(
    isProxyRunning: Boolean,
    onCreate: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AwgWarpProfileManager(context) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(manager.listProfiles()) }
    var importBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        profiles = manager.listProfiles()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !isProxyRunning && !importBusy) {
            importBusy = true
            scope.launch {
                val result = manager.importProfile(
                    uri = uri,
                    name = context.getString(R.string.awg_warp_imported_profile_name),
                )
                importBusy = false
                message = if (result.isSuccess) {
                    context.getString(R.string.awg_warp_import_success)
                } else {
                    context.getString(
                        R.string.awg_warp_import_failed,
                        supportSafeError(result.exceptionOrNull()),
                    )
                }
                refresh()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProxyRunning) {
            Text(
                stringResource(R.string.awg_warp_action_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        val selected = profiles.firstOrNull { it.selected }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.awg_warp_selected_profile),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                AwgMetricLine(
                    stringResource(R.string.awg_warp_selected_profile),
                    selected?.metadata?.name ?: stringResource(R.string.awg_warp_not_selected),
                )
                AwgMetricLine(
                    stringResource(R.string.awg_warp_state),
                    selected?.let { awgHealthLabel(it.metadata.health) }
                        ?: stringResource(R.string.awg_warp_not_configured),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        WarpProvisioningWorkersCard(
            isProxyRunning = isProxyRunning,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onCreate,
            enabled = !isProxyRunning,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.awg_warp_create_profile))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !isProxyRunning && !importBusy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (importBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(stringResource(R.string.awg_warp_import_profile))
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            stringResource(R.string.awg_warp_saved_profiles),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
        if (profiles.isEmpty()) {
            Text(
                stringResource(R.string.awg_warp_no_profiles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            profiles.forEach { profile ->
                AwgProfileRow(profile = profile, onClick = { onOpenDetails(profile.metadata.id) })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun AwgWarpCreateProfilePage(
    isProxyRunning: Boolean,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AwgWarpProfileManager(context) }
    val scope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf(context.getString(R.string.awg_warp_default_profile_name)) }
    var stage by remember { mutableStateOf<WarpProvisioningStage?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    fun start() {
        if (isProxyRunning || job?.isActive == true) return
        error = null
        createdId = null
        job = scope.launch {
            try {
                val result = manager.provisionProfile(profileName) { stage = it }
                if (result.isSuccess) {
                    createdId = result.getOrThrow().id
                    stage = null
                } else {
                    error = context.getString(
                        R.string.awg_warp_create_failed,
                        supportSafeError(result.exceptionOrNull()),
                    )
                    stage = null
                }
            } catch (_: CancellationException) {
                stage = null
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.awg_warp_provider_consumer),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.awg_warp_provider_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it.take(64) },
            label = { Text(stringResource(R.string.awg_warp_profile_name)) },
            enabled = !isProxyRunning && job?.isActive != true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.awg_warp_create_safety_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        if (stage == null && createdId == null) {
            Button(
                onClick = ::start,
                enabled = !isProxyRunning && profileName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.awg_warp_create_automatically))
            }
        }

        if (stage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    WarpProvisioningStage.entries.forEach { item ->
                        val currentIndex = WarpProvisioningStage.entries.indexOf(stage)
                        val itemIndex = WarpProvisioningStage.entries.indexOf(item)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    itemIndex < currentIndex -> "✓"
                                    item == stage -> "•"
                                    else -> "○"
                                },
                                modifier = Modifier.padding(end = 10.dp),
                                color = if (itemIndex <= currentIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                awgStageLabel(item),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item == stage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(
                        onClick = { job?.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.awg_warp_cancel))
                    }
                }
            }
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        createdId?.let { id ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.awg_warp_profile_created),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FilledTonalButton(
                        onClick = {
                            manager.selectProfile(id)
                            onCreated(id)
                        },
                        enabled = !isProxyRunning,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(stringResource(R.string.awg_warp_use_profile))
                    }
                    TextButton(
                        onClick = { onCreated(id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.awg_warp_open_details))
                    }
                }
            }
        }
    }
}

@Composable
fun AwgWarpProfileDetailsPage(
    profileId: String,
    isProxyRunning: Boolean,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AwgWarpProfileManager(context) }
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(manager.listProfiles().firstOrNull { it.metadata.id == profileId }) }
    var details by remember { mutableStateOf(manager.loadDetails(profileId).getOrNull()) }
    var revealSecret by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        summary = manager.listProfiles().firstOrNull { it.metadata.id == profileId }
        details = manager.loadDetails(profileId).getOrNull()
    }

    val profile = summary
    val config = details
    if (profile == null || config == null) {
        Text(
            stringResource(R.string.awg_warp_status_config_error),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.awg_warp_delete_confirm_title)) },
            text = { Text(stringResource(R.string.awg_warp_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val result = manager.deleteProfile(profileId)
                    if (result.isSuccess) onDeleted()
                    else message = context.getString(
                        R.string.awg_warp_delete_failed,
                        supportSafeError(result.exceptionOrNull()),
                    )
                }) {
                    Text(stringResource(R.string.awg_warp_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.awg_warp_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProxyRunning) {
            Text(
                stringResource(R.string.awg_warp_action_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profile.metadata.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile.selected) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.awg_warp_selected_badge),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                AwgMetricLine(stringResource(R.string.awg_warp_source), awgSourceLabel(profile.metadata.source))
                AwgMetricLine(stringResource(R.string.awg_warp_state), awgHealthLabel(profile.metadata.health))
                AwgMetricLine(
                    stringResource(R.string.awg_warp_created),
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(profile.metadata.createdAtMs)),
                )
                profile.metadata.lastCheckedAtMs?.let {
                    AwgMetricLine(
                        stringResource(R.string.awg_warp_last_checked_never),
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val result = manager.selectProfile(profileId)
                        message = result.exceptionOrNull()?.let {
                            context.getString(R.string.awg_warp_select_failed, supportSafeError(it))
                        }
                        refresh()
                    },
                    enabled = !isProxyRunning && !checking && !profile.selected,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        if (profile.selected) stringResource(R.string.awg_warp_selected_badge)
                        else stringResource(R.string.awg_warp_use_profile),
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = {
                        checking = true
                        message = null
                        scope.launch {
                            val result = manager.checkProfile(profileId)
                            checking = false
                            message = result.exceptionOrNull()?.let {
                                context.getString(R.string.awg_warp_check_failed, supportSafeError(it))
                            }
                            refresh()
                        }
                    },
                    enabled = !isProxyRunning && !checking,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.awg_warp_check_profile))
                }
                if (profile.metadata.source == AwgWarpProfileSource.IMPORTED) {
                    AwgWarpImportedConfigEditor(
                        profileId = profileId,
                        isProxyRunning = isProxyRunning,
                        onSaved = { refresh() },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        AwgDetailsSection(stringResource(R.string.awg_warp_interface_title)) {
            val ipv4 = config.addresses.firstOrNull { !it.contains(':') }
            val ipv6 = config.addresses.firstOrNull { it.contains(':') }
            AwgMetricLine(stringResource(R.string.awg_warp_address_ipv4), ipv4 ?: stringResource(R.string.awg_warp_value_unset))
            AwgMetricLine(stringResource(R.string.awg_warp_address_ipv6), ipv6 ?: stringResource(R.string.awg_warp_value_unset))
            AwgMetricLine(stringResource(R.string.awg_warp_mtu), config.mtu.toString())
            profile.metadata.localPublicKey?.let {
                AwgMetricLine(stringResource(R.string.awg_warp_public_key), it)
            }
            Text(
                stringResource(R.string.awg_warp_awg_parameters),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5")
                .forEach { key -> AwgMetricLine(key, config.deviceOptions[key] ?: stringResource(R.string.awg_warp_value_unset)) }
        }

        AwgDetailsSection(stringResource(R.string.awg_warp_peer_title)) {
            AwgMetricLine(stringResource(R.string.awg_warp_endpoint), config.peer.endpoint)
            AwgMetricLine(stringResource(R.string.awg_warp_public_key), config.peer.publicKey)
            AwgMetricLine(stringResource(R.string.awg_warp_allowed_ips), config.peer.allowedIps.joinToString(", "))
            AwgMetricLine(
                stringResource(R.string.awg_warp_keepalive),
                config.peer.persistentKeepalive?.toString() ?: stringResource(R.string.awg_warp_value_unset),
            )
        }

        AwgDetailsSection(stringResource(R.string.awg_warp_secret_title)) {
            Text(
                stringResource(R.string.awg_warp_private_key),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (revealSecret) config.privateKey else stringResource(R.string.awg_warp_secret_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { revealSecret = !revealSecret }) {
                    Text(
                        if (revealSecret) stringResource(R.string.awg_warp_hide_secret)
                        else stringResource(R.string.awg_warp_show_secret),
                    )
                }
            }
            Text(
                stringResource(R.string.awg_warp_secret_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        TextButton(
            onClick = { showDeleteConfirm = true },
            enabled = !isProxyRunning && !checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.awg_warp_delete_profile),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun WarpProvisioningWorkersCard(
    isProxyRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { WarpProvisioningBootstrapSettingsRepository(context) }
    val healthChecker = remember { WarpProvisioningWorkerHealthChecker() }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(repository.load()) }
    var newWorkerUrl by remember { mutableStateOf("") }
    var checkingWorkerId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        settings = repository.load()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.warp_bootstrap_settings_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.warp_bootstrap_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.warp_bootstrap_builtin_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.warp_bootstrap_builtin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.useBuiltInWorkers,
                    onCheckedChange = { enabled ->
                        repository.setUseBuiltInWorkers(enabled)
                        refresh()
                    },
                    enabled = !isProxyRunning,
                )
            }

            Text(
                stringResource(
                    R.string.warp_bootstrap_custom_workers_count,
                    settings.customWorkers.size,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            OutlinedTextField(
                value = newWorkerUrl,
                onValueChange = { newWorkerUrl = it.take(512) },
                label = { Text(stringResource(R.string.warp_bootstrap_worker_url)) },
                placeholder = { Text(stringResource(R.string.warp_bootstrap_worker_url_hint)) },
                enabled = !isProxyRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = {
                    message = runCatching {
                        repository.addCustomWorker(newWorkerUrl)
                    }.fold(
                        onSuccess = {
                            newWorkerUrl = ""
                            refresh()
                            context.getString(R.string.warp_bootstrap_worker_added)
                        },
                        onFailure = {
                            context.getString(
                                R.string.warp_bootstrap_worker_action_failed,
                                supportSafeError(it),
                            )
                        },
                    )
                },
                enabled = !isProxyRunning && newWorkerUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.warp_bootstrap_add_worker))
            }

            settings.customWorkers.forEach { worker ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            worker.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            provisioningWorkerHealthLabel(worker.lastHealthStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        worker.lastCheckedAtMs?.let { checkedAt ->
                            Text(
                                stringResource(
                                    R.string.warp_bootstrap_worker_last_checked,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(checkedAt)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        worker.lastErrorCode?.let { code ->
                            Text(
                                stringResource(R.string.warp_bootstrap_worker_last_error, code),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = worker.enabled,
                                onCheckedChange = { enabled ->
                                    repository.setCustomWorkerEnabled(worker.id, enabled)
                                    refresh()
                                },
                                enabled = !isProxyRunning,
                            )
                            Text(
                                if (worker.enabled) {
                                    stringResource(R.string.warp_bootstrap_worker_enabled)
                                } else {
                                    stringResource(R.string.warp_bootstrap_worker_disabled)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 6.dp).weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    checkingWorkerId = worker.id
                                    message = null
                                    scope.launch {
                                        val result = healthChecker.check(worker.url)
                                        repository.updateHealth(worker.id, result)
                                        checkingWorkerId = null
                                        message = if (result.ok) {
                                            context.getString(R.string.warp_bootstrap_worker_check_ok)
                                        } else {
                                            context.getString(
                                                R.string.warp_bootstrap_worker_action_failed,
                                                result.errorCode ?: "health_failed",
                                            )
                                        }
                                        refresh()
                                    }
                                },
                                enabled = checkingWorkerId == null,
                            ) {
                                Text(
                                    if (checkingWorkerId == worker.id) {
                                        stringResource(R.string.warp_bootstrap_worker_checking)
                                    } else {
                                        stringResource(R.string.warp_bootstrap_worker_check)
                                    },
                                )
                            }
                            TextButton(
                                onClick = {
                                    repository.deleteCustomWorker(worker.id)
                                    refresh()
                                },
                                enabled = !isProxyRunning && checkingWorkerId != worker.id,
                            ) {
                                Text(stringResource(R.string.warp_bootstrap_worker_delete))
                            }
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun provisioningWorkerHealthLabel(status: ProvisioningWorkerHealthStatus): String = when (status) {
    ProvisioningWorkerHealthStatus.UNCHECKED -> stringResource(R.string.warp_bootstrap_worker_status_unchecked)
    ProvisioningWorkerHealthStatus.HEALTHY -> stringResource(R.string.warp_bootstrap_worker_status_healthy)
    ProvisioningWorkerHealthStatus.FAILED -> stringResource(R.string.warp_bootstrap_worker_status_failed)
}

@Composable
private fun AwgProfileRow(
    profile: AwgWarpProfileSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.selected) 0.7f else 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profile.metadata.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile.selected) {
                        Spacer(modifier = Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.awg_warp_selected_badge),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                Text(
                    awgSourceLabel(profile.metadata.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    awgHealthLabel(profile.metadata.health),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile.endpoint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AwgDetailsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun AwgMetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun awgSourceLabel(source: AwgWarpProfileSource): String = when (source) {
    AwgWarpProfileSource.CONSUMER_WARP -> stringResource(R.string.awg_warp_source_consumer)
    AwgWarpProfileSource.IMPORTED -> stringResource(R.string.awg_warp_source_imported)
}

@Composable
private fun awgHealthLabel(health: AwgWarpProfileHealth): String = when (health) {
    AwgWarpProfileHealth.NOT_CHECKED -> stringResource(R.string.awg_warp_status_not_checked)
    AwgWarpProfileHealth.WORKING -> stringResource(R.string.awg_warp_status_working)
    AwgWarpProfileHealth.CONFIG_ERROR -> stringResource(R.string.awg_warp_status_config_error)
    AwgWarpProfileHealth.NO_HANDSHAKE -> stringResource(R.string.awg_warp_status_no_handshake)
    AwgWarpProfileHealth.NETWORK_ERROR -> stringResource(R.string.awg_warp_status_network_error)
}

@Composable
private fun awgStageLabel(stage: WarpProvisioningStage): String = when (stage) {
    WarpProvisioningStage.PREPARING_KEYS -> stringResource(R.string.awg_warp_stage_preparing_keys)
    WarpProvisioningStage.REGISTERING_WARP -> stringResource(R.string.awg_warp_stage_registering)
    WarpProvisioningStage.FETCHING_PARAMETERS -> stringResource(R.string.awg_warp_stage_fetching)
    WarpProvisioningStage.BUILDING_PROFILE -> stringResource(R.string.awg_warp_stage_building)
    WarpProvisioningStage.VALIDATING_CONFIG -> stringResource(R.string.awg_warp_stage_validating)
    WarpProvisioningStage.CHECKING_CONNECTION -> stringResource(R.string.awg_warp_stage_connection)
    WarpProvisioningStage.SAVING -> stringResource(R.string.awg_warp_stage_saving)
}

private fun supportSafeError(throwable: Throwable?): String {
    val provisioning = generateSequence(throwable) { it.cause }
        .take(8)
        .filterIsInstance<WarpProvisioningException>()
        .firstOrNull()
    if (provisioning != null) return provisioning.code

    val safeMessage = throwable?.message?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) }
    if (safeMessage != null) return safeMessage
    val safeClass = throwable?.javaClass?.simpleName?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) }
    return safeClass?.let { "unexpected_$it" } ?: "unknown_error"
}
