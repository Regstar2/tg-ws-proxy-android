package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyQuickSettingsTileTest {
    private fun socks(runtime: String = "@network_profile_type=wifi,@route_cf_proxy_ws=1") =
        ProxySavedConfig(
            frontendType = LocalProxyFrontendType.SOCKS5,
            port = 1443,
            runtimeConfig = runtime,
            poolSize = 4,
        )

    private fun assertTileState(
        status: ProxyServiceStatus,
        running: Boolean,
        action: ProxyTileClickAction,
        active: Boolean,
        transitioning: Boolean,
        preparingStart: Boolean = false,
        stopping: Boolean = false,
    ) {
        val state = ProxyTileStateMapper.resolve(status, running, preparingStart, stopping)
        assertEquals(action, state.clickAction)
        assertEquals(active, state.active)
        assertEquals(transitioning, state.transitioning)
    }

    @Test
    fun confirmedRunningAndStoppedStatesChooseMatchingClickActions() {
        assertTileState(
            ProxyServiceStatus.RUNNING, true,
            ProxyTileClickAction.STOP, active = true, transitioning = false,
        )
        assertTileState(
            ProxyServiceStatus.STOPPED, false,
            ProxyTileClickAction.START, active = false, transitioning = false,
        )
        assertTileState(
            ProxyServiceStatus.ERROR, false,
            ProxyTileClickAction.START, active = false, transitioning = false,
        )
    }

    @Test
    fun mismatchedRunningAndStatusNeverDispatchStopFromInactiveTile() {
        listOf(
            ProxyServiceStatus.STOPPED,
            ProxyServiceStatus.STARTING,
            ProxyServiceStatus.RECONNECTING,
            ProxyServiceStatus.ERROR,
        ).forEach { status ->
            assertTileState(
                status, true,
                ProxyTileClickAction.NONE, active = false, transitioning = true,
            )
        }
        assertTileState(
            ProxyServiceStatus.RUNNING, false,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
        )
    }

    @Test
    fun pendingStartAndReconnectAreNotClickableOrActive() {
        assertTileState(
            ProxyServiceStatus.STOPPED, false,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
            preparingStart = true,
        )
        assertTileState(
            ProxyServiceStatus.STARTING, false,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
        )
        assertTileState(
            ProxyServiceStatus.RECONNECTING, true,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
        )
        assertTileState(
            ProxyServiceStatus.RECONNECTING, false,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
        )
    }

    @Test
    fun stopRequestedKeepsActiveRuntimeUnavailableUntilStopIsConfirmed() {
        assertTileState(
            ProxyServiceStatus.RUNNING, true,
            ProxyTileClickAction.NONE, active = false, transitioning = true,
            stopping = true,
        )
        assertTileState(
            ProxyServiceStatus.STOPPED, false,
            ProxyTileClickAction.START, active = false, transitioning = false,
        )
        assertTileState(
            ProxyServiceStatus.ERROR, false,
            ProxyTileClickAction.START, active = false, transitioning = false,
        )
    }

    @Test
    fun delayedStatusEventsDoNotInvertTileActionDuringStartOrStop() {
        val states = listOf(
            Triple(ProxyServiceStatus.STOPPED, false, ProxyTileClickAction.START),
            Triple(ProxyServiceStatus.STARTING, false, ProxyTileClickAction.NONE),
            Triple(ProxyServiceStatus.STARTING, true, ProxyTileClickAction.NONE),
            Triple(ProxyServiceStatus.RUNNING, true, ProxyTileClickAction.STOP),
            Triple(ProxyServiceStatus.RUNNING, false, ProxyTileClickAction.NONE),
            Triple(ProxyServiceStatus.STOPPED, false, ProxyTileClickAction.START),
        )
        states.forEach { (status, running, expectedAction) ->
            assertEquals(expectedAction, ProxyTileStateMapper.resolve(status, running).clickAction)
        }
    }

    @Test
    fun reopeningQuickSettingsRecomputesStateInsteadOfReusingOldColor() {
        val initiallyActive = ProxyTileStateMapper.resolve(ProxyServiceStatus.RUNNING, true)
        assertTrue(initiallyActive.active)
        val stoppedWhileShadeClosed = ProxyTileStateMapper.resolve(ProxyServiceStatus.STOPPED, false)
        assertFalse(stoppedWhileShadeClosed.active)
        assertEquals(ProxyTileClickAction.START, stoppedWhileShadeClosed.clickAction)
        val restartedWhileShadeClosed = ProxyTileStateMapper.resolve(ProxyServiceStatus.RUNNING, true)
        assertTrue(restartedWhileShadeClosed.active)
        assertEquals(ProxyTileClickAction.STOP, restartedWhileShadeClosed.clickAction)
    }

    @Test
    fun firstUseRequiresOneSuccessfulAppStart() {
        var rebuildCalls = 0
        val check = ProxyTileStartPolicy.evaluateFreshStart(false, null) {
            rebuildCalls++
            socks()
        }
        assertEquals(ProxyTileStartDecision.SETUP_REQUIRED, check.decision)
        assertNull(check.saved)
        assertEquals(0, rebuildCalls)
    }

    @Test
    fun eachTileStartUsesNewNetworkAndRouteConfigInsteadOfLastRuntime() {
        var current = "@network_profile_type=wifi,@route_cf_proxy_ws=1"
        var calls = 0
        fun click(): ProxyTileStartCheck = ProxyTileStartPolicy.evaluateFreshStart(true, null) {
            calls++
            socks(current)
        }

        val wifi = click()
        assertEquals(ProxyTileStartDecision.READY, wifi.decision)
        assertEquals("@network_profile_type=wifi,@route_cf_proxy_ws=1", wifi.saved?.runtimeConfig)

        current = "@network_profile_type=mobile,@route_awg_warp=1,@route_cf_proxy_ws=0"
        val mobile = click()
        assertEquals(ProxyTileStartDecision.READY, mobile.decision)
        assertEquals(current, mobile.saved?.runtimeConfig)
        assertEquals(2, calls)
        assertEquals("@network_profile_type=wifi,@route_cf_proxy_ws=1", wifi.saved?.runtimeConfig)
    }

    @Test
    fun changingPreferencesDoesNotRequireAnotherInAppStart() {
        val modified = socks("@network_profile_type=wifi,@preferred_route=direct_ws")
        val check = ProxyTileStartPolicy.evaluateFreshStart(true, null) { modified }
        assertEquals(ProxyTileStartDecision.READY, check.decision)
        assertEquals(modified, check.saved)
    }

    @Test
    fun missingOrInvalidRebuiltRuntimeNeverFallsBackToSavedIps() {
        val invalid = listOf<ProxySavedConfig?>(
            null,
            socks(""),
            socks().copy(port = 0),
            socks().copy(port = 65536),
            socks().copy(poolSize = 0),
        )
        invalid.forEach { value ->
            val check = ProxyTileStartPolicy.evaluateFreshStart(true, null) { value }
            assertEquals(ProxyTileStartDecision.SETUP_REQUIRED, check.decision)
            assertNull(check.saved)
        }
    }

    @Test
    fun validMtProtoIsAllowedWithRefreshedRuntime() {
        val config = MtProtoProxyConfig(
            port = 1443,
            secret = "0123456789abcdef0123456789abcdef",
        )
        val saved = socks("@network_profile_type=mobile").copy(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
        )
        val check = ProxyTileStartPolicy.evaluateFreshStart(true, config) { saved }
        assertEquals(ProxyTileStartDecision.READY, check.decision)
        assertEquals(saved, check.saved)
    }

    @Test
    fun mtProtoRejectsInvalidOrMismatchedSettings() {
        val config = MtProtoProxyConfig(
            port = 1443,
            secret = "0123456789abcdef0123456789abcdef",
        )
        val saved = socks().copy(frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL)
        val badConfigs = listOf<MtProtoProxyConfig?>(
            null,
            config.copy(port = 1444),
            config.copy(secret = ""),
            config.copy(enabled = false),
            config.copy(experimentalAcknowledged = false),
        )
        badConfigs.forEach { value ->
            val check = ProxyTileStartPolicy.evaluateFreshStart(true, value) { saved }
            assertEquals(ProxyTileStartDecision.FRONTEND_INVALID, check.decision)
            assertNull(check.saved)
        }
    }

    @Test
    fun socks5DoesNotRequireMtProtoConfiguration() {
        val check = ProxyTileStartPolicy.evaluateFreshStart(true, null) {
            socks("@network_profile_type=mobile,@route_worker_ws=1")
        }
        assertEquals(ProxyTileStartDecision.READY, check.decision)
    }

    @Test
    fun rapidTileClicksAreSerialized() {
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
