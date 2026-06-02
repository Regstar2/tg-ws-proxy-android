package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.annotation.StringRes

object RouteDisplayNames {
    @StringRes
    fun routeLabelRes(route: String): Int {
        return when (route) {
            "direct_ws", "direct" -> R.string.route_display_direct_ws
            "cf_worker_ws", "worker_ws", "worker" -> R.string.route_display_worker
            "cf_proxy_ws", "cf" -> R.string.route_display_cf_proxy
            "tcp_fallback", "tcp" -> R.string.route_display_tcp_fallback
            else -> R.string.route_display_unknown
        }
    }

    fun routeLabel(context: Context, route: String): String {
        if (route.isBlank()) {
            return context.getString(R.string.common_none)
        }
        return context.getString(routeLabelRes(route))
    }

    fun currentRouteLabel(context: Context, routeKind: String, running: Boolean): String {
        if (routeKind.isBlank()) {
            return if (running) {
                context.getString(R.string.metrics_no_active_route)
            } else {
                context.getString(R.string.common_none)
            }
        }
        if (routeKind == "websocket" || routeKind == "ws") {
            return context.getString(R.string.route_display_unknown)
        }
        return routeLabel(context, routeKind)
    }

    @StringRes
    fun transportLabelRes(transport: String): Int {
        return when (transport.lowercase()) {
            "tcp" -> R.string.route_display_transport_tcp
            "websocket", "ws" -> R.string.route_display_transport_websocket
            else -> R.string.common_none
        }
    }

    fun transportLabel(context: Context, transport: String): String {
        if (transport.isBlank()) {
            return context.getString(R.string.common_none)
        }
        val res = transportLabelRes(transport)
        return if (res == R.string.common_none) transport else context.getString(res)
    }

    @StringRes
    fun modeLabelRes(mode: String): Int {
        return when (mode) {
            "auto" -> R.string.connection_mode_auto
            "direct_with_fallback" -> R.string.connection_mode_direct_cf_fallback
            "worker_first" -> R.string.connection_mode_worker_first
            "cf_first" -> R.string.connection_mode_cf_first
            "worker_only" -> R.string.connection_mode_worker_only
            "cf_only" -> R.string.connection_mode_cf_only
            "direct_only" -> R.string.connection_mode_direct_only
            else -> R.string.route_display_unknown
        }
    }

    fun modeLabel(context: Context, mode: String): String {
        if (mode.isBlank()) {
            return context.getString(R.string.common_none)
        }
        return context.getString(modeLabelRes(mode))
    }
}
