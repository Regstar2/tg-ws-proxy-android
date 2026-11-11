package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateQuickDeliveryPolicyTest {
    @Test
    fun visibleAppWithInstallPermissionGoesDirectlyToSystemInstaller() {
        assertEquals(
            QuickUpdateDestination.OPEN_SYSTEM_INSTALLER,
            QuickUpdateDeliveryPolicy.destination(isForeground = true, canInstall = true),
        )
    }

    @Test
    fun visibleAppWithoutInstallPermissionOpensUpdatesForSettingsHandoff() {
        assertEquals(
            QuickUpdateDestination.OPEN_UPDATES_FOR_PERMISSION,
            QuickUpdateDeliveryPolicy.destination(isForeground = true, canInstall = false),
        )
    }

    @Test
    fun backgroundAppNeverOpensSystemInstaller() {
        assertEquals(
            QuickUpdateDestination.WAIT_FOR_FOREGROUND,
            QuickUpdateDeliveryPolicy.destination(isForeground = false, canInstall = true),
        )
        assertEquals(
            QuickUpdateDestination.WAIT_FOR_FOREGROUND,
            QuickUpdateDeliveryPolicy.destination(isForeground = false, canInstall = false),
        )
    }
}
