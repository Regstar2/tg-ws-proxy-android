package com.amurcanov.tgwsproxy

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

object ProxyNotificationBuilder {
    fun build(
        context: Context,
        prefs: NotificationPreferences,
        title: String,
        contentText: String,
        expandedText: String?,
        contentIntent: PendingIntent,
        actions: List<NotificationCompat.Action>,
    ): Notification {
        val builder = NotificationCompat.Builder(context, ProxyService.CHANNEL_STATUS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(NotificationIconHelper.smallIconRes)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        NotificationIconHelper.largeIcon(context)?.let { builder.setLargeIcon(it) }

        if (!expandedText.isNullOrBlank() && prefs.displayMode == NotificationDisplayMode.NORMAL) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
        }

        if (prefs.notificationActionsEnabled) {
            val maxActions = when (prefs.displayMode) {
                NotificationDisplayMode.MINIMAL -> 2
                NotificationDisplayMode.COMPACT -> 3
                NotificationDisplayMode.NORMAL -> 4
            }
            actions.take(maxActions).forEach { builder.addAction(it) }
        }

        return builder.build()
    }
}
