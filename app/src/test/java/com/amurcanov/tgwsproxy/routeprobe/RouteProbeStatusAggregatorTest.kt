package com.amurcanov.tgwsproxy.routeprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProbeStatusAggregatorTest {
    @Test
    fun aggregate_allRequiredOk_returnsOk() {
        val steps = listOf(
            step(RouteProbeStep.DNS_RESOLVE, RouteProbeStatus.OK),
            step(RouteProbeStep.TCP_CONNECT, RouteProbeStatus.OK),
            step(RouteProbeStep.TLS_HANDSHAKE, RouteProbeStatus.OK),
            step(RouteProbeStep.WEBSOCKET_HANDSHAKE, RouteProbeStatus.OK),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(
                RouteProbeStep.DNS_RESOLVE,
                RouteProbeStep.TCP_CONNECT,
                RouteProbeStep.TLS_HANDSHAKE,
                RouteProbeStep.WEBSOCKET_HANDSHAKE,
            ),
        )
        assertEquals(RouteProbeStatus.OK, status)
    }

    @Test
    fun aggregate_requiredTimeout_returnsTimeout() {
        val steps = listOf(
            step(RouteProbeStep.DNS_RESOLVE, RouteProbeStatus.OK),
            step(RouteProbeStep.TCP_CONNECT, RouteProbeStatus.TIMEOUT),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(RouteProbeStep.DNS_RESOLVE, RouteProbeStep.TCP_CONNECT),
        )
        assertEquals(RouteProbeStatus.TIMEOUT, status)
    }

    @Test
    fun aggregate_requiredFail_returnsFail() {
        val steps = listOf(
            step(RouteProbeStep.DNS_RESOLVE, RouteProbeStatus.FAIL),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(RouteProbeStep.DNS_RESOLVE, RouteProbeStep.TCP_CONNECT),
        )
        assertEquals(RouteProbeStatus.FAIL, status)
    }

    @Test
    fun aggregate_mixedRequiredOkAndFail_returnsPartial() {
        val steps = listOf(
            step(RouteProbeStep.DNS_RESOLVE, RouteProbeStatus.OK),
            step(RouteProbeStep.TCP_CONNECT, RouteProbeStatus.FAIL),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(RouteProbeStep.DNS_RESOLVE, RouteProbeStep.TCP_CONNECT),
        )
        assertEquals(RouteProbeStatus.PARTIAL, status)
    }

    @Test
    fun aggregate_optionalFailRequiredOk_returnsPartial() {
        val steps = listOf(
            step(RouteProbeStep.DNS_RESOLVE, RouteProbeStatus.OK),
            step(RouteProbeStep.TCP_CONNECT, RouteProbeStatus.OK),
            step(RouteProbeStep.HTTP_PROBE, RouteProbeStatus.FAIL),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(RouteProbeStep.DNS_RESOLVE, RouteProbeStep.TCP_CONNECT),
        )
        assertEquals(RouteProbeStatus.PARTIAL, status)
    }

    @Test
    fun aggregate_allSkipped_returnsSkipped() {
        val steps = listOf(
            step(RouteProbeStep.TELEGRAM_PROBE, RouteProbeStatus.UNSUPPORTED),
        )
        val status = RouteProbeStatusAggregator.aggregate(
            steps,
            setOf(RouteProbeStep.TELEGRAM_PROBE),
        )
        assertEquals(RouteProbeStatus.SKIPPED, status)
    }

    @Test
    fun primaryErrorCode_mapsTimeout() {
        val steps = listOf(
            step(RouteProbeStep.TCP_CONNECT, RouteProbeStatus.TIMEOUT, RouteProbeErrorCode.TIMEOUT),
        )
        assertEquals(RouteProbeErrorCode.TIMEOUT, RouteProbeStatusAggregator.primaryErrorCode(steps))
    }

    private fun step(
        step: RouteProbeStep,
        status: RouteProbeStatus,
        code: RouteProbeErrorCode = RouteProbeErrorCode.NONE,
    ): RouteProbeStepResult {
        return RouteProbeStepResult(
            step = step,
            status = status,
            error = RouteProbeError(code),
        )
    }
}
