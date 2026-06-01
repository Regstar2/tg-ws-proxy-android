package com.amurcanov.tgwsproxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class RouteProbeResult(
    val route: String,
    val dc: Int,
    val success: Boolean,
    val stage: String,
    val elapsedMs: Long,
    val detail: String,
)

data class ConnectionProbeReport(
    val results: List<RouteProbeResult>,
) {
    val successCount: Int get() = results.count { it.success }
    val totalCount: Int get() = results.size
}

object ConnectionDiagnostics {
    private const val TAG = "TgWsProxy"
    private const val CONNECT_MS = 3500
    private const val HANDSHAKE_MS = 4500
    private val testDcs = listOf(1, 2, 3, 4, 5, 203)

    suspend fun probeWorker(domainRaw: String): ConnectionProbeReport = withContext(Dispatchers.IO) {
        val domain = WorkerDomain.normalize(domainRaw)
        if (domain.isBlank()) {
            return@withContext ConnectionProbeReport(
                listOf(
                    RouteProbeResult(
                        route = "worker",
                        dc = 0,
                        success = false,
                        stage = "not_configured",
                        elapsedMs = 0,
                        detail = "worker domain empty",
                    )
                )
            )
        }
        val results = testDcs.map { dc ->
            probeWorkerDc(domain, dc)
        }
        ConnectionProbeReport(results)
    }

    suspend fun probeCfProxy(
        cfDomain: String,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): ConnectionProbeReport {
        return probeCfProxy(listOf(cfDomain), cachedUpstreamDomains)
    }

    suspend fun probeCfProxy(
        manualDomainsRaw: List<String>,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): ConnectionProbeReport = withContext(Dispatchers.IO) {
        val domains = cfProxyProbeDomains(manualDomainsRaw, cachedUpstreamDomains)
        if (domains.isEmpty()) {
            return@withContext ConnectionProbeReport(emptyList())
        }
        var lastReport: ConnectionProbeReport? = null
        for (domain in domains) {
            val results = testDcs.map { dc ->
                val wsDc = ConnectionRuntimeConfig.effectiveWsHostDc(dc)
                val host = "kws$wsDc.$domain"
                probeWss(host, host, "/apiws", "cf_proxy", dc)
            }
            val report = ConnectionProbeReport(results)
            lastReport = report
            if (report.successCount > 0) {
                return@withContext report
            }
        }
        lastReport ?: ConnectionProbeReport(emptyList())
    }

    private fun cfProxyProbeDomains(
        manualDomainsRaw: List<String>,
        cachedUpstreamDomains: List<String>,
    ): List<String> {
        val manual = CfManualDomainList.normalize(manualDomainsRaw)
        return buildList {
            manual.take(2).forEach { add(it) }
            cachedUpstreamDomains
                .mapNotNull(CfDomain::normalizeOrNull)
                .filterNot { it in manual }
                .take(2)
                .forEach { add(it) }
            val cached = cachedUpstreamDomains.mapNotNull(CfDomain::normalizeOrNull).toSet()
            CfDomain.builtInDomains
                .filter { it !in manual && it !in cached }
                .take(2)
                .forEach { add(it) }
        }.distinct()
    }

    suspend fun probeCfPool(
        manualDomainRaw: String,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): CfDomainProbeReport {
        return probeCfPool(listOf(manualDomainRaw), cachedUpstreamDomains)
    }

    suspend fun probeCfPool(
        manualDomainsRaw: List<String>,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): CfDomainProbeReport = withContext(Dispatchers.IO) {
        val checkedAt = System.currentTimeMillis()
        val manual = CfManualDomainList.normalize(manualDomainsRaw)
        val domainsToCheck = buildList {
            manual
                .take(2)
                .forEach { add(it to CfDomainSource.MANUAL) }
            cachedUpstreamDomains
                .mapNotNull(CfDomain::normalizeOrNull)
                .filterNot { it in manual }
                .take(2)
                .forEach { add(it to CfDomainSource.CACHED_UPSTREAM) }
            CfDomain.builtInDomains
                .filterNot { it in manual || it in cachedUpstreamDomains }
                .take(2)
                .forEach { add(it to CfDomainSource.BUILT_IN) }
        }
        val results = domainsToCheck.map { (domain, source) ->
            val host = "kws2.$domain"
            probeWss(host, host, "/apiws", "cf_pool", 2).also {
                CfDomainDiagnosticsState.markProbe(domain, source, it, checkedAt)
            }
        }
        val rows = CfDomainDiagnosticsState.snapshot(manualDomainsRaw, cachedUpstreamDomains)
        CfDomainProbeReport(
            routeReport = ConnectionProbeReport(results),
            domains = rows,
            summary = CfDomainDiagnosticsState.buildSummary(manualDomainsRaw, cachedUpstreamDomains),
            checkedAtMs = checkedAt,
        )
    }

    suspend fun probeDirectWs(): ConnectionProbeReport = withContext(Dispatchers.IO) {
        val results = listOf(2, 4).flatMap { dc ->
            listOf(
                probeWss("kws$dc.web.telegram.org", "149.154.167.220", "/apiws", "direct_ws", dc),
                probeWss("kws$dc-1.web.telegram.org", "149.154.167.220", "/apiws", "direct_ws", dc),
            )
        }
        ConnectionProbeReport(results)
    }

