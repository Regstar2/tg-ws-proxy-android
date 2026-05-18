package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class NotificationDisplayMode(val prefValue: String) {
    NORMAL("normal"),
    COMPACT("compact"),
    MINIMAL("minimal"),
    ;

    companion object {
        fun fromPref(raw: String?): NotificationDisplayMode {
            return entries.firstOrNull { it.prefValue == raw } ?: NORMAL
        }
    }
}

data class NotificationPreferences(
    val displayMode: NotificationDisplayMode = NotificationDisplayMode.NORMAL,
    val showMetricsInNotification: Boolean = true,
    val showMetricsInApp: Boolean = true,
    val notificationActionsEnabled: Boolean = true,
) {
    companion object {
        fun load(context: Context): NotificationPreferences {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return NotificationPreferences(
                displayMode = NotificationDisplayMode.fromPref(
                    prefs.getString(KEY_DISPLAY_MODE, null),
                ),
                showMetricsInNotification = prefs.getBoolean(KEY_METRICS_NOTIFICATION, true),
                showMetricsInApp = prefs.getBoolean(KEY_METRICS_APP, true),
                notificationActionsEnabled = prefs.getBoolean(KEY_ACTIONS, true),
            )
        }

        fun save(context: Context, prefs: NotificationPreferences) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DISPLAY_MODE, prefs.displayMode.prefValue)
                .putBoolean(KEY_METRICS_NOTIFICATION, prefs.showMetricsInNotification)
                .putBoolean(KEY_METRICS_APP, prefs.showMetricsInApp)
                .putBoolean(KEY_ACTIONS, prefs.notificationActionsEnabled)
                .apply()
        }

        fun openSystemNotificationSettings(context: Context) {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, ProxyService.CHANNEL_STATUS_ID)
                } else {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ContextCompat.startActivity(context, intent, null)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ContextCompat.startActivity(context, fallback, null)
            }
        }

        private const val PREFS = "ProxyPrefs"
        private const val KEY_DISPLAY_MODE = "notification_display_mode"
        private const val KEY_METRICS_NOTIFICATION = "show_metrics_in_notification"
        private const val KEY_METRICS_APP = "show_metrics_in_app"
        private const val KEY_ACTIONS = "notification_actions_enabled"
    }
}
