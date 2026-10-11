package com.amurcanov.tgwsproxy.worker

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Bounded, payload-free probe of the transport actually used by the MTProto Worker route.
 * A successful probe establishes Chunk Relay protocol and DO reachability, NOT an MTProto exchange.
 */
internal class ChunkRelayWorkerProbe(
    private val http: ChunkRelayProbeHttp = HttpsChunkRelayProbeHttp(),
    private val nowNs: () -> Long = System::nanoTime,
) {
    fun check(domain: String, destination: String): ChunkRelayProbeResult {
        val started = nowNs()
        val deadline = started + MAX_PROBE_MS * 1_000_000
        val sid = "diag_" + UUID.randomUUID().toString().replace("-", "")
        val query = "sid=$sid&dst=$destination"
        var openAttempted = false
        var openAccepted = false
        var outcome: ChunkRelayProbeResult
        try {
            openAttempted = true
            val opened = request(domain, "/chunk-relay/open?$query", "POST", deadline)
            outcome = classify("open", opened, 204)
            if (outcome.available) {
                openAccepted = true
                // Invalid sequence is deliberate: exercise the /up route without sending MTProto bytes.
                val upload = request(domain, "/chunk-relay/up?$query&seq=0", "POST", deadline)
                outcome = classify("up_validation", upload, 400)
            }
            if (outcome.available) {
                val download = request(domain, "/chunk-relay/down?$query&ack=0&wait=0", "GET", deadline)
                outcome = classify("down_poll", download, 204, 200)
            }
        } catch (_: SocketTimeoutException) {
            outcome = ChunkRelayProbeResult(false, false, "network", null, "timeout")
        } catch (_: Exception) {
            outcome = ChunkRelayProbeResult(false, false, "network", null, "network_error")
        }

        // A timed-out open may still have created a remote session.
        if (openAttempted && (openAccepted || outcome.errorCode == "timeout" || outcome.errorCode == "network_error")) {
            try {
                val closed = request(domain, "/chunk-relay/close?$query", "GET", nowNs() + CLOSE_TIMEOUT_MS * 1_000_000)
                if (outcome.available) {
                    outcome = classify("close", closed, 204)
                }
            } catch (_: Exception) {
                if (outcome.available) {
                    outcome = ChunkRelayProbeResult(true, false, "close", null, "cleanup_failed")
                }
            }
        }
        return outcome.copy(elapsedMs = ((nowNs() - started) / 1_000_000).coerceAtLeast(0))
    }

    private fun request(domain: String, path: String, method: String, deadline: Long): ChunkRelayProbeHttpResponse {
        val remainingMs = ((deadline - nowNs()) / 1_000_000).coerceAtMost(REQUEST_TIMEOUT_MS)
        if (remainingMs <= 0) throw SocketTimeoutException("probe budget exhausted")
        return http.request(domain, path, method, remainingMs.toInt())
    }

    private fun classify(
        stage: String,
        response: ChunkRelayProbeHttpResponse,
        expected: Int,
        alternate: Int = expected,
    ): ChunkRelayProbeResult {
        val compatible = response.revision == REVISION && response.hubRevision == HUB_REVISION
        val failure = when {
            response.status == 404 || response.status == 405 || response.status == 101 -> "incompatible_protocol"
            response.revision.isNullOrBlank() || response.hubRevision.isNullOrBlank() -> "incompatible_protocol"
            !compatible -> "revision_mismatch"
            response.status == 409 -> "sequence_conflict"
            response.status == 410 -> "session_lost"
            response.status >= 500 -> if (response.workerState == "do-quota-exhausted") "do_quota_exhausted" else "worker_server_error"
            response.status != expected && response.status != alternate -> "unexpected_http_status"
            else -> "none"
        }
        return ChunkRelayProbeResult(
            compatible = compatible,
            available = failure == "none",
            stage = stage,
            httpStatus = response.status,
            errorCode = failure,
        )
    }

    private companion object {
        const val REVISION = "chunk-relay-mtproto-v10"
        const val HUB_REVISION = "relay-hub-v1"
        const val MAX_PROBE_MS = 9_000L
        const val REQUEST_TIMEOUT_MS = 2_500L
        const val CLOSE_TIMEOUT_MS = 1_500L
    }
}

internal data class ChunkRelayProbeResult(
    val compatible: Boolean,
    val available: Boolean,
    val stage: String,
    val httpStatus: Int?,
    val errorCode: String,
    val elapsedMs: Long = 0,
) {
    val detail: String
        get() = "transport=chunk_relay check=protocol_and_do compatible=$compatible " +
            "reachable=$available mtproto_bidirectional=unverified " +
            "endpoint=/chunk-relay/$stage http_status=${httpStatus ?: "none"} error=$errorCode"
}

internal data class ChunkRelayProbeHttpResponse(
    val status: Int,
    val revision: String?,
    val hubRevision: String?,
    val workerState: String? = null,
)

internal fun interface ChunkRelayProbeHttp {
    fun request(domain: String, path: String, method: String, timeoutMs: Int): ChunkRelayProbeHttpResponse
}

private class HttpsChunkRelayProbeHttp : ChunkRelayProbeHttp {
    override fun request(domain: String, path: String, method: String, timeoutMs: Int): ChunkRelayProbeHttpResponse {
        require(domain.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid Worker hostname" }
        val connection = URL("https://$domain$path").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Connection", "close")
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.use { }
            }
            val status = connection.responseCode
            return ChunkRelayProbeHttpResponse(
                status = status,
                revision = connection.getHeaderField("X-Tgws-Chunk-Relay-Revision"),
                hubRevision = connection.getHeaderField("X-Tgws-Relay-Hub-Revision"),
                workerState = connection.getHeaderField("X-Tgws-Worker-State"),
            )
        } finally {
            connection.disconnect()
        }
    }
}
