package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal enum class BootAutostartDecision {
    DISABLED,
    ALREADY_RUNNING,
    INVALID_SAVED_CONFIG,
    START,
}

internal object BootAutostartPolicy {
    fun evaluate(
        enabled: Boolean,
        savedConfig: ProxySavedConfig,
        alreadyRunning: Boolean,
    ): BootAutostartDecision {
        if (!enabled) {
            return BootAutostartDecision.DISABLED
        }
        if (alreadyRunning) {
            return BootAutostartDecision.ALREADY_RUNNING
        }
        if (!savedConfig.isSufficientForStart()) {
            return BootAutostartDecision.INVALID_SAVED_CONFIG
        }
        return BootAutostartDecision.START
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val appContext = context.applicationContext
        AppLogger.i(
            context = appContext,
            category = AppLogCategory.APP,
            message = "BOOT_COMPLETED_RECEIVED",
        )

        val prefs = appContext.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
        val autostartEnabled = AutostartPreferences(prefs).isEnabled()
        if (!autostartEnabled) {
            AppLogger.i(
                context = appContext,
                category = AppLogCategory.APP,
                message = "BOOT_AUTOSTART_DISABLED",
            )
            return
        }

        val savedConfig = ProxySavedConfigStore(prefs).load()
        when (
            BootAutostartPolicy.evaluate(
                enabled = true,
                savedConfig = savedConfig,
                alreadyRunning = ProxyService.isRunning.value,
            )
        ) {
            BootAutostartDecision.DISABLED -> Unit

            BootAutostartDecision.ALREADY_RUNNING -> {
                AppLogger.i(
                    context = appContext,
                    category = AppLogCategory.APP,
                    message = "BOOT_AUTOSTART_ALREADY_RUNNING",
                )
            }

            BootAutostartDecision.INVALID_SAVED_CONFIG -> {
                AppLogger.w(
                    context = appContext,
                    category = AppLogCategory.APP,
                    message = "BOOT_AUTOSTART_SKIPPED_INVALID_STATE",
                    details = mapOf("frontend" to savedConfig.frontendType.name),
                )
            }

            BootAutostartDecision.START -> {
                val serviceIntent = Intent(appContext, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                    AppLogger.i(
                        context = appContext,
                        category = AppLogCategory.APP,
                        message = "BOOT_AUTOSTART_START_REQUESTED",
                        details = mapOf("frontend" to savedConfig.frontendType.name),
                    )
                } catch (error: RuntimeException) {
                    AppLogger.e(
                        context = appContext,
                        category = AppLogCategory.APP,
                        message = "BOOT_AUTOSTART_START_FAILED",
                        details = mapOf("error" to error.javaClass.simpleName),
                    )
                }
            }
        }
    }
}
