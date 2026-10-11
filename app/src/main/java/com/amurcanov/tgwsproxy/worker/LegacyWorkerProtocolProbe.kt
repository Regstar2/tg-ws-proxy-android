package com.amurcanov.tgwsproxy.worker

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Bounded, payload-free check of the upstream Flowseal /apiws handshake.
 * HTTP 101 + a valid RFC 6455 accept only confirm protocol compatibility;
 * they DO NOT confirm full-duplex MTProto or media stability.
 */
internal class LegacyWorkerProtocolProbe {
    fun check(domain: String, destination: String): LegacyWorkerProbeResult {
        val started = System.nanoTime()
        try {
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            Socket().use { raw ->
                raw.connect(InetSocketAddress(domain, 443), 5_000)
                raw.soTimeout = 5_000
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(raw, domain, 443, true) as SSLSocket
                tls.use { socket ->
                    socket.soTimeout = 5_000
                    val params = socket.sslParameters
                    params.serverNames = listOf(SNIHostName(domain))
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    socket.sslParameters = params
                    socket.startHandshake()
                    val request = buildString {
                        append("GET /apiws?dst=").append(destination).append(" HTTP/1.1\r\n")
                        append("Host: ").append(domain).append("\r\n")
                        append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                        append("Sec-WebSocket-Version: 13\r\n\r\n")
                    }
                    socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
                    socket.outputStream.flush()
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII))
                    val lines = mutableListOf<String>()
                    var bytesRead = 0
                    repeat(40) {
                        val line = reader.readLine() ?: throw IllegalStateException("incomplete_upgrade")
                        bytesRead += line.length + 2
                        if (bytesRead > 8_192) throw IllegalStateException("headers_too_large")
                        if (line.isEmpty()) {
                            val ok = verifyUpgrade(lines, key)
                            return LegacyWorkerProbeResult(
                                compatible = ok,
                                elapsedMs = elapsedMs(started),
                                errorCode = if (ok) "none" else "incompatible_protocol",
                                detail = "transport=legacy_websocket endpoint=/apiws check=websocket_upgrade " +
                                    "compatible=$ok mtproto_bidirectional=unverified",
                            )
                        }
                        lines += line
                    }
                    return LegacyWorkerProbeResult(false, elapsedMs(started), "headers_too_large", "legacy_ws_headers_too_large")
                }
            }
        } catch (e: SocketTimeoutException) {
            return LegacyWorkerProbeResult(false, elapsedMs(started), "timeout", "legacy_ws_timeout")
        } catch (e: Exception) {
            return LegacyWorkerProbeResult(
                false, elapsedMs(started), "handshake_failed",
                "transport=legacy_websocket check=websocket_upgrade error=${e.javaClass.simpleName}",
            )
        }
    }

    companion object {
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        internal fun verifyUpgrade(lines: List<String>, key: String): Boolean {
            if (lines.isEmpty() || lines.first().split(' ').getOrNull(1) != "101") return false
            val headers = lines.drop(1).mapNotNull {
                val index = it.indexOf(':')
                if (index <= 0) null else it.substring(0, index).trim().lowercase() to it.substring(index + 1).trim()
            }.toMap()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    (key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII),
                ),
            )
            return headers["sec-websocket-accept"] == accept &&
                headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
                headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true
        }

        private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
    }
}

internal data class LegacyWorkerProbeResult(
    val compatible: Boolean,
    val elapsedMs: Long,
    val errorCode: String,
    val detail: String,
)
