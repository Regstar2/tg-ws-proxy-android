package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateInstallPermissionPolicyTest {
    @Test
    fun installWithAlreadyGrantedPermissionOpensInstallerDirectly() {
        assertEquals(
            UpdateInstallPermissionAction.OPEN_INSTALLER,
            UpdateInstallPermissionPolicy.onInstallConfirmed(granted = true),
        )
    }

    @Test
    fun firstInstallWithoutPermissionOpensSettings() {
        assertEquals(
            UpdateInstallPermissionAction.OPEN_SETTINGS,
            UpdateInstallPermissionPolicy.onInstallConfirmed(granted = false),
        )
    }

    @Test
    fun returningFromSettingsWithPermissionContinuesInstallation() {
        assertEquals(
            UpdateInstallPermissionAction.OPEN_INSTALLER,
            UpdateInstallPermissionPolicy.onSettingsReturned(granted = true),
        )
    }

    @Test
    fun denyingPermissionDoesNotReopenSettingsInLoop() {
        assertEquals(
            UpdateInstallPermissionAction.WAIT_FOR_USER,
            UpdateInstallPermissionPolicy.onSettingsReturned(granted = false),
        )
    }
}
