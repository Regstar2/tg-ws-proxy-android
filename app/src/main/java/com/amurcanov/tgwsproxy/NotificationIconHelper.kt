package com.amurcanov.tgwsproxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

object NotificationIconHelper {
  private const val TAG = "TgWsProxy"

  val smallIconRes: Int = IconResources.notificationSmall
  val largeIconRes: Int = IconResources.notificationLarge

  fun largeIcon(context: Context): Bitmap? {
    Log.d(TAG, "Notification small icon resource=ic_notification_small_v2")
    Log.d(TAG, "Notification large icon resource=notification_app_icon_v2")
    return decodeBitmapResource(context, largeIconRes)
  }

  private fun decodeBitmapResource(context: Context, resId: Int): Bitmap? {
    return try {
      val opts = BitmapFactory.Options().apply { inScaled = false }
      BitmapFactory.decodeResource(context.resources, resId, opts)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode notification icon resId=$resId", e)
      null
    }
  }
}
