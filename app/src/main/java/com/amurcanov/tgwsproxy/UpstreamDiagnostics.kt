package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class UpstreamSweepReport(
    val fileName: String,
    val savedUri: Uri?,
    val successCount: Int,
    val totalCount: Int
)

object UpstreamDiagnostics {
    private const val TAG = "TgWsProxy"
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val HANDSHAKE_TIMEOUT_MS = 3500
    private const val WS_PATH = "/apiws"
    private val ipv4Regex =
        Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    private val dcTcpPorts = listOf(80, 443, 5222)
    private val wsDomains = listOf(
        "kws2.web.telegram.org",
        "kws2-1.web.telegram.org",
        "kws4.web.telegram.org",
        "kws4-1.web.telegram.org",
    )
    private val kwsTransports = listOf(
        DomainTransport(scheme = "ws", port = 80, useTls = false),
        DomainTransport(scheme = "wss", port = 443, useTls = true),
    )
    private val knownTelegramServiceCandidates = listOf(
        "149.154.167.220",
        "149.154.175.50",
        "149.154.175.51",
        "149.154.175.52",
        "149.154.175.53",
        "149.154.175.54",
        "149.154.167.35",
        "149.154.167.36",
        "149.154.167.41",
        "149.154.167.50",
        "149.154.167.51",
        "149.154.167.91",
        "149.154.167.92",
        "149.154.166.121",
        "149.154.166.120",
        "149.154.165.111",
        "149.154.164.250",
        "149.154.162.123",
        "149.154.167.118",
        "149.154.167.151",
        "149.154.167.222",
        "149.154.167.223",
        "91.105.192.100",
        "149.154.175.100",
        "149.154.175.101",
        "149.154.175.102",
        "149.154.171.5",
        "91.108.56.100",
        "91.108.56.101",
        "91.108.56.102",
        "91.108.56.116",
        "91.108.56.126",
        "91.108.56.128",
        "91.108.56.151",
    )
    private val officialCidrSampleCandidates = listOf(
        "91.108.4.100",
        "91.108.8.100",
        "91.108.12.100",
        "91.108.16.100",
        "91.108.20.100",
        "91.105.193.100",
        "149.154.160.100",
        "149.154.161.100",
        "149.154.168.100",
        "149.154.172.100",
        "185.76.151.100",
    )
    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(TrustAllManager), SecureRandom())
        }
    }

    suspend fun runSweep(
        context: Context,
        reportTreeUri: Uri,
        configuredCandidates: List<String>,
        proxyRunning: Boolean,
    ): UpstreamSweepReport = withContext(Dispatchers.IO) {
        val candidates = buildCandidateList(configuredCandidates)
        val startedAt = Date()
        val fileName = "upstream-sweep-${fileStamp(startedAt)}.txt"
        val report = StringBuilder()
        var dcTcpSuccesses = 0
        var kwsSuccesses = 0

        report.appendLine("Telegram WS Proxy upstream sweep")
        report.appendLine("started_at=${humanStamp(startedAt)}")
        report.appendLine("proxy_running=$proxyRunning")
        report.appendLine("candidate_count=${candidates.size}")
        report.appendLine("dc_tcp_ports=${dcTcpPorts.joinToString(",")}")
        report.appendLine("kws_domains=${wsDomains.joinToString(",")}")
        report.appendLine("kws_transports=${kwsTransports.joinToString(",") { "${it.scheme}:${it.port}" }}")
        report.appendLine("connect_timeout_ms=$CONNECT_TIMEOUT_MS handshake_timeout_ms=$HANDSHAKE_TIMEOUT_MS")
        report.appendLine()

        logInfo("UPSTREAM TEST start dc_candidates=${candidates.size} kws_domains=${wsDomains.size}")

        report.appendLine("[dc-ip-tcp-probe]")
        for (candidate in candidates) {
            report.appendLine("[$candidate]")
            for (port in dcTcpPorts) {
                val result = probePlainTcp(candidate, port)
                if (result.success) {
                    dcTcpSuccesses += 1
                }
                report.appendLine("tcp://$candidate:$port ${formatResult(result)}")
                logForResult("UPSTREAM TEST dc ip=$candidate port=$port ${formatResult(result)}", result.success)
            }
            report.appendLine()
        }

        report.appendLine("[kws-domain-probe]")
        for (domain in wsDomains) {
            report.appendLine("[$domain]")
            for (transport in kwsTransports) {
                val result = probeDomainTransport(domain, transport)
                if (result.success) {
                    kwsSuccesses += 1
                }
                report.appendLine("${transport.scheme}://$domain:${transport.port}$WS_PATH ${formatResult(result)}")
                logForResult(
                    "UPSTREAM TEST kws host=$domain transport=${transport.scheme}:${transport.port} ${formatResult(result)}",
                    result.success
                )
            }
            report.appendLine()
        }

        val totalChecks = candidates.size * dcTcpPorts.size + wsDomains.size * kwsTransports.size
        val totalSuccesses = dcTcpSuccesses + kwsSuccesses
        report.appendLine(
            "summary dc_tcp_successes=$dcTcpSuccesses kws_transport_successes=$kwsSuccesses total_successes=$totalSuccesses total_checks=$totalChecks"
        )

        val savedUri = saveReport(context, reportTreeUri, fileName, report.toString())
        if (savedUri != null) {
            logInfo("UPSTREAM TEST report saved file=$fileName")
        } else {
            logWarn("UPSTREAM TEST report save failed file=$fileName")
        }

        UpstreamSweepReport(
            fileName = fileName,
            savedUri = savedUri,
            successCount = totalSuccesses,
            totalCount = totalChecks
        )
    }

    fun buildCandidateList(configuredCandidates: List<String>): List<String> {
        return (configuredCandidates + knownTelegramServiceCandidates + officialCidrSampleCandidates)
            .map { it.trim() }
            .filter { it.matches(ipv4Regex) }
            .distinct()
    }

    private fun probePlainTcp(ip: String, port: Int): ProbeResult {
        val startNs = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            }
            ProbeResult(true, "tcp_open", elapsedMs(startNs), null)
        } catch (e: Exception) {
            ProbeResult(false, classifyException(e), elapsedMs(startNs), describeException(e))
        }
    }

    private fun probeDomainTransport(domain: String, transport: DomainTransport): ProbeResult {
        val startNs = System.nanoTime()
        val resolved = resolveDomainAddresses(domain)
        val candidates = if (resolved.ipv4.isNotEmpty()) resolved.ipv4 else resolved.ipv6
        if (candidates.isEmpty()) {
            return ProbeResult(
                false,
                "dns_resolution_failure",
                elapsedMs(startNs),
                "resolved=${resolved.describe()} no usable addresses"
            )
        }

        var lastError: ProbeResult? = null
        for (address in candidates) {
            val result = if (transport.useTls) {
                probeWssAddress(domain, transport.port, address, resolved, startNs)
            } else {
                probeWsAddress(domain, transport.port, address, resolved, startNs)
            }
            if (result.success) {
                return result
            }
            lastError = result
        }

        return lastError ?: ProbeResult(
            false,
            "unexpected_failure",
            elapsedMs(startNs),
            "resolved=${resolved.describe()} no attempts completed"
        )
    }

    private fun probeWsAddress(
        domain: String,
        port: Int,
        address: InetAddress,
        resolved: ResolvedAddressSet,
        startNs: Long,
    ): ProbeResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                performWsUpgrade(
                    socket = socket,
                    domain = domain,
                    startNs = startNs,
                    detailPrefix = "resolved=${resolved.describe()} selected=${address.hostAddress}"
                )
            }
        } catch (e: Exception) {
            ProbeResult(
                false,
                classifyException(e),
                elapsedMs(startNs),
                "resolved=${resolved.describe()} selected=${address.hostAddress} ${describeException(e)}"
            )
        }
    }

    private fun probeWssAddress(
        domain: String,
        port: Int,
        address: InetAddress,
        resolved: ResolvedAddressSet,
        startNs: Long,
    ): ProbeResult {
        return try {
            Socket().use { rawSocket ->
                rawSocket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

                val sslSocket = sslContext.socketFactory.createSocket(rawSocket, domain, port, true) as SSLSocket
                sslSocket.use { tls ->
                    tls.soTimeout = HANDSHAKE_TIMEOUT_MS
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(domain))
                    tls.sslParameters = params
                    tls.startHandshake()
                    performWsUpgrade(
                        socket = tls,
                        domain = domain,
                        startNs = startNs,
                        detailPrefix = "resolved=${resolved.describe()} selected=${address.hostAddress}"
                    )
                }
            }
        } catch (e: Exception) {
            ProbeResult(
                false,
                classifyException(e),
                elapsedMs(startNs),
                "resolved=${resolved.describe()} selected=${address.hostAddress} ${describeException(e)}"
            )
        }
    }

    private fun performWsUpgrade(
        socket: Socket,
        domain: String,
        startNs: Long,
        detailPrefix: String,
    ): ProbeResult {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
        val request = buildString {
            append("GET $WS_PATH HTTP/1.1\r\n")
            append("Host: $domain\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ${randomWsKey()}\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: binary\r\n")
            append("Origin: https://web.telegram.org\r\n")
            append("User-Agent: Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36\r\n")
            append("\r\n")
        }
        writer.write(request)
        writer.flush()

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val statusLine = reader.readLine()
            ?: return ProbeResult(false, "empty_http_response", elapsedMs(startNs), "$detailPrefix no status line")

        var headerCount = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                break
            }
            headerCount += 1
            if (headerCount > 100) {
                return ProbeResult(false, "too_many_headers", elapsedMs(startNs), "$detailPrefix header_count=$headerCount")
            }
        }

        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        return if (statusCode == 101) {
            ProbeResult(true, "ws_upgrade_101", elapsedMs(startNs), "$detailPrefix $statusLine")
        } else {
            ProbeResult(false, "ws_status_${statusCode ?: 0}", elapsedMs(startNs), "$detailPrefix $statusLine")
        }
    }

    private data class ResolvedAddressSet(
        val ipv4: List<InetAddress>,
        val ipv6: List<InetAddress>,
    ) {
        fun describe(): String {
            val parts = mutableListOf<String>()
            if (ipv4.isNotEmpty()) {
                parts += "ipv4=${ipv4.joinToString(",") { it.hostAddress ?: "unknown" }}"
            }
            if (ipv6.isNotEmpty()) {
                parts += "ipv6=${ipv6.joinToString(",") { it.hostAddress ?: "unknown" }}"
            }
            return if (parts.isEmpty()) "none" else parts.joinToString(" ")
        }
    }

    private data class DomainTransport(
        val scheme: String,
        val port: Int,
        val useTls: Boolean,
    )

    private data class ProbeResult(
        val success: Boolean,
        val stage: String,
        val elapsedMs: Long,
        val detail: String?,
    )

    private fun resolveDomainAddresses(domain: String): ResolvedAddressSet {
        return try {
            val all = InetAddress.getAllByName(domain).toList()
            val ipv4 = all.filter { (it.address?.size ?: 0) == 4 }
            val ipv6 = all.filter { (it.address?.size ?: 0) == 16 }
            ResolvedAddressSet(ipv4 = ipv4, ipv6 = ipv6)
        } catch (_: Exception) {
            ResolvedAddressSet(emptyList(), emptyList())
        }
    }

    private fun saveReport(context: Context, treeUri: Uri, fileName: String, text: String): Uri? {
        return try {
            ArtifactStore.saveTextFile(
                context = context,
                treeUri = treeUri,
                subdirectoryName = ArtifactStore.UPSTREAM_REPORTS_DIR,
                fileName = fileName,
                text = text
            )
        } catch (e: Exception) {
            logWarn("UPSTREAM TEST save error=${describeException(e)}")
            null
        }
    }

    private fun randomWsKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun formatResult(result: ProbeResult): String {
        return if (result.success) {
            "OK stage=${result.stage} elapsed_ms=${result.elapsedMs}${result.detail?.let { " detail=$it" } ?: ""}"
        } else {
            "FAIL stage=${result.stage} elapsed_ms=${result.elapsedMs}${result.detail?.let { " detail=$it" } ?: ""}"
        }
    }

    private fun describeException(error: Exception): String {
        val message = error.message?.replace("\n", " ")?.trim()
        return "${error.javaClass.simpleName}${message?.let { ": $it" } ?: ""}"
    }

    private fun classifyException(error: Exception): String {
        return when (error) {
            is UnknownHostException -> "dns_resolution_failure"
            is SocketTimeoutException -> "tcp_dial_timeout"
            is java.net.ConnectException -> {
                val text = error.message?.lowercase(Locale.US).orEmpty()
                when {
                    "unreachable" in text -> "network_unreachable"
                    "refused" in text -> "connection_refused"
                    else -> "connect_failure"
                }
            }
            is javax.net.ssl.SSLException -> "tls_handshake_failure"
            is IOException -> "io_failure"
            else -> "unexpected_failure"
        }
    }

    private fun elapsedMs(startNs: Long): Long {
        return (System.nanoTime() - startNs) / 1_000_000
    }

    private fun fileStamp(date: Date): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date)
    }

    private fun humanStamp(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
    }

    private fun logInfo(message: String) {
        Log.i(TAG, message)
    }

    private fun logWarn(message: String) {
        Log.w(TAG, message)
    }

    private fun logForResult(message: String, success: Boolean) {
        if (success) {
            logInfo(message)
        } else {
            logWarn(message)
        }
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
