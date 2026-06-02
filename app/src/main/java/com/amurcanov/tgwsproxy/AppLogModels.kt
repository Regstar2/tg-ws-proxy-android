package com.amurcanov.tgwsproxy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AppLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class AppLogCategory {
    APP,
    TG,
    PROXY,
    NETWORK,
    DNS,
    VPN,
    UI,
}

data class AppLogEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: AppLogLevel,
    val category: AppLogCategory,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

object AppLogFormatter {
    private val fileTs: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun formatForFile(event: AppLogEvent): String {
        val ts = fileTs.format(Instant.ofEpochMilli(event.timestampMs))
        val base = "[$ts] [${event.level.name}] [${event.category.name}] ${event.message}"
        if (event.details.isEmpty()) {
            return base
        }
        val suffix = event.details.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return "$base $suffix"
    }

    /**
     * Logcat line optimized for the existing runtime log UI parser (it looks for "INFO  ", "WARN  ", etc).
     */
    fun formatForLogcat(event: AppLogEvent): String {
        val prefix = when (event.level) {
            AppLogLevel.DEBUG -> "DEBUG "
            AppLogLevel.INFO -> "INFO  "
            AppLogLevel.WARN -> "WARN  "
            AppLogLevel.ERROR -> "ERROR "
        }
        // Do not embed a second timestamp: logcat already prefixes each line with wall clock time.
        val base = "$prefix[${event.category.name}] ${event.message}"
        if (event.details.isEmpty()) return base
        val suffix = event.details.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return "$base $suffix"
    }
}

