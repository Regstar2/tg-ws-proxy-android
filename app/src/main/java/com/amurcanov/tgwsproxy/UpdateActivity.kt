package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstalledAppVersion(
    val name: String,
    val code: Long,
)

private sealed interface UpdateScreenState {
    data object Idle : UpdateScreenState
    data object Checking : UpdateScreenState
    data object UpToDate : UpdateScreenState
    data object UnsupportedVersion : UpdateScreenState
    data class Available(val release: GitHubReleaseInfo) : UpdateScreenState
    data class Failed(val kind: UpdateFailureKind) : UpdateScreenState
}

class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UpdateActivityContent(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateActivityContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE) }
    val themeMode = prefs.getString("theme_mode", "System") ?: "System"
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> systemDark
    }
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        useDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.update_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.update_back),
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                UpdateScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val client = remember { GitHubReleasesClient() }
    val installed = remember { readInstalledVersion(appContext) }
    var state by remember { mutableStateOf<UpdateScreenState>(UpdateScreenState.Idle) }

    suspend fun checkForUpdates() {
        if (state == UpdateScreenState.Checking) return
        state = UpdateScreenState.Checking
        state = try {
            val decision = withContext(Dispatchers.IO) {
                AppUpdateSelector.select(installed.name, client.fetchReleases())
            }
            when (decision) {
                is UpdateDecision.Available -> UpdateScreenState.Available(decision.release)
                UpdateDecision.UpToDate -> UpdateScreenState.UpToDate
                UpdateDecision.UnsupportedCurrentVersion -> UpdateScreenState.UnsupportedVersion
            }
        } catch (error: UpdateCheckException) {
            UpdateScreenState.Failed(error.kind)
        } catch (_: Exception) {
            UpdateScreenState.Failed(UpdateFailureKind.NETWORK)
        }
    }

    LaunchedEffect(Unit) {
        // Automatic check happens only after this screen is opened and never blocks app/proxy startup.
        checkForUpdates()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = stringResource(R.string.update_current_version_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.update_current_version_value,
                        installed.name,
                        installed.code,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                when (val current = state) {
                    UpdateScreenState.Idle -> {
                        Text(stringResource(R.string.update_status_idle))
                    }
                    UpdateScreenState.Checking -> {
                        Text(
                            text = stringResource(R.string.update_status_checking),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    UpdateScreenState.UpToDate -> {
                        Text(
                            text = stringResource(R.string.update_status_up_to_date),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    UpdateScreenState.UnsupportedVersion -> {
                        Text(
                            text = stringResource(R.string.update_status_unsupported_version),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is UpdateScreenState.Failed -> {
                        Text(
                            text = stringResource(current.kind.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.update_failure_safe_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    is UpdateScreenState.Available -> {
                        val release = current.release
                        val fullNotes = remember(release.notes) {
                            ReleaseNotesTextFormatter.toPlainText(release.notes)
                        }
                        val preview = remember(release.notes) {
                            ReleaseNotesTextFormatter.preview(release.notes)
                        }
                        var notesExpanded by remember(release.tagName) { mutableStateOf(false) }

                        Text(
                            text = stringResource(R.string.update_available_title, release.tagName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (release.prerelease) {
                            Text(
                                text = stringResource(R.string.update_prerelease_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (release.title.isNotBlank() && release.title != release.tagName) {
                            Text(
                                text = release.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        Button(
                            onClick = {
                                val url = AppUpdateSelector.officialReleaseUrl(release.tagName)
                                if (url == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.update_open_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                                        )
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.update_open_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.update_open_release))
                        }

                        Text(
                            text = stringResource(R.string.update_release_notes_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = when {
                                fullNotes.isBlank() -> stringResource(R.string.update_release_notes_empty)
                                notesExpanded -> fullNotes
                                else -> preview.text
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (preview.truncated) {
                            TextButton(
                                onClick = { notesExpanded = !notesExpanded },
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text(
                                    if (notesExpanded) {
                                        stringResource(R.string.update_hide_release_notes)
                                    } else {
                                        stringResource(R.string.update_show_release_notes)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { scope.launch { checkForUpdates() } },
            enabled = state != UpdateScreenState.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state == UpdateScreenState.Checking) {
                    stringResource(R.string.update_status_checking)
                } else {
                    stringResource(R.string.update_check_now)
                },
            )
        }

        Text(
            text = stringResource(R.string.update_source_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.update_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun readInstalledVersion(context: Context): InstalledAppVersion {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        InstalledAppVersion(
            name = info.versionName ?: "unknown",
            code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
        )
    }.getOrDefault(InstalledAppVersion("unknown", 0L))
}

private fun UpdateFailureKind.messageResource(): Int = when (this) {
    UpdateFailureKind.NETWORK -> R.string.update_error_network
    UpdateFailureKind.TIMEOUT -> R.string.update_error_timeout
    UpdateFailureKind.API -> R.string.update_error_api
    UpdateFailureKind.MALFORMED -> R.string.update_error_malformed
}
