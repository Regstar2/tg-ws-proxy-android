package com.amurcanov.tgwsproxy.routeprobe

object RouteProbeStatusAggregator {
    fun aggregate(
        steps: List<RouteProbeStepResult>,
        requiredSteps: Set<RouteProbeStep>,
    ): RouteProbeStatus {
        if (steps.isEmpty()) {
            return RouteProbeStatus.UNKNOWN
        }
        val required = steps.filter { it.step in requiredSteps }
        if (required.isEmpty()) {
            return RouteProbeStatus.UNKNOWN
        }
        if (required.all { it.status == RouteProbeStatus.SKIPPED || it.status == RouteProbeStatus.UNSUPPORTED }) {
            return RouteProbeStatus.SKIPPED
        }
        if (required.any { it.status == RouteProbeStatus.UNSUPPORTED }) {
            return RouteProbeStatus.UNSUPPORTED
        }
        if (required.any { it.status == RouteProbeStatus.TIMEOUT }) {
            return RouteProbeStatus.TIMEOUT
        }
        val requiredFailures = required.filter {
            it.status == RouteProbeStatus.FAIL || it.status == RouteProbeStatus.UNKNOWN
        }
        val requiredSuccess = required.filter { it.status == RouteProbeStatus.OK }
        if (requiredFailures.isNotEmpty()) {
            return if (requiredSuccess.isNotEmpty()) RouteProbeStatus.PARTIAL else RouteProbeStatus.FAIL
        }
        if (requiredSuccess.isNotEmpty()) {
            val optionalFailures = steps.filter { it.step !in requiredSteps && it.status == RouteProbeStatus.FAIL }
            return if (optionalFailures.isNotEmpty()) RouteProbeStatus.PARTIAL else RouteProbeStatus.OK
        }
        return RouteProbeStatus.UNKNOWN
    }

    fun primaryErrorCode(steps: List<RouteProbeStepResult>): RouteProbeErrorCode {
        val failed = steps.firstOrNull {
            it.status == RouteProbeStatus.FAIL ||
                it.status == RouteProbeStatus.TIMEOUT ||
                it.status == RouteProbeStatus.UNKNOWN
        }
        return failed?.error?.code?.takeIf { it != RouteProbeErrorCode.NONE }
            ?: RouteProbeErrorCode.UNKNOWN_ERROR
    }
}
