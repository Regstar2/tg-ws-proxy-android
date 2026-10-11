package com.amurcanov.tgwsproxy.worker

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkRelayWorkerProbeTest {
    private val revision = "chunk-relay-mtproto-v10"
    private val hub = "relay-hub-v1"

    private fun response(
        status: Int,
        workerRevision: String? = revision,
        hubRevision: String? = hub,
        state: String? = null,
    ) = ChunkRelayProbeHttpResponse(status, workerRevision, hubRevision, state)

    @Test
    fun checksOpenUpValidationDownAndClosesSessionWithoutPayload() {
        val calls = mutableListOf<Pair<String, String>>()
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, path, method, _ ->
                calls += method to path
                when {
                    path.startsWith("/chunk-relay/open?") -> response(204)
                    path.startsWith("/chunk-relay/up?") -> response(400)
                    path.startsWith("/chunk-relay/down?") -> response(204)
                    path.startsWith("/chunk-relay/close?") -> response(204)
                    else -> error("Unexpected action")
                }
            },
        )
        val result = probe.check("example.workers.dev", "149.154.167.51")
        assertTrue(result.compatible)
        assertTrue(result.available)
        assertTrue(result.detail.contains("mtproto_bidirectional=unverified"))
        assertEquals(listOf("POST", "POST", "GET", "GET"), calls.map { it.first })
        assertEquals(4, calls.size)
        assertTrue(calls[1].second.contains("seq=0"))
        val sids = calls.map { Regex("sid=([^&]+)").find(it.second)!!.groupValues[1] }.distinct()
        assertEquals(1, sids.size)
        assertTrue(calls.all { it.second.startsWith("/chunk-relay/") })
    }

    @Test
    fun legacyWebSocketUpgradeIsIncompatibleWithoutGreenResult() {
        val calls = mutableListOf<String>()
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, path, _, _ ->
                calls += path
                response(101, workerRevision = null, hubRevision = null)
            },
        )
        val result = probe.check("legacy.workers.dev", "149.154.167.51")
        assertFalse(result.compatible)
        assertFalse(result.available)
        assertEquals("incompatible_protocol", result.errorCode)
        assertEquals(1, calls.size)
    }

    @Test
    fun revisionMismatchIsIncompatible() {
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, _, _, _ -> response(204, workerRevision = "chunk-relay-mtproto-v9") },
        )
        val result = probe.check("example.workers.dev", "149.154.167.51")
        assertFalse(result.available)
        assertEquals("revision_mismatch", result.errorCode)
    }

    @Test
    fun missingBindingAndUnexpectedMethodsAreFailures() {
        for (status in listOf(404, 405)) {
            val probe = ChunkRelayWorkerProbe(
                http = ChunkRelayProbeHttp { _, _, _, _ -> response(status, workerRevision = null, hubRevision = null) },
            )
            val result = probe.check("example.workers.dev", "149.154.167.51")
            assertFalse(result.available)
            assertEquals("incompatible_protocol", result.errorCode)
        }
    }

    @Test
    fun sessionAndServerFailuresAreNotAvailable() {
        val errors = mapOf(
            409 to "sequence_conflict",
            410 to "session_lost",
            500 to "worker_server_error",
            502 to "worker_server_error",
            503 to "worker_server_error",
        )
        for ((status, error) in errors) {
            val probe = ChunkRelayWorkerProbe(
                http = ChunkRelayProbeHttp { _, _, _, _ -> response(status) },
            )
            val result = probe.check("example.workers.dev", "149.154.167.51")
            assertTrue(result.compatible)
            assertFalse(result.available)
            assertEquals(error, result.errorCode)
            assertEquals(status, result.httpStatus)
        }
    }

    @Test
    fun durableObjectQuotaIsClassified() {
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, _, _, _ -> response(503, state = "do-quota-exhausted") },
        )
        assertEquals(
            "do_quota_exhausted",
            probe.check("example.workers.dev", "149.154.167.51").errorCode,
        )
    }

    @Test
    fun uploadFailureStillClosesOpenedSession() {
        val calls = mutableListOf<String>()
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, path, _, _ ->
                calls += path
                when {
                    path.startsWith("/chunk-relay/open?") -> response(204)
                    path.startsWith("/chunk-relay/up?") -> response(409)
                    path.startsWith("/chunk-relay/close?") -> response(204)
                    else -> error("Unexpected action")
                }
            },
        )
        val result = probe.check("example.workers.dev", "149.154.167.51")
        assertFalse(result.available)
        assertEquals("sequence_conflict", result.errorCode)
        assertEquals(3, calls.size)
        assertTrue(calls.last().startsWith("/chunk-relay/close?"))
    }

    @Test
    fun networkTimeoutDoesNotReportSuccessAndAttemptsCleanup() {
        val calls = mutableListOf<String>()
        val probe = ChunkRelayWorkerProbe(
            http = ChunkRelayProbeHttp { _, path, _, _ ->
                calls += path
                if (path.startsWith("/chunk-relay/open?")) throw SocketTimeoutException("read timed out")
                response(204)
            },
        )
        val result = probe.check("example.workers.dev", "149.154.167.51")
        assertFalse(result.available)
        assertEquals("timeout", result.errorCode)
        assertEquals(2, calls.size)
        assertTrue(calls[1].startsWith("/chunk-relay/close?"))
    }
}
