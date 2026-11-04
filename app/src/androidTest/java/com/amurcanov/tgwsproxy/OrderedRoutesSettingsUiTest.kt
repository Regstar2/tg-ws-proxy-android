package com.amurcanov.tgwsproxy

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderedRoutesSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun wifiAndMobileKeepIndependentOrders() {
        render()
        count(3)
        rule.onNodeWithTag("routes.tab.mobile").performClick()
        count(1)
        rule.onNodeWithTag("routes.toggle.awg_warp").performScrollTo().performClick()
        count(2)
        rule.onNodeWithTag("routes.tab.wifi").performClick()
        count(3)
        rule.onNodeWithTag("routes.tab.mobile").performClick()
        count(2)
    }

    @Test
    fun switchMovesDisabledRouteOutAndAppendsWhenEnabledAgain() {
        render()
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        count(2)
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOff()
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        count(3)
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOn()
        assertChain(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS)
    }

    @Test
    fun arrowsChangeStrictFallbackChain() {
        render()
        rule.onNodeWithTag("routes.down.cf_proxy_ws").performClick()
        assertChain(RouteKind.DIRECT_WS, RouteKind.CF_PROXY_WS, RouteKind.TCP_FALLBACK)
        rule.onNodeWithTag("routes.up.cf_proxy_ws").performClick()
        assertChain(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
    }

    @Test
    fun lastActiveRouteCannotBeDisabled() {
        render()
        rule.onNodeWithTag("routes.tab.mobile").performClick()
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        count(1)
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOn()
    }

    @Test
    fun resetOnlySelectedProfileAfterConfirmation() {
        render()
        rule.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        count(2)
        rule.onNodeWithTag("routes.reset").performScrollTo().performClick()
        rule.onNodeWithTag("routes.confirm_reset").performClick()
        count(3)
        assertChain(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
        rule.onNodeWithTag("routes.tab.mobile").performClick()
        count(1)
    }

    @Test
    fun dragHandleCanChangeActivePriority() {
        render()
        rule.onNodeWithTag("routes.drag.tcp_fallback").performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(0f, -180f))
            moveBy(Offset(0f, -180f))
            up()
        }
        // Drag moves only enabled rows; disabled candidates remain absent from the chain.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initial = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
            .joinToString(" → ") { context.getString(label(it)) }
        val actual = rule.onNodeWithTag("routes.chain").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
            ?.joinToString("") { it.text }.orEmpty()
        org.junit.Assert.assertTrue("drag did not change route order", actual != initial)
    }

    private fun render() {
        rule.setContent {
            var wifi by remember {
                mutableStateOf(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI))
            }
            var mobile by remember {
                mutableStateOf(DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE))
            }
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OrderedRoutesSettings(
                        wifiPolicy = wifi,
                        mobilePolicy = mobile,
                        onWifiPolicyChange = { wifi = it },
                        onMobilePolicyChange = { mobile = it },
                        onResetWifiPolicy = {
                            wifi = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI)
                        },
                        onResetMobilePolicy = {
                            mobile = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)
                        },
                        onOpenDiagnostics = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun count(enabled: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithTag("routes.count")
            .assertTextEquals(context.getString(R.string.routes_enabled_count, enabled, 5))
    }

    private fun assertChain(vararg order: RouteKind) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = order.joinToString(" → ") { context.getString(label(it)) }
        rule.onNodeWithTag("routes.chain").assertTextEquals(expected)
    }

    private fun label(kind: RouteKind): Int = when (kind) {
        RouteKind.DIRECT_WS -> R.string.route_policy_direct_ws
        RouteKind.WORKER_WS -> R.string.route_policy_worker_ws
        RouteKind.CF_PROXY_WS -> R.string.route_policy_cf_proxy_ws
        RouteKind.AWG_WARP -> R.string.route_policy_awg_warp
        RouteKind.TCP_FALLBACK -> R.string.route_policy_tcp_fallback
    }
}
