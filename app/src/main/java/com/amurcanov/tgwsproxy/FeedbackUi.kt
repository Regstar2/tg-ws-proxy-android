package com.amurcanov.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val BUG_REPORT_URL =
    "https://github.com/Regstar2/tg-ws-proxy-android/issues/new?template=bug_report.yml"
private const val FEATURE_REQUEST_URL =
    "https://github.com/Regstar2/tg-ws-proxy-android/issues/new?template=feature_request.yml"

internal fun safeFeedbackContext(context: Context): String {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "unknown"
    val versionCode = if (packageInfo == null) {
        "unknown"
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode.toString()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toString()
    }
    val manufacturer = Build.MANUFACTURER.trim().ifBlank { "unknown" }
    val model = Build.MODEL.trim().ifBlank { "unknown" }

    return buildString {
        appendLine("TgWsProxy: $versionName ($versionCode)")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        append("Device: $manufacturer $model")
    }
}

private fun openFeedbackForm(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("GitHub feedback form", url))
        Toast.makeText(context, context.getString(R.string.feedback_open_failed), Toast.LENGTH_LONG).show()
    }
}

@Composable
fun FeedbackSettingsCard(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safeContext = remember(context) { safeFeedbackContext(context) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.feedback_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { openFeedbackForm(context, BUG_REPORT_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feedback_report_bug))
            }
            OutlinedButton(
                onClick = { openFeedbackForm(context, FEATURE_REQUEST_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feedback_request_feature))
            }
            Text(
                text = stringResource(R.string.feedback_safe_context_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = safeContext,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("TgWsProxy app/device info", safeContext))
                    Toast.makeText(
                        context,
                        context.getString(R.string.feedback_context_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feedback_copy_context))
            }
            Text(
                text = stringResource(R.string.feedback_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
