package com.amurcanov.tgwsproxy.routeprobe

import com.amurcanov.tgwsproxy.NetworkProfile
import com.amurcanov.tgwsproxy.NetworkProfileType
import com.amurcanov.tgwsproxy.RouteKind

enum class RouteProbeTarget {
    DIRECT_WEBSOCKET,
    WORKER_WEBSOCKET,
    CLOUDFLARE_PROXY,
    TELEGRAM_REACHABILITY,
    CURRENT_NETWORK,
    IPV4_CONNECTIVITY,
    IPV6_CONNECTIVITY,
}

enum class RouteProbeStatus {
    OK,
    FAIL,
    PARTIAL,
    SKIPPED,
    TIMEOUT,
    UNSUPPORTED,
    UNKNOWN,
}

enum class RouteProbeStep {
    DNS_RESOLVE,
    TCP_CONNECT,
    TLS_HANDSHAKE,
    HTTP_PROBE,
    WEBSOCKET_HANDSHAKE,
    ROUTE_BINDING,
    TELEGRAM_PROBE,
}

enum class RouteProbeErrorCode {
    NONE,
    DNS_FAILED,
    TCP_CONNECT_FAILED,
    TLS_HANDSHAKE_FAILED,
    HTTP_STATUS_ERROR,
    WEBSOCKET_HANDSHAKE_FAILED,
    TIMEOUT,
    NETWORK_UNAVAILABLE,
    VPN_DETECTED,
    INVALID_TARGET,
    INVALID_CONFIG,
    UNSUPPORTED_TARGET,
    CANCELLED,
    UNKNOWN_ERROR,
}

data class RouteProbeError(
    val code: RouteProbeErrorCode,
    val debugMessage: String = "",
)

data class RouteProbeStepResult(
    val step: RouteProbeStep,
    val status: RouteProbeStatus,
    val latencyMs: Long = 0,
    val error: RouteProbeError = RouteProbeError(RouteProbeErrorCode.NONE),
    val debugDetail: String = "",
)

data class RouteProbeRequest(
    val workerDomain: String = "",
    val manualCfDomains: List<String> = emptyList(),
    val cachedUpstreamDomains: List<String> = emptyList(),
    val networkProfile: NetworkProfile? = null,
)

data class RouteProbeResult(
    val target: RouteProbeTarget,
    val status: RouteProbeStatus,
    val steps: List<RouteProbeStepResult>,
    val latencyMs: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val errorCode: RouteProbeErrorCode,
    val errorMessageForDebug: String = "",
    val routeKind: RouteKind? = null,
    val networkType: NetworkProfileType? = null,
    val isVpnDetected: Boolean = false,
)

data class RouteProbeSummary(
    val results: List<RouteProbeResult>,
    val finishedAtMs: Long,
) {
    val worstStatus: RouteProbeStatus
        get() = results.map { it.status }.maxByOrNull { statusPriority(it) } ?: RouteProbeStatus.UNKNOWN

    private fun statusPriority(status: RouteProbeStatus): Int = when (status) {
        RouteProbeStatus.FAIL -> 6
        RouteProbeStatus.TIMEOUT -> 5
        RouteProbeStatus.PARTIAL -> 4
        RouteProbeStatus.UNKNOWN -> 3
        RouteProbeStatus.UNSUPPORTED -> 2
        RouteProbeStatus.SKIPPED -> 1
        RouteProbeStatus.OK -> 0
    }
}

fun RouteProbeTarget.routeKindOrNull(): RouteKind? = when (this) {
    RouteProbeTarget.DIRECT_WEBSOCKET -> RouteKind.DIRECT_WS
    RouteProbeTarget.WORKER_WEBSOCKET -> RouteKind.WORKER_WS
    RouteProbeTarget.CLOUDFLARE_PROXY -> RouteKind.CF_PROXY_WS
    else -> null
}
