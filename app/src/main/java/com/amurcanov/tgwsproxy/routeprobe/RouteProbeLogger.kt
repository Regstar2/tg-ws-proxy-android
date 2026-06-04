package com.amurcanov.tgwsproxy.routeprobe

import android.util.Log

internal object RouteProbeLogger {
    private const val TAG = "TgWsProxy"

    fun started(target: RouteProbeTarget) {
        Log.i(TAG, "Route probe started: target=${target.name.lowercase()}")
    }

    fun stepSuccess(target: RouteProbeTarget, step: RouteProbeStep, latencyMs: Long) {
        Log.i(
            TAG,
            "Route probe step success: target=${target.name.lowercase()}, step=${step.name.lowercase()}, latencyMs=$latencyMs",
        )
    }

    fun stepFailed(target: RouteProbeTarget, step: RouteProbeStep, error: RouteProbeErrorCode, detail: String = "") {
        Log.w(
            TAG,
            "Route probe step failed: target=${target.name.lowercase()}, step=${step.name.lowercase()}, " +
                "error=${error.name}, detail=$detail",
        )
    }

    fun finished(result: RouteProbeResult) {
        Log.i(
            TAG,
            "Route probe finished: target=${result.target.name.lowercase()}, status=${result.status.name}, " +
                "errorCode=${result.errorCode.name}, latencyMs=${result.latencyMs}",
        )
    }
}
