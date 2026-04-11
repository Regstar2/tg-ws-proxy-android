package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RuntimeLogSaveReport(
    val fileName: String,
    val savedUri: Uri?,
    val lineCount: Int,
)

object RuntimeLogExport {
    suspend fun save(
        context: Context,
        treeUri: Uri,
        logs: List<String>,
        proxyRunning: Boolean,
    ): RuntimeLogSaveReport = withContext(Dispatchers.IO) {
        val exportedAt = Date()
        val fileName = "runtime-log-${fileStamp(exportedAt)}.txt"
        val payload = buildString {
            appendLine("TgWsProxy runtime log export")
            appendLine("saved_at=${humanStamp(exportedAt)}")
            appendLine("proxy_running=$proxyRunning")
            appendLine("line_count=${logs.size}")
            appendLine()
            if (logs.isEmpty()) {
                appendLine("(no logs captured)")
            } else {
                logs.forEach { appendLine(it) }
            }
        }

        RuntimeLogSaveReport(
            fileName = fileName,
            savedUri = ArtifactStore.saveTextFile(
                context = context,
                treeUri = treeUri,
                subdirectoryName = ArtifactStore.RUNTIME_LOGS_DIR,
                fileName = fileName,
                text = payload
            ),
            lineCount = logs.size
        )
    }

    private fun fileStamp(date: Date): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date)
    }

    private fun humanStamp(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
    }
}