    suspend fun probeTcpFallback(): ConnectionProbeReport = withContext(Dispatchers.IO) {
        val results = testDcs.map { dc ->
            val ip = ConnectionRuntimeConfig.dcIpForTest(dc)
            probeTcp(ip, 443, "tcp_fallback", dc)
        }
        ConnectionProbeReport(results)
    }

    suspend fun probeAll(
        workerDomain: String,
        cfDomain: String,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): ConnectionProbeReport {
        return probeAll(workerDomain, listOf(cfDomain), cachedUpstreamDomains)
    }

    suspend fun probeAll(
        workerDomain: String,
        manualDomainsRaw: List<String>,
        cachedUpstreamDomains: List<String> = emptyList(),
    ): ConnectionProbeReport = withContext(Dispatchers.IO) {
        val merged = buildList {
            addAll(probeDirectWs().results)
            addAll(probeWorker(workerDomain).results)
            addAll(probeCfProxy(manualDomainsRaw, cachedUpstreamDomains).results)
            addAll(probeTcpFallback().results)
        }
        ConnectionProbeReport(merged)
    }

    private fun probeWorkerDc(domain: String, dc: Int): RouteProbeResult {
        val ip = ConnectionRuntimeConfig.dcIpForTest(dc)
        val path = "/apiws?dst=$ip&dc=$dc&media=0"
        return probeWss(domain, domain, path, "worker", dc)
    }

    private fun probeWss(
        sniHost: String,
        dialHost: String,
        path: String,
        route: String,
        dc: Int,
    ): RouteProbeResult {
        val hostnameResult = probeWssOnce(sniHost, dialHost, path, route, dc, via = "hostname")
        if (hostnameResult.success) {
            return hostnameResult
        }
        val resolvedIpv4 = resolveIpv4Addresses(sniHost)
        var lastResult = hostnameResult
        for (address in resolvedIpv4) {
            val ip = address.hostAddress ?: continue
            if (ip.equals(dialHost, ignoreCase = true)) {
                continue
            }
            val ipResult = probeWssOnce(sniHost, ip, path, route, dc, via = "resolved_ip")
            if (ipResult.success) {
                return ipResult
            }
            lastResult = ipResult
            if (ipResult.stage.startsWith("ws_30")) {
                break
            }
        }
        return lastResult
    }

    private fun probeWssOnce(
        sniHost: String,
        dialHost: String,
        path: String,
        route: String,
        dc: Int,
        via: String,
    ): RouteProbeResult {
        val start = System.nanoTime()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(dialHost, 443), CONNECT_MS)
                raw.soTimeout = HANDSHAKE_MS
                val ssl = UpstreamDiagnosticsSsl.sslContext.socketFactory
                    .createSocket(raw, sniHost, 443, true) as SSLSocket
                ssl.use { tls ->
                    tls.soTimeout = HANDSHAKE_MS
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    tls.sslParameters = params
                    tls.startHandshake()
                    val upgraded = performWsUpgrade(tls, sniHost, path)
                    RouteProbeResult(
                        route = route,
                        dc = dc,
                        success = upgraded.success,
                        stage = upgraded.stage,
                        elapsedMs = elapsedMs(start),
                        detail = "$via:${upgraded.detail}",
                    )
                }
            }
        } catch (e: Exception) {
            RouteProbeResult(
                route = route,
                dc = dc,
                success = false,
                stage = "failure",
                elapsedMs = elapsedMs(start),
                detail = "$via:${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun resolveIpv4Addresses(host: String): List<java.net.InetAddress> {
        return try {
            java.net.InetAddress.getAllByName(host)
                .filter { it.address.size == 4 }
                .distinctBy { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun probeTcp(ip: String, port: Int, route: String, dc: Int): RouteProbeResult {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_MS)
            }
            RouteProbeResult(route, dc, true, "tcp_open", elapsedMs(start), "$ip:$port")
        } catch (e: Exception) {
            RouteProbeResult(route, dc, false, "tcp_failure", elapsedMs(start), e.message ?: "error")
        }
    }

    private data class WsUpgrade(val success: Boolean, val stage: String, val detail: String)

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
                append(
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n",
                )
                append("\r\n")
            }
        )
        writer.flush()

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val status = reader.readLine() ?: return WsUpgrade(false, "empty_http", "no status")
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        return if (code == 101) {
            WsUpgrade(true, "ws_101", status)
        } else {
            WsUpgrade(false, "ws_$code", status)
        }
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    fun logReport(report: ConnectionProbeReport) {
        report.results.forEach { r ->
            val line = "CONN TEST ${r.route} dc=${r.dc} ${if (r.success) "OK" else "FAIL"} stage=${r.stage} ms=${r.elapsedMs} ${r.detail}"
            if (r.success) Log.i(TAG, line) else Log.w(TAG, line)
        }
    }
}

/** Shared trust-all SSL for diagnostics only. */
internal object UpstreamDiagnosticsSsl {
    val sslContext by lazy {
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }), SecureRandom())
        }
    }
}
