package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log

/**
 * Central app logger with optional persistent sink.
 *
 * Sinks:
 * - Android Logcat (always) for the existing runtime log UI (LogManager reads tag "TgWsProxy")
 * - In-memory runtime log UI remains unchanged (it still reads Logcat and keeps last ~400 lines)
 * - Optional file sink via [PersistentLogStore] (disabled by default)
 */
object AppLogger {
    private const val TAG = "TgWsProxy"

    fun d(context: Context, category: AppLogCategory, message: String, details: Map<String, String> = emptyMap()) {
        log(context, AppLogLevel.DEBUG, category, message, details)
    }

    fun i(context: Context, category: AppLogCategory, message: String, details: Map<String, String> = emptyMap()) {
        log(context, AppLogLevel.INFO, category, message, details)
    }

    fun w(context: Context, category: AppLogCategory, message: String, details: Map<String, String> = emptyMap()) {
        log(context, AppLogLevel.WARN, category, message, details)
    }

    fun e(context: Context, category: AppLogCategory, message: String, details: Map<String, String> = emptyMap(), tr: Throwable? = null) {
        log(context, AppLogLevel.ERROR, category, message, details, tr)
    }

    fun log(
        context: Context,
        level: AppLogLevel,
        category: AppLogCategory,
        message: String,
        details: Map<String, String> = emptyMap(),
        tr: Throwable? = null,
    ) {
        val safe = AppLogSanitizer.sanitizeEvent(
            AppLogEvent(
                level = level,
                category = category,
                message = message,
                details = details,
            ),
        )
        val logcat = AppLogFormatter.formatForLogcat(safe)
        when (level) {
            AppLogLevel.DEBUG -> Log.d(TAG, logcat, tr)
            AppLogLevel.INFO -> Log.i(TAG, logcat, tr)
            AppLogLevel.WARN -> Log.w(TAG, logcat, tr)
            AppLogLevel.ERROR -> Log.e(TAG, logcat, tr)
        }

        val prefs = PersistentLoggingPrefsStore.load(
            context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE),
        )
        PersistentLogStore.log(context, prefs, safe)
    }
}

