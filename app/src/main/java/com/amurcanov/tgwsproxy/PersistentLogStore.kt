package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Optional file log sink with rotation & retention.
 *
 * Internal storage path:
 *   /data/data/<package>/files/logs/
 */
object PersistentLogStore {
    private const val TAG = "TgWsProxy"
    private const val DIR_NAME = "logs"
    private const val CURRENT_FILE = "app-current.log"
    private const val MAX_CURRENT_FILE_BYTES = PersistentLogLimits.DEFAULT_MAX_LOG_FILE_SIZE_BYTES
    private const val MAX_ARCHIVED_FILES = PersistentLogLimits.DEFAULT_MAX_LOG_FILE_COUNT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<WriteCmd>(capacity = Channel.BUFFERED)

    @Volatile
    private var started = false

    @Volatile
    private var lastWriteError: String? = null

    private var writer: BufferedWriter? = null
    private var currentDate: LocalDate? = null
    private var writesSinceMaintenance: Int = 0

    sealed class WriteCmd {
        data class Line(val line: String) : WriteCmd()
        data object Flush : WriteCmd()
        data object Stop : WriteCmd()
    }

    fun initIfNeeded(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch {
                runCatching { maintenance(context.applicationContext) }
                runWriterLoop(context.applicationContext)
            }
        }
    }

    fun getLastWriteError(): String? = lastWriteError

    fun log(context: Context, prefs: PersistentLoggingPrefs, event: AppLogEvent) {
        if (!prefs.enabled) return
        initIfNeeded(context)
        val safe = AppLogSanitizer.sanitizeEvent(event)
        if (!shouldWrite(prefs, safe.level)) return
        val line = AppLogFormatter.formatForFile(safe)
        // Never block callers (including UI thread).
        queue.trySend(WriteCmd.Line(line))
    }

    fun logRawLogcatLine(context: Context, prefs: PersistentLoggingPrefs, rawLine: String) {
        if (!prefs.enabled) return
        initIfNeeded(context)
        val safe = AppLogSanitizer.sanitizeText(rawLine).trimEnd()
        if (safe.isBlank()) return

        // Best-effort priority mapping from logcat line.
        // Example: "03-24 14:30:45.057 I/TgWsProxy(24567): INFO  [APP] ..."
        val level = when {
            safe.contains(" E/") || safe.contains(" E\\") -> AppLogLevel.ERROR
            safe.contains(" W/") || safe.contains(" W\\") -> AppLogLevel.WARN
            safe.contains(" D/") || safe.contains(" D\\") -> AppLogLevel.DEBUG
            else -> AppLogLevel.INFO
        }
        if (!shouldWrite(prefs, level)) return
        queue.trySend(WriteCmd.Line(safe))
    }

    fun flushAsync() {
        queue.trySend(WriteCmd.Flush)
    }

    fun stopAsync() {
        queue.trySend(WriteCmd.Stop)
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        val dir = logsDir(context)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    suspend fun totalSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val dir = logsDir(context)
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun listLogFiles(context: Context): List<File> = withContext(Dispatchers.IO) {
        val dir = logsDir(context)
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".zip")) }
            .sortedByDescending { it.lastModified() }
    }

    private fun shouldWrite(prefs: PersistentLoggingPrefs, level: AppLogLevel): Boolean {
        return when (prefs.verbosity) {
            PersistentLogVerbosity.ERRORS_ONLY -> level == AppLogLevel.ERROR
            PersistentLogVerbosity.IMPORTANT -> level == AppLogLevel.WARN || level == AppLogLevel.ERROR || level == AppLogLevel.INFO
            PersistentLogVerbosity.VERBOSE -> level != AppLogLevel.DEBUG
            PersistentLogVerbosity.DEBUG -> true
        }
    }

    private suspend fun runWriterLoop(context: Context) {
        try {
            for (cmd in queue) {
                when (cmd) {
                    is WriteCmd.Line -> {
                        writeLine(context, cmd.line)
                    }
                    WriteCmd.Flush -> {
                        flush()
                    }
                    WriteCmd.Stop -> {
                        flush()
                        closeWriter()
                        return
                    }
                }
            }
        } finally {
            closeWriter()
        }
    }

    private fun logsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun currentFile(context: Context): File = File(logsDir(context), CURRENT_FILE)

    private fun ensureWriter(context: Context) {
        if (writer != null) return
        val file = currentFile(context)
        writer = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(file, true),
                StandardCharsets.UTF_8,
            ),
        )
        currentDate = LocalDate.now()
    }

    private fun writeLine(context: Context, line: String) {
        try {
            ensureWriter(context)
            rotateIfNeeded(context)
            writer?.apply {
                write(line)
                newLine()
            }
            writesSinceMaintenance++
            if (writesSinceMaintenance >= 200) {
                writesSinceMaintenance = 0
                // Best-effort maintenance, never crash the app.
                runCatching { maintenance(context) }
            }
            lastWriteError = null
        } catch (e: Exception) {
            // Avoid infinite recursion by never trying to persist this error.
            lastWriteError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Persistent log write failed: ${e.javaClass.simpleName}", e)
            // Close writer to allow recovery later.
            closeWriter()
        }
    }

    private fun flush() {
        runCatching { writer?.flush() }
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        currentDate = null
    }

    private fun rotateIfNeeded(context: Context) {
        val nowDate = LocalDate.now()
        val dateChanged = currentDate != null && currentDate != nowDate
        val tooBig = runCatching { currentFile(context).length() > MAX_CURRENT_FILE_BYTES }.getOrDefault(false)
        if (!dateChanged && !tooBig) return

        val from = currentFile(context)
        if (!from.exists() || from.length() == 0L) {
            currentDate = nowDate
            return
        }
        closeWriter()

        val dir = logsDir(context)
        if (tooBig && !dateChanged) {
            rotateNumberedFiles(dir, from)
        } else {
            val dateToUse = (currentDate ?: nowDate)
            val baseName = "app-$dateToUse.log"
            var target = File(dir, baseName)
            var idx = 1
            while (target.exists()) {
                target = File(dir, "app-$dateToUse-$idx.log")
                idx++
            }
            runCatching { from.renameTo(target) }
        }
        currentDate = nowDate
        ensureWriter(context)
    }

    private fun rotateNumberedFiles(dir: File, current: File) {
        val oldest = File(dir, "$CURRENT_FILE.$MAX_ARCHIVED_FILES")
        runCatching { oldest.delete() }
        for (index in MAX_ARCHIVED_FILES downTo 2) {
            val from = File(dir, "$CURRENT_FILE.${index - 1}")
            val to = File(dir, "$CURRENT_FILE.$index")
            if (from.exists()) {
                runCatching { from.renameTo(to) }
            }
        }
        val firstArchive = File(dir, "$CURRENT_FILE.1")
        runCatching { current.renameTo(firstArchive) }
    }

    private fun maintenance(context: Context) {
        val prefs = PersistentLoggingPrefsStore.load(
            context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE),
        )
        if (!prefs.enabled) return
        rotateIfNeeded(context)
        enforceRetention(context, prefs.retentionDays)
        enforceMaxTotalSize(context, prefs.maxTotalSizeBytes)
    }

    private fun enforceRetention(context: Context, retentionDays: Int) {
        val dir = logsDir(context)
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            if (!name.startsWith("app-") || !name.endsWith(".log")) return@forEach
            if (name == CURRENT_FILE) return@forEach
            val date = extractDate(name) ?: return@forEach
            if (date.isBefore(cutoff)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun enforceMaxTotalSize(context: Context, maxBytes: Long) {
        val dir = logsDir(context)
        val files = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.endsWith(".log") && it.name != CURRENT_FILE }
            .sortedBy { it.lastModified() } // oldest first
            .toMutableList()
        var total = (dir.listFiles()?.sumOf { it.length() } ?: 0L)
        while (total > maxBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val len = oldest.length()
            if (runCatching { oldest.delete() }.getOrDefault(false)) {
                total -= len
            } else {
                // If we can't delete, avoid infinite loop.
                break
            }
        }
    }

    private fun extractDate(fileName: String): LocalDate? {
        // app-YYYY-MM-DD(.log) or app-YYYY-MM-DD-1.log
        val core = fileName.removePrefix("app-").removeSuffix(".log")
        val datePart = core.split('-').take(3).joinToString("-")
        return runCatching { LocalDate.parse(datePart) }.getOrNull()
    }
}

