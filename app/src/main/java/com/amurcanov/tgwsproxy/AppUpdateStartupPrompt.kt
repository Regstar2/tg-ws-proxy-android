package com.amurcanov.tgwsproxy

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/** Async startup check. This dialog never blocks app/proxy initialization. */
@Composable
internal fun AppUpdateStartupPrompt(enabledForLaunch: Boolean) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val preferences = remember { AppUpdatePreferences(appContext) }
    var release by remember { mutableStateOf<GitHubReleaseInfo?>(null) }

    LaunchedEffect(enabledForLaunch) {
        if (!enabledForLaunch) return@LaunchedEffect
        // The release check also refreshes the shared cache when prompts are
        // disabled; the preference controls the dialog, not manual updates.
        val candidate = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val installedVersion = info.versionName.orEmpty()
            val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            (AppUpdateCheckRepository.check(
                appContext, installedVersion, installedCode,
            ) as? UpdateDecision.Available)?.release
        } catch (error: Exception) {
            Log.w("TgWsProxyUpdate", "Startup release check failed: ${error.javaClass.simpleName}")
            null
        }
        if (candidate != null &&
            AppUpdateAssetSelector.select(candidate) != null &&
            preferences.shouldShow()
        ) release = candidate
    }

    val available = release ?: return
    val size = AppUpdateAssetSelector.select(available)?.apk?.size ?: return

    AlertDialog(
        onDismissRequest = {
            preferences.remindLater()
            release = null
        },
        title = { Text(stringResource(R.string.update_prompt_title, available.tagName)) },
        text = {
            Text(stringResource(R.string.update_prompt_subtitle, size / 1048576.0))
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        preferences.remindLater()
                        release = null
                    }) { Text(stringResource(R.string.update_later)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        release = null
                        context.startActivity(
                            Intent(context, UpdateActivity::class.java)
                                .putExtra(UpdateActivity.EXTRA_START_DOWNLOAD, true),
                        )
                    }) { Text(stringResource(R.string.update_download_install)) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        preferences.enabled = false
                        release = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(stringResource(R.string.update_disable_reminders)) }
            }
        },
        icon = null,
    )
}
