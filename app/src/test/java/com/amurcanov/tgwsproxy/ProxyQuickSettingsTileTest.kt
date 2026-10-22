package com.amurcanov.tgwsproxy

import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun configuredProxyStartDecisionIsExplicit() {
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "valid-route-tokens",
        )
        assertTrue(
            ProxyTileStartPolicy.decide(saved, "same", "same", false) ==
                ProxyTileStartDecision.READY,
        )
        assertTrue(
            ProxyTileStartPolicy.decide(saved, null, "current", true) ==
                ProxyTileStartDecision.READY,
        )
        assertTrue(
            ProxyTileStartPolicy.decide(saved, "old", "new", true) ==
                ProxyTileStartDecision.CONFIG_CHANGED,
        )
        assertTrue(
            ProxyTileStartPolicy.decide(saved, null, null, true) ==
                ProxyTileStartDecision.CONFIG_CHANGED,
        )
    }

    @Test
    fun missingProxyConfigReportsSetupRequired() {
        val missing = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "",
        )
        assertTrue(
            ProxyTileStartPolicy.decide(missing, null, null, false) ==
                ProxyTileStartDecision.SETUP_REQUIRED,
        )
    }

    @Test
    fun workerHealthAndCacheUpdatesDoNotInvalidateConfirmedSettings() {
        val worker = WorkerEndpoint(
            id = "worker-1",
            name = "Primary",
            url = "https://worker.example",
            enabled = true,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val healthUpdate = worker.copy(
            state = WorkerHealthState.HEALTHY,
            latencyMs = 50L,
            failureCount = 2,
            lastCheckedAt = 500L,
            updatedAt = 500L,
        )
        assertEquals(
            ProxyTileStartPolicy.stableWorkerConfig(listOf(worker)),
            ProxyTileStartPolicy.stableWorkerConfig(listOf(healthUpdate)),
        )
        assertFalse(ProxyTileStartPolicy.isSignaturePreference("cf_upstream_domains", true))
        assertFalse(ProxyTileStartPolicy.isSignaturePreference("worker_pool_workers_v1", true))
        assertTrue(ProxyTileStartPolicy.isSignaturePreference("worker_pool_workers_v1", false))
    }

    @Test
    fun changingWorkerEndpointOrRoutingSettingsInvalidatesConfirmation() {
        val worker = WorkerEndpoint(
            id = "worker-1",
            name = "Primary",
            url = "https://first.example",
            enabled = true,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val expected = ProxyTileStartPolicy.stableWorkerConfig(listOf(worker))
        assertNotEquals(
            expected,
            ProxyTileStartPolicy.stableWorkerConfig(
                listOf(worker.copy(url = "https://different.example")),
            ),
        )
        assertNotEquals(
            expected,
            ProxyTileStartPolicy.stableWorkerConfig(
                listOf(worker.copy(enabled = false)),
            ),
        )
        assertTrue(ProxyTileStartPolicy.isSignaturePreference("connection_mode", true))
        assertTrue(ProxyTileStartPolicy.isSignaturePreference("worker_pool_selected_id_v1", true))
        assertTrue(ProxyTileStartPolicy.isSignaturePreference("worker_pool_workers_v1", false))
    }

    @Test
    fun savedRuntimeCanBeReusedWithoutMatchingVolatileFingerprint() {
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            runtimeConfig = "@network_profile_id=wifi-known,@network_profile_type=wifi," +
                "@connection_mode=cf_first,@auto_strategy=balanced," +
                "@route_cf_proxy_ws=1,@route_awg_warp=0",
        )
        val expected = mapOf(
            "@network_profile_id" to "wifi-known",
            "@network_profile_type" to "wifi",
            "@connection_mode" to "cf_first",
            "@auto_strategy" to "balanced",
            "@route_cf_proxy_ws" to "1",
            "@route_awg_warp" to "0",
        )
        assertTrue(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected, "",
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected + ("@route_awg_warp" to "1"), "",
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected + ("@network_profile_id" to "mobile-other"), "",
        ))
    }

    @Test
    fun importedWarpConfigurationMustBeConfirmedBeforeReuse() {
        val path = "/data/user/0/app/files/awg-warp/profile.conf"
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            runtimeConfig = "@network_profile_id=wifi-known," +
                "@network_profile_type=wifi,@awg_warp_config_path=$path",
        )
        val expected = mapOf(
            "@network_profile_id" to "wifi-known",
            "@network_profile_type" to "wifi",
        )
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected, path,
        ))
        assertTrue(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected, path, awgPreviouslyConfirmed = true,
        ))
        assertFalse(ProxyTileStartPolicy.isLegacyReusable(
            saved, true, expected, "$path.changed", awgPreviouslyConfirmed = true,
        ))
    }

    @Test
    fun previouslyRunningMtProtoCanBeStartedFromSavedConfig() {
        val saved = ProxySavedConfig(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            port = 1443,
        )
        val valid = MtProtoProxyConfig(port = 1443, secret = "0123456789abcdef0123456789abcdef")
        assertTrue(ProxyTileStartPolicy.isFrontendValid(saved, valid))
        assertFalse(ProxyTileStartPolicy.isFrontendValid(saved, valid.copy(port = 1444)))
        assertFalse(ProxyTileStartPolicy.isFrontendValid(saved, valid.copy(enabled = false)))
        assertFalse(ProxyTileStartPolicy.isFrontendValid(saved, valid.copy(secret = "")))
        assertFalse(ProxyTileStartPolicy.isFrontendValid(saved, null))
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
