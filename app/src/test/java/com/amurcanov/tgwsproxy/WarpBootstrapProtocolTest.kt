package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpBootstrapProtocolTest {
    @Test
    fun compatibleHealthResponseIsAccepted() {
        val result = WarpBootstrapProtocol.validateHealthResponse(
            httpStatus = 200,
            headerRevision = "warp-bootstrap-v1",
            bodyText = """{"service":"warp-bootstrap","revision":"warp-bootstrap-v1"}""",
        )

        assertTrue(result.ok)
        assertEquals(null, result.errorCode)
    }

    @Test
    fun healthRejectsWrongServiceOrRevision() {
        val wrongService = WarpBootstrapProtocol.validateHealthResponse(
            httpStatus = 200,
            headerRevision = "warp-bootstrap-v1",
            bodyText = """{"service":"other","revision":"warp-bootstrap-v1"}""",
        )
        val wrongRevision = WarpBootstrapProtocol.validateHealthResponse(
            httpStatus = 200,
            headerRevision = "warp-bootstrap-v2",
            bodyText = """{"service":"warp-bootstrap","revision":"warp-bootstrap-v2"}""",
        )

        assertFalse(wrongService.ok)
        assertEquals("health_service_mismatch", wrongService.errorCode)
        assertFalse(wrongRevision.ok)
        assertEquals("health_revision_unsupported", wrongRevision.errorCode)
    }

    @Test
    fun workerUrlAcceptsOnlyHttpsOriginWithoutCredentialsOrPath() {
        assertEquals(
            "https://example.workers.dev",
            ProvisioningWorkerUrlValidator.normalize("https://EXAMPLE.workers.dev/"),
        )
        assertRejected("http://example.workers.dev", "worker_url_https_required")
        assertRejected("https://user:pass@example.workers.dev", "worker_url_credentials_forbidden")
        assertRejected("https://example.workers.dev/proxy", "worker_url_path_forbidden")
        assertRejected("https://example.workers.dev?x=1", "worker_url_query_forbidden")
    }

    private fun assertRejected(url: String, expectedCode: String) {
        val error = runCatching { ProvisioningWorkerUrlValidator.normalize(url) }.exceptionOrNull()
        assertTrue(error is WarpProvisioningBootstrapException)
        assertEquals(expectedCode, (error as WarpProvisioningBootstrapException).code)
    }
}
