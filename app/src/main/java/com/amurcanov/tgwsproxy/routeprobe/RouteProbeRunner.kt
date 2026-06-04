package com.amurcanov.tgwsproxy.routeprobe

import android.content.Context
import com.amurcanov.tgwsproxy.CfDomain
import com.amurcanov.tgwsproxy.CfManualDomainList
import com.amurcanov.tgwsproxy.ConnectionRuntimeConfig
import com.amurcanov.tgwsproxy.NetworkProfileProvider
import com.amurcanov.tgwsproxy.WorkerDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RouteProbeRunner(
    private val config: RouteProbeConfig = RouteProbeConfig.DEFAULT,
) {
    private val steps = RouteProbeNetworkSteps(config)

    suspend fun run(
        context: Context,
        target: RouteProbeTarget,
        request: RouteProbeRequest,
    ): RouteProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        RouteProbeLogger.started(target)
        try {
            withTimeout(config.overallTimeoutMs) {
                buildResult(context, target, request, startedAt)
            }
        } catch (e: CancellationException) {
            cancelledResult(target, startedAt)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            timeoutResult(target, startedAt)
        } catch (e: Exception) {
            failedResult(target, startedAt, RouteProbeErrorCode.UNKNOWN_ERROR, e.message ?: "exception")
        }
    }

    private fun buildResult(
        context: Context,
        target: RouteProbeTarget,
        request: RouteProbeRequest,
        startedAt: Long,
    ): RouteProbeResult {
        val profile = request.networkProfile ?: NetworkProfileProvider.current(context)
        val stepResults = when (target) {
            RouteProbeTarget.CURRENT_NETWORK -> steps.probeCurrentNetwork(context)
            RouteProbeTarget.DIRECT_WEBSOCKET -> probeDirectWebSocket()
            RouteProbeTarget.WORKER_WEBSOCKET -> probeWorkerWebSocket(request.workerDomain)
            RouteProbeTarget.CLOUDFLARE_PROXY -> probeCloudflareProxy(request)
            RouteProbeTarget.TELEGRAM_REACHABILITY -> listOf(
                RouteProbeStepResult(
                    step = RouteProbeStep.TELEGRAM_PROBE,
                    status = RouteProbeStatus.UNSUPPORTED,
                    error = RouteProbeError(RouteProbeErrorCode.UNSUPPORTED_TARGET, "telegram_probe_v183"),
                ),
            )
            RouteProbeTarget.IPV4_CONNECTIVITY -> listOf(steps.probeIpv4Connectivity())
            RouteProbeTarget.IPV6_CONNECTIVITY -> listOf(steps.probeIpv6Connectivity())
        }
        logSteps(target, stepResults)
        val required = requiredSteps(target)
        val status = RouteProbeStatusAggregator.aggregate(stepResults, required)
        val errorCode = RouteProbeStatusAggregator.primaryErrorCode(stepResults)
        val finishedAt = System.currentTimeMillis()
        val vpn = stepResults.any { it.error.code == RouteProbeErrorCode.VPN_DETECTED }
        val result = RouteProbeResult(
            target = target,
            status = status,
            steps = stepResults,
            latencyMs = (finishedAt - startedAt).coerceAtLeast(0),
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
            errorCode = errorCode,
            errorMessageForDebug = stepResults.lastOrNull { it.debugDetail.isNotBlank() }?.debugDetail.orEmpty(),
            routeKind = target.routeKindOrNull(),
            networkType = profile.type,
            isVpnDetected = vpn,
        )
        RouteProbeLogger.finished(result)
        return result
    }

    private fun probeDirectWebSocket(): List<RouteProbeStepResult> {
        val host = "kws2.web.telegram.org"
        val dialIp = "149.154.167.220"
        val out = mutableListOf<RouteProbeStepResult>()
        out += steps.probeDns(host)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTcp(dialIp, 443)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTls(host, dialIp)
        if (!config.runWebSocketHandshake) {
            out += RouteProbeStepResult(
                step = RouteProbeStep.WEBSOCKET_HANDSHAKE,
                status = RouteProbeStatus.SKIPPED,
            )
            return out
        }
        if (out.last().status == RouteProbeStatus.OK) {
            out += steps.probeWebSocket(host, dialIp, "/apiws")
        }
        return out
    }

    private fun probeWorkerWebSocket(domainRaw: String): List<RouteProbeStepResult> {
        val domain = WorkerDomain.normalize(domainRaw)
        if (domain.isBlank()) {
            return listOf(
                RouteProbeStepResult(
                    step = RouteProbeStep.ROUTE_BINDING,
                    status = RouteProbeStatus.SKIPPED,
                    error = RouteProbeError(RouteProbeErrorCode.INVALID_CONFIG, "worker_domain_empty"),
                    debugDetail = "worker_domain_empty",
                ),
            )
        }
        val dc = 2
        val ip = ConnectionRuntimeConfig.dcIpForTest(dc)
        val path = "/apiws?dst=$ip&dc=$dc&media=0"
        val out = mutableListOf<RouteProbeStepResult>()
        out += steps.probeDns(domain)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTcp(domain, 443)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTls(domain, domain)
        if (config.runWebSocketHandshake && out.last().status == RouteProbeStatus.OK) {
            out += steps.probeWebSocket(domain, domain, path)
        }
        return out
    }

    private fun probeCloudflareProxy(request: RouteProbeRequest): List<RouteProbeStepResult> {
        val domain = selectCfDomain(request)
        if (domain.isBlank()) {
            return listOf(
                RouteProbeStepResult(
                    step = RouteProbeStep.ROUTE_BINDING,
                    status = RouteProbeStatus.SKIPPED,
                    error = RouteProbeError(RouteProbeErrorCode.INVALID_CONFIG, "cf_domain_empty"),
                ),
            )
        }
        val wsDc = ConnectionRuntimeConfig.effectiveWsHostDc(2)
        val host = "kws$wsDc.$domain"
        val out = mutableListOf<RouteProbeStepResult>()
        out += steps.probeDns(host)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTcp(host, 443)
        if (out.last().status != RouteProbeStatus.OK) {
            return out
        }
        out += steps.probeTls(host, host)
        if (config.runWebSocketHandshake && out.last().status == RouteProbeStatus.OK) {
            out += steps.probeWebSocket(host, host, "/apiws")
        } else if (!config.runWebSocketHandshake && out.last().status == RouteProbeStatus.OK) {
            out += steps.probeHttpHead(host, host, "/apiws")
        }
        return out
    }

    private fun selectCfDomain(request: RouteProbeRequest): String {
        val manual = CfManualDomainList.normalize(request.manualCfDomains)
        manual.firstOrNull()?.let { return it }
        request.cachedUpstreamDomains
            .mapNotNull(CfDomain::normalizeOrNull)
            .firstOrNull()
            ?.let { return it }
        return CfDomain.builtInDomains.firstOrNull().orEmpty()
    }

    private fun requiredSteps(target: RouteProbeTarget): Set<RouteProbeStep> = when (target) {
        RouteProbeTarget.CURRENT_NETWORK -> setOf(RouteProbeStep.ROUTE_BINDING)
        RouteProbeTarget.DIRECT_WEBSOCKET,
        RouteProbeTarget.WORKER_WEBSOCKET,
        RouteProbeTarget.CLOUDFLARE_PROXY,
        -> setOf(
            RouteProbeStep.DNS_RESOLVE,
            RouteProbeStep.TCP_CONNECT,
            RouteProbeStep.TLS_HANDSHAKE,
            RouteProbeStep.WEBSOCKET_HANDSHAKE,
        )
        RouteProbeTarget.IPV4_CONNECTIVITY -> setOf(RouteProbeStep.TCP_CONNECT)
        RouteProbeTarget.IPV6_CONNECTIVITY,
        RouteProbeTarget.TELEGRAM_REACHABILITY,
        -> setOf(RouteProbeStep.TELEGRAM_PROBE)
    }

    private fun logSteps(target: RouteProbeTarget, stepResults: List<RouteProbeStepResult>) {
        stepResults.forEach { step ->
            when (step.status) {
                RouteProbeStatus.OK ->
                    RouteProbeLogger.stepSuccess(target, step.step, step.latencyMs)
                RouteProbeStatus.TIMEOUT ->
                    RouteProbeLogger.stepFailed(target, step.step, RouteProbeErrorCode.TIMEOUT, step.debugDetail)
                RouteProbeStatus.SKIPPED,
                RouteProbeStatus.UNSUPPORTED,
                -> Unit
                else ->
                    RouteProbeLogger.stepFailed(
                        target,
                        step.step,
                        step.error.code,
                        step.debugDetail,
                    )
            }
        }
    }

    private fun timeoutResult(target: RouteProbeTarget, startedAt: Long): RouteProbeResult {
        val finishedAt = System.currentTimeMillis()
        val result = RouteProbeResult(
            target = target,
            status = RouteProbeStatus.TIMEOUT,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
            errorCode = RouteProbeErrorCode.TIMEOUT,
            errorMessageForDebug = "overall_timeout",
            routeKind = target.routeKindOrNull(),
        )
        RouteProbeLogger.finished(result)
        return result
    }

    private fun cancelledResult(target: RouteProbeTarget, startedAt: Long): RouteProbeResult {
        val finishedAt = System.currentTimeMillis()
        return RouteProbeResult(
            target = target,
            status = RouteProbeStatus.FAIL,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
            errorCode = RouteProbeErrorCode.CANCELLED,
            errorMessageForDebug = "cancelled",
            routeKind = target.routeKindOrNull(),
        )
    }

    private fun failedResult(
        target: RouteProbeTarget,
        startedAt: Long,
        code: RouteProbeErrorCode,
        message: String,
    ): RouteProbeResult {
        val finishedAt = System.currentTimeMillis()
        val result = RouteProbeResult(
            target = target,
            status = RouteProbeStatus.FAIL,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
            errorCode = code,
            errorMessageForDebug = message,
            routeKind = target.routeKindOrNull(),
        )
        RouteProbeLogger.finished(result)
        return result
    }
}
