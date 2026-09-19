package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun AwgWarpProfileEditor(
    profileId: String,
    isProxyRunning: Boolean,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AwgWarpProfileManager(context.applicationContext) }
    var dialogOpen by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var configDraft by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = {
            val metadata = manager.loadMetadata(profileId)
            val config = manager.loadConfig(profileId)
            if (metadata == null || config == null) {
                statusMessage = context.getString(
                    R.string.awg_warp_edit_profile_failed,
                    "profile_not_found",
                )
            } else {
                nameDraft = metadata.name
                configDraft = config
                dialogError = null
                dialogOpen = true
            }
        },
        enabled = !isProxyRunning,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.awg_warp_edit_profile))
    }

    statusMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.awg_warp_edit_profile_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.awg_warp_edit_profile_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = {
                            nameDraft = it.take(64)
                            dialogError = null
                        },
                        label = { Text(stringResource(R.string.awg_warp_profile_name)) },
                        enabled = !isProxyRunning,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configDraft,
                        onValueChange = {
                            configDraft = it
                            dialogError = null
                        },
                        label = { Text(stringResource(R.string.awg_warp_edit_profile_config_label)) },
                        enabled = !isProxyRunning,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(min = 240.dp, max = 400.dp),
                        minLines = 11,
                        maxLines = 19,
                    )
                    dialogError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val result = manager.updateProfile(
                            profileId = profileId,
                            name = nameDraft,
                            configText = configDraft,
                        )
                        if (result.isSuccess) {
                            dialogOpen = false
                            dialogError = null
                            statusMessage = context.getString(R.string.awg_warp_edit_profile_saved)
                            onSaved()
                        } else {
                            dialogError = context.getString(
                                R.string.awg_warp_edit_profile_failed,
                                awgWarpProfileEditorSafeError(result.exceptionOrNull()),
                            )
                        }
                    },
                    enabled = !isProxyRunning && nameDraft.isNotBlank() && configDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.awg_warp_edit_profile_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.awg_warp_cancel))
                }
            },
        )
    }
}

private fun awgWarpProfileEditorSafeError(throwable: Throwable?): String {
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
