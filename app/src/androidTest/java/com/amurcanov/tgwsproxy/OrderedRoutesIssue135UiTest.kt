package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.Column
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderedRoutesIssue135UiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allFiveRoutesShareOneCompactList() {
        render()
        compose.onNodeWithTag("routes.list").assertExists()
        RouteKind.entries.forEach { route ->
            compose.onNodeWithTag("routes.row.${route.prefValue}").assertExists()
        }
    }

    @Test fun wifiAndMobileTabsAreIndependent() {
        render()
        assertCount(3)
        compose.onNodeWithTag("routes.tab.mobile").performClick()
        assertCount(1)
        compose.onNodeWithTag("routes.toggle.awg_warp").performScrollTo().performClick()
        assertCount(2)
        compose.onNodeWithTag("routes.tab.wifi").performClick()
        assertCount(3)
    }

    @Test fun toggleOffKeepsRowPositionAndOnRestoresOriginalPriority() {
        render()
        val before = compose.onNodeWithTag("routes.row.cf_proxy_ws")
            .fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        assertCount(2)
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOff()
        assertChain(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
        val after = compose.onNodeWithTag("routes.row.cf_proxy_ws")
            .fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertEquals(before, after, 1f)
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        assertCount(3)
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOn()
        assertChain(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
    }

    @Test fun gripDragMovesAcrossTwoRowsInOneGesture() {
        render()
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("routes.drag.cf_proxy_ws").performTouchInput {
            down(center)
            moveBy(Offset(0f, 85f * density * 4.2f))
            up()
        }
        assertChain(RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK, RouteKind.CF_PROXY_WS)
    }

    @Test fun arrowsUpdateChainWithoutApplyButton() {
        render()
        compose.onNodeWithTag("routes.down.cf_proxy_ws").performClick()
        assertChain(RouteKind.DIRECT_WS, RouteKind.CF_PROXY_WS, RouteKind.TCP_FALLBACK)
        compose.onNodeWithTag("routes.up.cf_proxy_ws").performClick()
        assertChain(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
    }

    @Test fun lastActiveCannotBeDisabled() {
        render()
        compose.onNodeWithTag("routes.tab.mobile").performClick()
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        assertCount(1)
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").assertIsOn()
    }

    @Test fun resetOnlySelectedProfile() {
        render()
        compose.onNodeWithTag("routes.toggle.cf_proxy_ws").performClick()
        assertCount(2)
        compose.onNodeWithTag("routes.reset").performScrollTo().performClick()
        compose.onNodeWithTag("routes.reset.confirm").performClick()
        assertChain(RouteKind.CF_PROXY_WS)
        compose.onNodeWithTag("routes.tab.mobile").performClick()
        assertCount(1)
    }

    @Test fun defaultResetKeepsOnlyCloudflareProxyInBothProfiles() {
        render()
        compose.onNodeWithTag("routes.reset").performScrollTo().performClick()
        compose.onNodeWithTag("routes.reset.confirm").performClick()
        assertChain(RouteKind.CF_PROXY_WS)
        compose.onNodeWithTag("routes.tab.mobile").performClick()
        assertChain(RouteKind.CF_PROXY_WS)
    }

    private fun render() {
        compose.setContent {
            var wifi by remember {
                mutableStateOf(DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI).copy(
                    activeRoutes = listOf(RouteKind.CF_PROXY_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
                    displayOrder = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
                        RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK),
                ))
            }
            var mobile by remember { mutableStateOf(DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE)) }
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OrderedRoutesSettings(
                        wifiPolicy = wifi,
                        mobilePolicy = mobile,
                        onWifiPolicyChange = { wifi = it },
                        onMobilePolicyChange = { mobile = it },
                        onResetWifiPolicy = { wifi = DefaultNetworkRoutePolicies.forType(NetworkProfileType.WIFI) },
                        onResetMobilePolicy = { mobile = DefaultNetworkRoutePolicies.forType(NetworkProfileType.MOBILE) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCount(enabled: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("routes.count")
            .assertTextEquals(context.getString(R.string.routes_enabled_counter, enabled, 5))
    }

    private fun assertChain(vararg routes: RouteKind) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = routes.joinToString("  →  ") { context.getString(it.priorityLabel()) }
        compose.onNodeWithTag("routes.chain").assertTextEquals(expected)
    }
}
