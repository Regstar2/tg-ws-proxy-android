package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeResult
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStep
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStepResult
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProbeUiMapperTest {
    @Test
    fun toUiModel_mapsFields() {
        val result = RouteProbeResult(
            target = RouteProbeTarget.WORKER_WEBSOCKET,
            status = RouteProbeStatus.TIMEOUT,
            steps = listOf(
                RouteProbeStepResult(
                    step = RouteProbeStep.WEBSOCKET_HANDSHAKE,
                    status = RouteProbeStatus.TIMEOUT,
                    latencyMs = 5000,
                    error = com.amurcanov.tgwsproxy.routeprobe.RouteProbeError(RouteProbeErrorCode.TIMEOUT),
                    debugDetail = "handshake timeout",
                ),
            ),
            latencyMs = 5021,
            startedAtMs = 1000,
            finishedAtMs = 6021,
            errorCode = RouteProbeErrorCode.TIMEOUT,
            errorMessageForDebug = "handshake timeout",
        )
        val ui = RouteProbeUiMapper.toUiModel(result)
        assertEquals(RouteProbeTarget.WORKER_WEBSOCKET, ui.target)
        assertEquals(RouteProbeStatus.TIMEOUT, ui.status)
        assertEquals(5021L, ui.latencyMs)
        assertEquals(RouteProbeErrorCode.TIMEOUT, ui.errorCode)
        assertTrue(ui.shortDetails.contains("timeout"))
    }

    @Test
    fun statusLabelRes_unsupported() {
        assertEquals(
            R.string.diagnostics_status_unsupported,
            RouteProbeUiMapper.statusLabelRes(RouteProbeStatus.UNSUPPORTED),
        )
    }

    @Test
    fun targetLabelRes_direct() {
        assertEquals(
            R.string.diagnostics_target_direct_websocket,
            RouteProbeUiMapper.targetLabelRes(RouteProbeTarget.DIRECT_WEBSOCKET),
        )
    }
}
