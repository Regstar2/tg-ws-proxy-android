package com.amurcanov.tgwsproxy.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DiagnosticReportShareResult(
    val uri: Uri,
    val fileName: String,
)

object DiagnosticReportExporter {
    private const val EXPORT_SUBDIR = "diagnostic-reports"

    suspend fun writeTempReportFile(context: Context, sanitizedText: String): DiagnosticReportShareResult =
        withContext(Dispatchers.IO) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
            val fileName = "tgwsproxy-diagnostic-report-$stamp.txt"
            val dir = File(context.cacheDir, EXPORT_SUBDIR).also { it.mkdirs() }
            val file = File(dir, fileName)
            file.writeText(sanitizedText, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            DiagnosticReportShareResult(uri = uri, fileName = fileName)
        }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diagnostic-report", text))
    }

    fun shareIntent(result: DiagnosticReportShareResult, chooserTitle: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, result.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, chooserTitle) }
    }

    fun shareTextIntent(text: String, chooserTitle: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }.let { Intent.createChooser(it, chooserTitle) }
    }
}
