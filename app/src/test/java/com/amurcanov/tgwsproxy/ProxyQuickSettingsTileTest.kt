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
    fun priorSuccessfulStartOnSameNetworkCanBeReusedAfterUpgrade() {
        val expected = mapOf(
            "@network_profile_id" to "wifi-home",
            "@network_profile_type" to "wifi",
            "@connection_mode" to "cf_first",
            "@route_cf_proxy_ws" to "1",
        )
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            runtimeConfig = "149.154.175.50,@network_profile_id=wifi-home," +
                "@network_profile_type=wifi,@connection_mode=cf_first,@route_cf_proxy_ws=1",
        )
        assertTrue(ProxyTileStartPolicy.isLegacyReusable(saved, true, expected, ""))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(saved, false, expected, ""))
    }

    @Test
    fun savedRuntimeCannotBypassCurrentNetworkOrRoutePolicy() {
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "@network_profile_id=wifi-home,@network_profile_type=wifi," +
                "@connection_mode=cf_first,@route_cf_proxy_ws=1,@route_awg_warp=0",
        )
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, mapOf("@network_profile_id" to "mobile"), "",
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, mapOf("@route_cf_proxy_ws" to "0"), "",
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, mapOf("@route_awg_warp" to "1"), "",
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, mapOf("@network_profile_type" to "wifi"), "new-awg-path",
        ))
    }

    @Test
    fun emptyAndUnconfiguredSavedRuntimesNeverStart() {
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            ProxySavedConfig(), true, mapOf("@network_profile_id" to "wifi-home"), "",
        ))
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "@network_profile_id=wifi-home",
        )
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(saved, true, emptyMap(), ""))
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
