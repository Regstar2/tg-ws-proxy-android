package com.amurcanov.tgwsproxy.routeprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.amurcanov.tgwsproxy.NetworkProfileProvider
import com.amurcanov.tgwsproxy.UpstreamDiagnosticsSsl
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

internal class RouteProbeNetworkSteps(
    private val config: RouteProbeConfig,
) {
    fun probeCurrentNetwork(context: Context): List<RouteProbeStepResult> {
        val started = System.nanoTime()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (active == null || caps == null) {
            return listOf(
                stepFail(
                    RouteProbeStep.ROUTE_BINDING,
                    RouteProbeErrorCode.NETWORK_UNAVAILABLE,
                    "no_active_network",
                    started,
                ),
            )
        }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val profile = NetworkProfileProvider.current(context)
        val bindingOk = hasInternet
        val steps = mutableListOf<RouteProbeStepResult>()
        steps += if (bindingOk) {
            stepOk(RouteProbeStep.ROUTE_BINDING, started, "type=${profile.type.prefValue}")
        } else {
            stepFail(
                RouteProbeStep.ROUTE_BINDING,
                RouteProbeErrorCode.NETWORK_UNAVAILABLE,
                "no_internet_capability",
                started,
            )
        }
        if (vpn) {
            steps += stepFail(
                RouteProbeStep.ROUTE_BINDING,
                RouteProbeErrorCode.VPN_DETECTED,
                "vpn_transport_detected",
                started,
            )
        }
        return steps
    }

    fun probeDns(host: String): RouteProbeStepResult {
        val started = System.nanoTime()
        return try {
            val addresses = java.net.InetAddress.getAllByName(host)
            if (addresses.isEmpty()) {
                stepFail(RouteProbeStep.DNS_RESOLVE, RouteProbeErrorCode.DNS_FAILED, "empty_result", started)
            } else {
                val ipv4 = addresses.count { it.address.size == 4 }
                stepOk(RouteProbeStep.DNS_RESOLVE, started, "resolved=$ipv4 ipv4")
            }
        } catch (e: Exception) {
            stepFail(RouteProbeStep.DNS_RESOLVE, RouteProbeErrorCode.DNS_FAILED, e.message ?: "dns_error", started)
        }
    }

    fun probeTcp(host: String, port: Int): RouteProbeStepResult {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), config.connectTimeoutMs.toInt())
            }
            stepOk(RouteProbeStep.TCP_CONNECT, started, "$host:$port")
        } catch (e: java.net.SocketTimeoutException) {
            stepTimeout(RouteProbeStep.TCP_CONNECT, started)
        } catch (e: Exception) {
            stepFail(
                RouteProbeStep.TCP_CONNECT,
                RouteProbeErrorCode.TCP_CONNECT_FAILED,
                e.message ?: "tcp_error",
                started,
            )
        }
    }

    fun probeTls(sniHost: String, dialHost: String, port: Int = 443): RouteProbeStepResult {
        val started = System.nanoTime()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(dialHost, port), config.connectTimeoutMs.toInt())
                raw.soTimeout = config.readTimeoutMs.toInt()
                val ssl = UpstreamDiagnosticsSsl.sslContext.socketFactory
                    .createSocket(raw, sniHost, port, true) as SSLSocket
                ssl.use { tls ->
                    tls.soTimeout = config.readTimeoutMs.toInt()
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    tls.sslParameters = params
                    tls.startHandshake()
                }
            }
            stepOk(RouteProbeStep.TLS_HANDSHAKE, started, sniHost)
        } catch (e: java.net.SocketTimeoutException) {
            stepTimeout(RouteProbeStep.TLS_HANDSHAKE, started)
        } catch (e: Exception) {
            stepFail(
                RouteProbeStep.TLS_HANDSHAKE,
                RouteProbeErrorCode.TLS_HANDSHAKE_FAILED,
                e.message ?: "tls_error",
                started,
            )
        }
    }

    fun probeHttpHead(sniHost: String, dialHost: String, path: String = "/"): RouteProbeStepResult {
        val started = System.nanoTime()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(dialHost, 443), config.connectTimeoutMs.toInt())
                raw.soTimeout = config.readTimeoutMs.toInt()
                val ssl = UpstreamDiagnosticsSsl.sslContext.socketFactory
                    .createSocket(raw, sniHost, 443, true) as SSLSocket
                ssl.use { tls ->
                    tls.soTimeout = config.readTimeoutMs.toInt()
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    tls.sslParameters = params
                    tls.startHandshake()
                    val writer = BufferedWriter(
                        OutputStreamWriter(tls.getOutputStream(), StandardCharsets.US_ASCII),
                    )
                    writer.write("HEAD $path HTTP/1.1\r\nHost: $sniHost\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    val reader = BufferedReader(
                        InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII),
                    )
                    val status = reader.readLine().orEmpty()
                    val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                    if (code != null && code in 200..399) {
                        stepOk(RouteProbeStep.HTTP_PROBE, started, status)
                    } else {
                        stepFail(
                            RouteProbeStep.HTTP_PROBE,
                            RouteProbeErrorCode.HTTP_STATUS_ERROR,
                            status,
                            started,
                        )
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            stepTimeout(RouteProbeStep.HTTP_PROBE, started)
        } catch (e: Exception) {
            stepFail(
                RouteProbeStep.HTTP_PROBE,
                RouteProbeErrorCode.HTTP_STATUS_ERROR,
                e.message ?: "http_error",
                started,
            )
        }
    }

    fun probeWebSocket(sniHost: String, dialHost: String, path: String): RouteProbeStepResult {
        val started = System.nanoTime()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(dialHost, 443), config.connectTimeoutMs.toInt())
                raw.soTimeout = config.readTimeoutMs.toInt()
                val ssl = UpstreamDiagnosticsSsl.sslContext.socketFactory
                    .createSocket(raw, sniHost, 443, true) as SSLSocket
                ssl.use { tls ->
                    tls.soTimeout = config.readTimeoutMs.toInt()
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    tls.sslParameters = params
                    tls.startHandshake()
                    val upgraded = performWsUpgrade(tls, sniHost, path)
                    if (upgraded.success) {
                        stepOk(RouteProbeStep.WEBSOCKET_HANDSHAKE, started, upgraded.detail)
                    } else {
                        stepFail(
                            RouteProbeStep.WEBSOCKET_HANDSHAKE,
                            RouteProbeErrorCode.WEBSOCKET_HANDSHAKE_FAILED,
                            upgraded.detail,
                            started,
                        )
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            stepTimeout(RouteProbeStep.WEBSOCKET_HANDSHAKE, started)
        } catch (e: Exception) {
            stepFail(
                RouteProbeStep.WEBSOCKET_HANDSHAKE,
                RouteProbeErrorCode.WEBSOCKET_HANDSHAKE_FAILED,
                e.message ?: "ws_error",
                started,
            )
        }
    }

    fun probeIpv4Connectivity(): RouteProbeStepResult {
        if (!config.allowIpv4) {
            return RouteProbeStepResult(
                step = RouteProbeStep.TCP_CONNECT,
                status = RouteProbeStatus.SKIPPED,
                debugDetail = "ipv4_disabled",
            )
        }
        return probeTcp("1.1.1.1", 443)
    }

    fun probeIpv6Connectivity(): RouteProbeStepResult {
        return RouteProbeStepResult(
            step = RouteProbeStep.TCP_CONNECT,
            status = RouteProbeStatus.UNSUPPORTED,
            error = RouteProbeError(RouteProbeErrorCode.UNSUPPORTED_TARGET, "ipv6_probe_not_implemented"),
        )
    }

    private data class WsUpgrade(val success: Boolean, val detail: String)

    private fun performWsUpgrade(socket: Socket, host: String, path: String): WsUpgrade {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        writer.write(
            buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("Origin: https://web.telegram.org\r\n")
                append("\r\n")
            },
        )
        writer.flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val status = reader.readLine() ?: return WsUpgrade(false, "empty_http")
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        return if (code == 101) WsUpgrade(true, status) else WsUpgrade(false, status)
    }

    private fun stepOk(step: RouteProbeStep, startedNs: Long, detail: String): RouteProbeStepResult {
        return RouteProbeStepResult(
            step = step,
            status = RouteProbeStatus.OK,
            latencyMs = elapsedMs(startedNs),
            debugDetail = detail,
        )
    }

    private fun stepFail(
        step: RouteProbeStep,
        code: RouteProbeErrorCode,
        detail: String,
        startedNs: Long,
    ): RouteProbeStepResult {
        return RouteProbeStepResult(
            step = step,
            status = RouteProbeStatus.FAIL,
            latencyMs = elapsedMs(startedNs),
            error = RouteProbeError(code, detail),
            debugDetail = detail,
        )
    }

    private fun stepTimeout(step: RouteProbeStep, startedNs: Long): RouteProbeStepResult {
        return RouteProbeStepResult(
            step = step,
            status = RouteProbeStatus.TIMEOUT,
            latencyMs = elapsedMs(startedNs),
            error = RouteProbeError(RouteProbeErrorCode.TIMEOUT),
        )
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
}
