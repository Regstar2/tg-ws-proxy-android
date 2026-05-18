package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationIconHelperTest {
    @Test
    fun smallIcon_usesVersionedMonochromeVector() {
        assertEquals(R.drawable.ic_notification_small_v2, NotificationIconHelper.smallIconRes)
    }

    @Test
    fun largeIcon_usesVersionedColorAsset() {
        assertEquals(R.drawable.notification_app_icon_v2, NotificationIconHelper.largeIconRes)
    }

    @Test
    fun smallAndLargeIcons_differ() {
        assertNotEquals(NotificationIconHelper.smallIconRes, NotificationIconHelper.largeIconRes)
    }

    @Test
    fun smallIcon_isNotLauncher() {
        assertNotEquals(IconResources.launcher, NotificationIconHelper.smallIconRes)
    }

    @Test
    fun iconResources_pointToVersionedLauncher() {
        assertEquals(R.mipmap.ic_launcher_tgwsproxy_v2, IconResources.launcher)
        assertEquals(R.mipmap.ic_launcher_tgwsproxy_round_v2, IconResources.launcherRound)
    }
}
