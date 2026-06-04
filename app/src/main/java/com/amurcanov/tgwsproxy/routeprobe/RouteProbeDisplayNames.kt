package com.amurcanov.tgwsproxy.routeprobe

import android.content.Context
import androidx.annotation.StringRes
import com.amurcanov.tgwsproxy.R

object RouteProbeDisplayNames {
    @StringRes
    fun statusLabelRes(status: RouteProbeStatus): Int = when (status) {
        RouteProbeStatus.OK -> R.string.route_probe_status_ok
        RouteProbeStatus.FAIL -> R.string.route_probe_status_fail
        RouteProbeStatus.PARTIAL -> R.string.route_probe_status_partial
        RouteProbeStatus.TIMEOUT -> R.string.route_probe_status_timeout
        RouteProbeStatus.SKIPPED -> R.string.route_probe_status_skipped
        RouteProbeStatus.UNSUPPORTED -> R.string.route_probe_status_unsupported
        RouteProbeStatus.UNKNOWN -> R.string.route_probe_status_unknown
    }

    fun statusLabel(context: Context, status: RouteProbeStatus): String =
        context.getString(statusLabelRes(status))

    @StringRes
    fun targetLabelRes(target: RouteProbeTarget): Int = when (target) {
        RouteProbeTarget.DIRECT_WEBSOCKET -> R.string.route_display_direct_ws
        RouteProbeTarget.WORKER_WEBSOCKET -> R.string.route_display_worker
        RouteProbeTarget.CLOUDFLARE_PROXY -> R.string.route_display_cf_proxy
        RouteProbeTarget.CURRENT_NETWORK -> R.string.adaptive_network_label
        RouteProbeTarget.TELEGRAM_REACHABILITY -> R.string.route_probe_target_telegram
        RouteProbeTarget.IPV4_CONNECTIVITY -> R.string.route_probe_target_ipv4
        RouteProbeTarget.IPV6_CONNECTIVITY -> R.string.route_probe_target_ipv6
    }

    fun targetLabel(context: Context, target: RouteProbeTarget): String =
        context.getString(targetLabelRes(target))

    fun summaryLine(context: Context, result: RouteProbeResult): String {
        val target = targetLabel(context, result.target)
        val status = statusLabel(context, result.status)
        return "$target: $status (${result.latencyMs} ms)"
    }
}
