package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class InstalledVersion(val name: String, val code: Long)

private fun UpdateDecision.asScreenState(): UpdateState = when (this) {
    is UpdateDecision.Available -> UpdateState.Available(release)
    UpdateDecision.UpToDate -> UpdateState.Current
    UpdateDecision.UnsupportedCurrentVersion -> UpdateState.Unsupported
}

/**
 * Permission decisions are separate from installation. Returning from Android
 * settings never triggers another settings launch without a user action.
 */
internal enum class UpdateInstallPermissionAction {
    OPEN_SETTINGS,
    OPEN_INSTALLER,
    WAIT_FOR_USER,
}

internal object UpdateInstallPermissionPolicy {
    fun onInstallConfirmed(granted: Boolean): UpdateInstallPermissionAction =
        if (granted) UpdateInstallPermissionAction.OPEN_INSTALLER
        else UpdateInstallPermissionAction.OPEN_SETTINGS

    fun onSettingsReturned(granted: Boolean): UpdateInstallPermissionAction =
        if (granted) UpdateInstallPermissionAction.OPEN_INSTALLER
        else UpdateInstallPermissionAction.WAIT_FOR_USER
}

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object Current : UpdateState
    data object Unsupported : UpdateState
    data class Available(val release: GitHubReleaseInfo) : UpdateState
    data class Downloading(val release: GitHubReleaseInfo, val received: Long, val total: Long) : UpdateState
    data class Verifying(val release: GitHubReleaseInfo) : UpdateState
    data class Ready(val release: GitHubReleaseInfo, val file: File) : UpdateState
    data class PermissionRequired(val release: GitHubReleaseInfo, val file: File) : UpdateState
    data class InstallerOpened(val release: GitHubReleaseInfo) : UpdateState
    data class Cancelled(val release: GitHubReleaseInfo) : UpdateState
    data class Failed(val release: GitHubReleaseInfo?, val message: Int) : UpdateState
}

class UpdateActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_DOWNLOAD = "start_verified_update"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startDownload = intent.getBooleanExtra(EXTRA_START_DOWNLOAD, false)
        setContent { UpdateActivityContent(onBack = { finish() }, autoDownload = startDownload) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateActivityContent(onBack: () -> Unit, autoDownload: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE) }
    val themeMode = prefs.getString("theme_mode", "System")
    val dark = when (themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.update_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.update_back))
                            }
                        },
                    )
                },
            ) { padding ->
                UpdateScreen(Modifier.fillMaxSize().padding(padding), autoDownload)
            }
        }
    }
}

