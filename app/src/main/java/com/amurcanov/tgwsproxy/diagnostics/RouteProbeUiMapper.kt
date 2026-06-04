package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeResult
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStep
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStepResult
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget

object RouteProbeUiMapper {
    fun toUiModel(result: RouteProbeResult): RouteProbeUiModel {
        return RouteProbeUiModel(
            target = result.target,
            status = result.status,
            latencyMs = result.latencyMs.takeIf { it > 0 },
            lastCheckedAtMs = result.finishedAtMs.takeIf { it > 0 },
            errorCode = result.errorCode,
            shortDetails = buildShortDetails(result),
            steps = result.steps.map { toStepUi(it) },
        )
    }

    fun placeholder(target: RouteProbeTarget): RouteProbeUiModel {
        return RouteProbeUiModel(
            target = target,
            status = RouteProbeStatus.UNKNOWN,
            shortDetails = "",
            steps = emptyList(),
        )
    }

    private fun toStepUi(step: RouteProbeStepResult): RouteProbeStepUiModel {
        return RouteProbeStepUiModel(
            step = step.step,
            status = step.status,
            latencyMs = step.latencyMs,
            errorCode = step.error.code,
            debugDetail = step.debugDetail,
        )
    }

    private fun buildShortDetails(result: RouteProbeResult): String {
        if (result.errorMessageForDebug.isNotBlank()) {
            return result.errorMessageForDebug
        }
        val failed = result.steps.lastOrNull {
            it.status == RouteProbeStatus.FAIL ||
                it.status == RouteProbeStatus.TIMEOUT
        }
        return failed?.debugDetail.orEmpty()
    }

    @androidx.annotation.StringRes
    fun targetLabelRes(target: RouteProbeTarget): Int = when (target) {
        RouteProbeTarget.CURRENT_NETWORK -> R.string.diagnostics_target_current_network
        RouteProbeTarget.DIRECT_WEBSOCKET -> R.string.diagnostics_target_direct_websocket
        RouteProbeTarget.WORKER_WEBSOCKET -> R.string.diagnostics_target_worker_websocket
        RouteProbeTarget.CLOUDFLARE_PROXY -> R.string.diagnostics_target_cloudflare_proxy
        RouteProbeTarget.TELEGRAM_REACHABILITY -> R.string.diagnostics_target_telegram_reachability
        RouteProbeTarget.IPV4_CONNECTIVITY -> R.string.diagnostics_target_ipv4
        RouteProbeTarget.IPV6_CONNECTIVITY -> R.string.diagnostics_target_ipv6
    }

    @androidx.annotation.StringRes
    fun statusLabelRes(status: RouteProbeStatus): Int = when (status) {
        RouteProbeStatus.OK -> R.string.diagnostics_status_ok
        RouteProbeStatus.FAIL -> R.string.diagnostics_status_fail
        RouteProbeStatus.PARTIAL -> R.string.diagnostics_status_partial
        RouteProbeStatus.TIMEOUT -> R.string.diagnostics_status_timeout
        RouteProbeStatus.SKIPPED -> R.string.diagnostics_status_skipped
        RouteProbeStatus.UNSUPPORTED -> R.string.diagnostics_status_unsupported
        RouteProbeStatus.UNKNOWN -> R.string.diagnostics_status_unknown
    }

    @androidx.annotation.StringRes
    fun stepLabelRes(step: RouteProbeStep): Int = when (step) {
        RouteProbeStep.DNS_RESOLVE -> R.string.diagnostics_step_dns
        RouteProbeStep.TCP_CONNECT -> R.string.diagnostics_step_tcp
        RouteProbeStep.TLS_HANDSHAKE -> R.string.diagnostics_step_tls
        RouteProbeStep.HTTP_PROBE -> R.string.diagnostics_step_http
        RouteProbeStep.WEBSOCKET_HANDSHAKE -> R.string.diagnostics_step_websocket
        RouteProbeStep.ROUTE_BINDING -> R.string.diagnostics_step_route_binding
        RouteProbeStep.TELEGRAM_PROBE -> R.string.diagnostics_step_telegram
    }

    @androidx.annotation.StringRes
    fun errorCodeLabelRes(code: RouteProbeErrorCode): Int = when (code) {
        RouteProbeErrorCode.NONE -> R.string.common_none
        RouteProbeErrorCode.DNS_FAILED -> R.string.diagnostics_error_dns
        RouteProbeErrorCode.TCP_CONNECT_FAILED -> R.string.diagnostics_error_tcp
        RouteProbeErrorCode.TLS_HANDSHAKE_FAILED -> R.string.diagnostics_error_tls
        RouteProbeErrorCode.HTTP_STATUS_ERROR -> R.string.diagnostics_error_http
        RouteProbeErrorCode.WEBSOCKET_HANDSHAKE_FAILED -> R.string.diagnostics_error_websocket
        RouteProbeErrorCode.TIMEOUT -> R.string.diagnostics_error_timeout
        RouteProbeErrorCode.NETWORK_UNAVAILABLE -> R.string.diagnostics_error_network
        RouteProbeErrorCode.VPN_DETECTED -> R.string.diagnostics_error_vpn
        RouteProbeErrorCode.INVALID_CONFIG -> R.string.diagnostics_error_invalid_config
        RouteProbeErrorCode.UNSUPPORTED_TARGET -> R.string.diagnostics_error_unsupported
        RouteProbeErrorCode.CANCELLED -> R.string.diagnostics_error_cancelled
        else -> R.string.diagnostics_error_unknown
    }
}
