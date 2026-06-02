package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PersistentLogExportReport(
    val fileName: String,
    val uri: Uri,
    val fileCount: Int,
    val totalBytes: Long,
)

object PersistentLogExport {
    private const val EXPORT_SUBDIR = "logs-export"

    suspend fun exportZip(context: Context): PersistentLogExportReport = withContext(Dispatchers.IO) {
        val files = PersistentLogStore.listLogFiles(context)
            .filter { it.name.endsWith(".log") } // avoid zipping previous exports

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val fileName = "logs-export-$stamp.zip"
        val exportDir = File(context.cacheDir, EXPORT_SUBDIR).also { it.mkdirs() }
        val zipFile = File(exportDir, fileName)

        // Always overwrite if exists.
        runCatching { zipFile.delete() }

        var totalBytes = 0L
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            files.forEach { f ->
                val entry = ZipEntry("logs/${f.name}")
                zos.putNextEntry(entry)
                FileInputStream(f).use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        zos.write(buf, 0, read)
                        totalBytes += read.toLong()
                    }
                }
                zos.closeEntry()
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        PersistentLogExportReport(
            fileName = fileName,
            uri = uri,
            fileCount = files.size,
            totalBytes = totalBytes,
        )
    }
}