@Composable
private fun UpdateScreen(modifier: Modifier, autoDownload: Boolean) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val installer = remember { AppUpdateInstaller(appContext) }
    val preferences = remember { AppUpdatePreferences(appContext) }
    val installed = remember { readInstalledVersion(appContext) }
    var state by remember {
        mutableStateOf<UpdateState>(
            AppUpdateCheckRepository.cached(appContext, installed.name, installed.code)
                ?.asScreenState() ?: UpdateState.Checking,
        )
    }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(preferences.enabled) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var askInstall by remember { mutableStateOf(false) }

    fun startDownload(release: GitHubReleaseInfo) {
        if (runningJob?.isActive == true) return
        if (AppUpdateAssetSelector.select(release) == null) {
            state = UpdateState.Failed(release, R.string.update_asset_unavailable)
            return
        }
        runningJob = scope.launch {
            state = UpdateState.Downloading(release, 0L, 1L)
            try {
                val verified = installer.download(release) { received, total ->
                    withContext(Dispatchers.Main) {
                        state = if (received == total) {
                            UpdateState.Verifying(release)
                        } else {
                            UpdateState.Downloading(release, received, total)
                        }
                    }
                }
                state = UpdateState.Ready(release, verified)
            } catch (_: CancellationException) {
                state = UpdateState.Cancelled(release)
            } catch (_: Exception) {
                state = UpdateState.Failed(release, R.string.update_download_failed)
            } finally {
                runningJob = null
            }
        }
    }

    fun checkUpdates(
        shouldStartDownload: Boolean = false,
        force: Boolean = false,
    ) {
        if (runningJob?.isActive == true) return
        val cached = if (force) null else {
            AppUpdateCheckRepository.cached(appContext, installed.name, installed.code)
        }
        if (cached != null) {
            state = cached.asScreenState()
            if (shouldStartDownload && cached is UpdateDecision.Available) {
                startDownload(cached.release)
            }
            return
        }
        runningJob = scope.launch {
            state = UpdateState.Checking
            notesExpanded = false
            var forwardedToDownload = false
            try {
                val result = AppUpdateCheckRepository.check(
                    appContext, installed.name, installed.code, force,
                )
                state = result.asScreenState()
                if (shouldStartDownload && result is UpdateDecision.Available) {
                    runningJob = null
                    startDownload(result.release)
                    forwardedToDownload = true
                }
            } catch (_: Exception) {
                state = UpdateState.Failed(null, R.string.update_error_network)
            } finally {
                if (!forwardedToDownload) runningJob = null
            }
        }
    }

    fun startSystemInstaller(release: GitHubReleaseInfo, file: File) {
        // Settings can be revoked at any time. Do not invoke the installer if
        // the source permission is still missing.
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.PermissionRequired(release, file)
            return
        }
        try {
            context.startActivity(installer.installIntent(file))
            state = UpdateState.InstallerOpened(release)
        } catch (_: Exception) {
            state = UpdateState.Failed(release, R.string.update_installer_failed)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val previous = state as? UpdateState.PermissionRequired
        if (previous != null) {
            when (UpdateInstallPermissionPolicy.onSettingsReturned(
                appContext.packageManager.canRequestPackageInstalls(),
            )) {
                UpdateInstallPermissionAction.OPEN_INSTALLER ->
                    startSystemInstaller(previous.release, previous.file)
                UpdateInstallPermissionAction.WAIT_FOR_USER -> Unit
                UpdateInstallPermissionAction.OPEN_SETTINGS -> Unit
            }
        }
    }

    fun openInstaller(release: GitHubReleaseInfo, file: File) {
        when (UpdateInstallPermissionPolicy.onInstallConfirmed(
            appContext.packageManager.canRequestPackageInstalls(),
        )) {
            UpdateInstallPermissionAction.OPEN_INSTALLER -> startSystemInstaller(release, file)
            UpdateInstallPermissionAction.OPEN_SETTINGS -> {
                // Reuse the verified APK. Ask for permission only after explicit
                // installation confirmation, never on launch or during download.
                state = UpdateState.PermissionRequired(release, file)
                try {
                    permissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                } catch (_: Exception) {
                    state = UpdateState.Failed(release, R.string.update_permission_settings_unavailable)
                }
            }
            UpdateInstallPermissionAction.WAIT_FOR_USER -> Unit
        }
    }

    LaunchedEffect(Unit) { checkUpdates(autoDownload) }
    DisposableEffect(Unit) {
        onDispose { runningJob?.cancel() }
    }

    val sourceRelease = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Verifying -> current.release
        is UpdateState.Ready -> current.release
        is UpdateState.PermissionRequired -> current.release
        is UpdateState.InstallerOpened -> current.release
        is UpdateState.Cancelled -> current.release
        is UpdateState.Failed -> current.release
        else -> null
    }
    val actionLabel = when (state) {
        UpdateState.Current, UpdateState.Unsupported -> R.string.update_check_now
        is UpdateState.Available -> if (AppUpdateAssetSelector.select(sourceRelease!!) != null) {
            R.string.update_download_install
        } else R.string.update_open_release
        is UpdateState.Downloading -> R.string.update_cancel_download
        is UpdateState.Verifying -> R.string.update_preparing
        is UpdateState.Ready -> R.string.update_install_now
        is UpdateState.InstallerOpened -> R.string.update_check_now
        is UpdateState.PermissionRequired -> R.string.update_permission_action
        is UpdateState.Cancelled -> R.string.update_download_install
        is UpdateState.Failed -> when {
            sourceRelease == null -> R.string.update_check_now
            AppUpdateAssetSelector.select(sourceRelease) == null -> R.string.update_open_release
            else -> R.string.update_retry
        }
        UpdateState.Checking -> R.string.update_status_checking
    }
    val buttonEnabled = state !is UpdateState.Checking && state !is UpdateState.Verifying

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (sourceRelease != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_installed_short), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(installed.name, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.update_code_short, installed.code),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    val statusLabel = when (state) {
                        UpdateState.Checking -> R.string.update_status_checking
                        UpdateState.Current -> R.string.update_status_current_short
                        UpdateState.Unsupported -> R.string.update_status_unsupported_version
                        is UpdateState.Available -> R.string.update_available_short
                        is UpdateState.Downloading -> R.string.update_downloading
                        is UpdateState.Verifying -> R.string.update_preparing
                        is UpdateState.Ready -> R.string.update_ready
                        is UpdateState.PermissionRequired -> R.string.update_permission_needed
                        is UpdateState.InstallerOpened -> R.string.update_installer_opened
                        is UpdateState.Cancelled -> R.string.update_cancelled
                        is UpdateState.Failed -> R.string.update_failed_short
                    }
                    val color = when (state) {
                        UpdateState.Current, is UpdateState.Ready -> MaterialTheme.colorScheme.tertiary
                        is UpdateState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(stringResource(statusLabel), style = MaterialTheme.typography.labelLarge, color = color)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sourceRelease?.tagName ?: installed.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (sourceRelease != null) {
                                val size = AppUpdateAssetSelector.select(sourceRelease)?.apk?.size
                                if (size != null) {
                                    Text(
                                        stringResource(R.string.update_size_abi, size / 1048576.0),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { openOfficialRelease(context, sourceRelease?.tagName) },
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.update_open_release))
                        }
                    }
                    when (val current = state) {
                        is UpdateState.Available -> {
                            Divider(modifier = Modifier.padding(top = 24.dp))
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { notesExpanded = !notesExpanded },
                            ) {
                                Text(stringResource(R.string.update_release_notes_title), modifier = Modifier.weight(1f))
                                Icon(
                                    if (notesExpanded) Icons.Default.KeyboardArrowDown
                                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                            if (notesExpanded) {
                                val notes = remember(current.release.notes) {
                                    ReleaseNotesTextFormatter.toPlainText(current.release.notes)
                                }
                                val displayedNotes = if (notes.isBlank()) {
                                    stringResource(R.string.update_release_notes_empty)
                                } else notes
                                Text(
                                    displayedNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (AppUpdateAssetSelector.select(current.release) == null) {
                                Text(stringResource(R.string.update_asset_unavailable),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        is UpdateState.Downloading -> {
                            val fraction = (current.received.toFloat() / current.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = fraction,
                                modifier = Modifier.fillMaxWidth().padding(top = 22.dp)
                            )
                            Text(
                                stringResource(R.string.update_progress,
                                    current.received / 1048576.0, current.total / 1048576.0,
                                    (fraction * 100).toInt()),
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is UpdateState.Verifying, UpdateState.Checking -> {
                            Row(
                                modifier = Modifier.padding(top = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(if (current is UpdateState.Verifying)
                                    R.string.update_preparing else R.string.update_status_checking))
                            }
                        }
                        is UpdateState.Failed -> Text(
                            stringResource(current.message),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        UpdateState.Current -> Text(
                            stringResource(R.string.update_status_up_to_date),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        is UpdateState.PermissionRequired -> Text(
                            stringResource(R.string.update_permission_explanation),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        is UpdateState.Ready -> Text(
                            stringResource(R.string.update_ready_description),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        is UpdateState.InstallerOpened -> Text(
                            stringResource(R.string.update_install_confirm_android),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        else -> Unit
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_reminders), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.update_reminders_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = reminders,
                    onCheckedChange = {
                        reminders = it
                        preferences.enabled = it
                    },
                )
            }
        }
        Divider()
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = {
                    when (val current = state) {
                        UpdateState.Current, UpdateState.Unsupported -> checkUpdates(force = true)
                        is UpdateState.Available -> {
                            if (AppUpdateAssetSelector.select(current.release) != null) {
                                startDownload(current.release)
                            } else openOfficialRelease(context, current.release.tagName)
                        }
                        is UpdateState.Downloading -> runningJob?.cancel()
                        is UpdateState.Ready -> askInstall = true
                        is UpdateState.InstallerOpened -> checkUpdates(force = true)
                        is UpdateState.PermissionRequired ->
                            openInstaller(current.release, current.file)
                        is UpdateState.Cancelled -> startDownload(current.release)
                        is UpdateState.Failed -> {
                            if (current.release == null) checkUpdates(force = true)
                            else if (AppUpdateAssetSelector.select(current.release) == null) {
                                openOfficialRelease(context, current.release.tagName)
                            } else startDownload(current.release)
                        }
                        else -> Unit
                    }
                },
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) {
                if (state is UpdateState.Available) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                }
                Text(stringResource(actionLabel))
            }
        }
    }

    val ready = state as? UpdateState.Ready
    if (askInstall && ready != null) {
        AlertDialog(
            onDismissRequest = { askInstall = false },
            title = { Text(stringResource(R.string.update_install_title)) },
            text = { Text(stringResource(R.string.update_install_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    askInstall = false
                    openInstaller(ready.release, ready.file)
                }) { Text(stringResource(R.string.update_install_now)) }
            },
            dismissButton = {
                TextButton(onClick = { askInstall = false }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}

private fun openOfficialRelease(context: Context, tag: String?) {
    val url = tag?.let(AppUpdateSelector::officialReleaseUrl)
        ?: "https://github.com/Regstar2/tg-ws-proxy-android/releases"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Suppress("DEPRECATION")
private fun readInstalledVersion(context: Context): InstalledVersion {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return InstalledVersion(
        name = info.versionName ?: "",
        code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong(),
    )
}
