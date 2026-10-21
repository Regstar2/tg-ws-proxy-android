package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyQuickSettingsTileTest {
    @Test
    fun tileIsActiveOnlyForConfirmedRuntime() {
        assertTrue(ProxyTileStateMapper.isActive(ProxyServiceStatus.RUNNING, true))
        for (status in ProxyServiceStatus.entries) {
            if (status != ProxyServiceStatus.RUNNING) {
                assertFalse(ProxyTileStateMapper.isActive(status, true))
            }
            assertFalse(ProxyTileStateMapper.isActive(status, false))
        }
    }

    @Test
    fun transitionalStatesNeverBecomeActive() {
        assertTrue(ProxyTileStateMapper.isTransitioning(ProxyServiceStatus.STARTING))
        assertTrue(ProxyTileStateMapper.isTransitioning(ProxyServiceStatus.RECONNECTING))
        assertFalse(ProxyTileStateMapper.isTransitioning(ProxyServiceStatus.RUNNING))
        assertFalse(ProxyTileStateMapper.isTransitioning(ProxyServiceStatus.ERROR))
        assertFalse(ProxyTileStateMapper.isTransitioning(ProxyServiceStatus.STOPPED))
    }

    @Test
    fun freshInstallCannotBeStartedFromTileWithDefaultMtProto() {
        val initial = ProxySavedConfig()
        assertTrue(initial.isSufficientForStart())
        assertFalse(ProxyTileStartPolicy.isConfirmed(initial, null, "current"))
    }

    @Test
    fun invalidSocks5AndMtProtoPortsFailClosed() {
        val noRoutes = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "",
        )
        assertFalse(ProxyTileStartPolicy.isConfirmed(noRoutes, "same", "same"))
        val badPort = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            port = 65536,
        )
        assertFalse(ProxyTileStartPolicy.isConfirmed(badPort, "same", "same"))
    }

    @Test
    fun confirmedConfigIsRejectedIfNetworkOrRouteSignatureChanged() {
        val valid = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "route-tokens",
        )
        assertTrue(ProxyTileStartPolicy.isConfirmed(valid, "same", "same"))
        assertFalse(ProxyTileStartPolicy.isConfirmed(valid, "old", "new"))
        assertFalse(ProxyTileStartPolicy.isConfirmed(valid, "old", null))
    }

    @Test
    fun rapidTileClicksHaveOneCommandUntilCompletion() {
        ProxyTileCommandGate.release()
        try {
            assertTrue(ProxyTileCommandGate.tryAcquire())
            assertFalse(ProxyTileCommandGate.tryAcquire())
        } finally {
            ProxyTileCommandGate.release()
        }
        assertTrue(ProxyTileCommandGate.tryAcquire())
        ProxyTileCommandGate.release()
    }
}
