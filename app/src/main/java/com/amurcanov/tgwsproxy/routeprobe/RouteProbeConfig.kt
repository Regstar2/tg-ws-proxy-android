package com.amurcanov.tgwsproxy.routeprobe

data class RouteProbeConfig(
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val overallTimeoutMs: Long = DEFAULT_OVERALL_TIMEOUT_MS,
    val allowIpv4: Boolean = true,
    val allowIpv6: Boolean = false,
    val runWebSocketHandshake: Boolean = true,
    val runTelegramProbe: Boolean = false,
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_500L
        const val DEFAULT_READ_TIMEOUT_MS = 4_500L
        const val DEFAULT_OVERALL_TIMEOUT_MS = 12_000L

        val DEFAULT = RouteProbeConfig()
    }
}
