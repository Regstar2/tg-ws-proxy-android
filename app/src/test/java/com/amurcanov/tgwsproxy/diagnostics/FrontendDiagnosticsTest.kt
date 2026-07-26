package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.LocalProxyFrontendType
import com.amurcanov.tgwsproxy.MtProtoNativeStatus
import com.amurcanov.tgwsproxy.MtProtoProxyConfig
import com.amurcanov.tgwsproxy.ProxyRuntimeMetrics
import com.amurcanov.tgwsproxy.ProxyServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendDiagnosticsTest {
    @Test
    fun unsupportedOutbound_doesNotClaimRouteReady() {
        val snapshot = mapStatus(
            MtProtoNativeStatus(
                status = "LISTENING_LOCAL_ONLY",
                outbound = "OUTBOUND_UNSUPPORTED",
                selectedBackend = "none",
            ),
        )

        assertEquals(DiagnosticSupportStatus.UNSUPPORTED, snapshot.mtProtoRouteSupport)
        assertFalse(snapshot.mtProtoRouteSupport == DiagnosticSupportStatus.READY)
    }

    @Test
    fun partialBackend_isReportedAsLimitedNotReady() {
        val snapshot = mapStatus(
            MtProtoNativeStatus(
                status = "LISTENING_ROUTE_READY",
                outbound = "MTPROTO_ROUTE_DIRECT_READY",
                selectedBackend = "direct_tcp",
                actualBackend = "direct_tcp",
                routeReason = "connected",
            ),
        )

        assertEquals(DiagnosticSupportStatus.LIMITED, snapshot.mtProtoRouteSupport)
        val report = DiagnosticReportGenerator.formatFrontendDiagnosticsSections(snapshot)
        assertTrue(report.contains("MTProto route support: LIMITED"))
        assertFalse(report.contains("MTProto route support: READY"))
    }

    @Test
    fun mtProtoFlowsealBackends_areReportedAsLimited() {
        listOf("direct_ws", "cf_proxy_ws", "cf_worker_ws", "direct_tcp").forEach { backend ->
            val snapshot = mapStatus(
                MtProtoNativeStatus(
                    status = "LISTENING_ROUTE_READY",
                    outbound = "MTPROTO_ROUTE_CHAIN_READY",
                    selectedBackend = backend,
                    actualBackend = backend,
                    routeReason = "connected",
                ),
            )

            assertEquals(backend, DiagnosticSupportStatus.LIMITED, snapshot.mtProtoRouteSupport)
        }
    }

    @Test
    fun reportContainsFrontendKindsAndRuntimeTruth() {
        val report = DiagnosticReportGenerator.formatFrontendDiagnosticsSections(
            mapStatus(
                MtProtoNativeStatus(
                    status = "LISTENING_ROUTE_READY",
                    outbound = "MTPROTO_ROUTE_WS_READY",
                    selectedBackend = "cf_worker_ws",
                    actualBackend = "cf_worker_ws",
                    routeReason = "connected",
                ),
            ),
        )

        assertTrue(report.contains("[SOCKS5 / WebSocket frontend]"))
        assertTrue(report.contains("Frontend kind: SOCKS5"))
        assertTrue(report.contains("Frontend kind: MTPROTO"))
        assertTrue(report.contains("[Runtime route truth]"))
    }

    @Test
    fun reportContainsFakeTlsDiagnostics() {
        val snapshot = mapStatus(
            MtProtoNativeStatus(
                status = "LISTENING_ROUTE_READY",
                outbound = "MTPROTO_ROUTE_CHAIN_READY",
                selectedBackend = "cf_proxy_ws",
                actualBackend = "cf_proxy_ws",
                fakeTls = true,
                maskingPassthrough = true,
                fakeTlsAccepted = 2,
                fakeTlsRejected = 1,
                fakeTlsRedirected = 3,
                fakeTlsProbe = 4,
                fakeTlsPassthrough = 5,
                fakeTlsLastError = "fake tls client hello verification failed",
            ),
        )

        val report = DiagnosticReportGenerator.formatFrontendDiagnosticsSections(snapshot)

        assertTrue(report.contains("Fake TLS enabled: yes"))
        assertTrue(report.contains("Fake TLS masking passthrough: yes"))
        assertTrue(report.contains("Fake TLS accepted: 2"))
        assertTrue(report.contains("Fake TLS last error: fake tls client hello verification failed"))
    }

    private fun mapStatus(status: MtProtoNativeStatus): FrontendDiagnosticsSnapshot {
        return FrontendDiagnosticsMapper.map(
            FrontendDiagnosticsInput(
                configuredFrontend = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
                serviceStatus = ProxyServiceStatus.RUNNING,
                socks5Runtime = ProxyRuntimeMetrics(),
                mtProtoConfig = MtProtoProxyConfig(
                    host = "127.0.0.1",
                    port = 1443,
                    secret = "0123456789abcdef0123456789abcdef",
                    enabled = true,
                    experimentalAcknowledged = true,
                ),
                mtProtoNativeStatus = status,
                workerPoolSnapshot = null,
            ),
        )
    }
}
