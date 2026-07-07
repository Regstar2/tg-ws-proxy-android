package com.amurcanov.tgwsproxy.worker

import android.content.Context
import android.util.Log
import com.amurcanov.tgwsproxy.AppLogCategory
import com.amurcanov.tgwsproxy.AppLogger
import com.amurcanov.tgwsproxy.ConnectionRuntimeConfig
import com.amurcanov.tgwsproxy.WorkerDomain
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeConfig
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeErrorCode
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeNetworkSteps
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStatus
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStep
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeStepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class WorkerHealthCheckRunner(
    private val config: RouteProbeConfig = RouteProbeConfig(
        connectTimeoutMs = WorkerHealthCheckConfig.DEFAULT_WORKER_HEALTH_CONNECT_TIMEOUT_MS,
        readTimeoutMs = WorkerHealthCheckConfig.DEFAULT_WORKER_HEALTH_READ_TIMEOUT_MS,
        overallTimeoutMs = WorkerHealthCheckConfig.DEFAULT_WORKER_HEALTH_OVERALL_TIMEOUT_MS,
        runWebSocketHandshake = true,
    ),
) {
    private val steps = RouteProbeNetworkSteps(config)

    suspend fun run(
        context: Context,
        worker: WorkerEndpoint,
        maskDomains: Boolean = true,
    ): WorkerHealthCheckResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        WorkerHealthCheckLogger.started(worker.id, worker.name)
        try {
            withTimeout(config.overallTimeoutMs) {
                buildResult(context, worker, maskDomains, startedAt)
            }
        } catch (e: CancellationException) {
            cancelledResult(worker, maskDomains, startedAt)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            timeoutResult(worker, maskDomains, startedAt)
        } catch (e: Exception) {
            failedResult(
                worker,
                maskDomains,
                startedAt,
                WorkerHealthErrorCode.UNKNOWN_ERROR,
                e.message ?: "exception",
            )
        }
    }

    private fun buildResult(
        context: Context,
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
    ): WorkerHealthCheckResult {
        if (!worker.enabled) {
            return skippedDisabled(worker, maskDomains, startedAt)
        }
        val validation = WorkerEndpointValidator.validate(worker.name, worker.url)
        if (validation != null) {
            return invalidConfigResult(worker, maskDomains, startedAt, validation)
        }
        val domain = WorkerDomain.normalize(worker.url)
        if (domain.isBlank()) {
            return invalidConfigResult(worker, maskDomains, startedAt, WorkerValidationError.EMPTY_URL)
        }
        val networkSteps = probeWorkerEndpoint(domain)
        logSteps(context, worker.id, networkSteps)
        val workerSteps = networkSteps.map { it.toWorkerStep() }
        val configStep = WorkerHealthCheckStepResult(
            step = WorkerHealthCheckStep.CONFIG_VALIDATION,
            status = WorkerHealthCheckStatus.OK,
            latencyMs = 0,
        )
        val allSteps = listOf(configStep) + workerSteps
        val status = aggregateStatus(allSteps)
        val errorCode = primaryErrorCode(allSteps)
        val finishedAt = System.currentTimeMillis()
        val (state, _) = WorkerHealthStateResolver.resolveAfterCheck(worker.failureCount, status)
        val result = WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = status,
            state = state,
            steps = allSteps,
            latencyMs = (finishedAt - startedAt).coerceAtLeast(0),
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = errorCode,
            errorMessageForDebug = allSteps.lastOrNull { it.debugDetail.isNotBlank() }?.debugDetail.orEmpty(),
        )
        WorkerHealthCheckLogger.finished(result)
        return result
    }

    private fun probeWorkerEndpoint(domain: String): List<RouteProbeStepResult> {
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

    private fun aggregateStatus(steps: List<WorkerHealthCheckStepResult>): WorkerHealthCheckStatus {
        if (steps.isEmpty()) return WorkerHealthCheckStatus.UNKNOWN
        val required = steps.filter {
            it.step != WorkerHealthCheckStep.CONFIG_VALIDATION &&
                it.step != WorkerHealthCheckStep.HTTP_PROBE
        }
        if (required.isEmpty()) return WorkerHealthCheckStatus.UNKNOWN
        if (required.all { it.status == WorkerHealthCheckStatus.SKIPPED }) {
            return WorkerHealthCheckStatus.SKIPPED
        }
        if (required.any { it.status == WorkerHealthCheckStatus.TIMEOUT }) {
            return WorkerHealthCheckStatus.TIMEOUT
        }
        val failures = required.filter {
            it.status == WorkerHealthCheckStatus.FAIL || it.status == WorkerHealthCheckStatus.UNKNOWN
        }
        val successes = required.filter { it.status == WorkerHealthCheckStatus.OK }
        if (failures.isNotEmpty()) {
            return if (successes.isNotEmpty()) WorkerHealthCheckStatus.PARTIAL else WorkerHealthCheckStatus.FAIL
        }
        if (successes.isNotEmpty()) {
            return WorkerHealthCheckStatus.OK
        }
        return WorkerHealthCheckStatus.UNKNOWN
    }

    private fun primaryErrorCode(steps: List<WorkerHealthCheckStepResult>): WorkerHealthErrorCode {
        val failed = steps.firstOrNull {
            it.status == WorkerHealthCheckStatus.FAIL ||
                it.status == WorkerHealthCheckStatus.TIMEOUT ||
                it.status == WorkerHealthCheckStatus.INVALID_CONFIG
        }
        return failed?.errorCode?.takeIf { it != WorkerHealthErrorCode.NONE }
            ?: WorkerHealthErrorCode.UNKNOWN_ERROR
    }

    private fun skippedDisabled(
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
    ): WorkerHealthCheckResult {
        val finishedAt = System.currentTimeMillis()
        val result = WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = WorkerHealthCheckStatus.SKIPPED,
            state = WorkerHealthState.DISABLED,
            steps = listOf(
                WorkerHealthCheckStepResult(
                    step = WorkerHealthCheckStep.CONFIG_VALIDATION,
                    status = WorkerHealthCheckStatus.SKIPPED,
                    errorCode = WorkerHealthErrorCode.WORKER_DISABLED,
                    debugDetail = "worker_disabled",
                ),
            ),
            latencyMs = finishedAt - startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = WorkerHealthErrorCode.WORKER_DISABLED,
            errorMessageForDebug = "worker_disabled",
        )
        WorkerHealthCheckLogger.finished(result)
        return result
    }

    private fun invalidConfigResult(
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
        validation: WorkerValidationError,
    ): WorkerHealthCheckResult {
        val finishedAt = System.currentTimeMillis()
        val result = WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = WorkerHealthCheckStatus.INVALID_CONFIG,
            state = WorkerHealthState.DEGRADED,
            steps = listOf(
                WorkerHealthCheckStepResult(
                    step = WorkerHealthCheckStep.CONFIG_VALIDATION,
                    status = WorkerHealthCheckStatus.INVALID_CONFIG,
                    errorCode = WorkerHealthErrorCode.INVALID_WORKER_URL,
                    debugDetail = validation.name.lowercase(),
                ),
            ),
            latencyMs = finishedAt - startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = WorkerHealthErrorCode.INVALID_WORKER_URL,
            errorMessageForDebug = validation.name.lowercase(),
        )
        WorkerHealthCheckLogger.finished(result)
        return result
    }

    private fun timeoutResult(
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
    ): WorkerHealthCheckResult {
        val finishedAt = System.currentTimeMillis()
        val (state, _) = WorkerHealthStateResolver.resolveAfterCheck(
            worker.failureCount,
            WorkerHealthCheckStatus.TIMEOUT,
        )
        val result = WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = WorkerHealthCheckStatus.TIMEOUT,
            state = state,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = WorkerHealthErrorCode.TIMEOUT,
            errorMessageForDebug = "overall_timeout",
        )
        WorkerHealthCheckLogger.finished(result)
        return result
    }

    private fun cancelledResult(
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
    ): WorkerHealthCheckResult {
        val finishedAt = System.currentTimeMillis()
        return WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = WorkerHealthCheckStatus.FAIL,
            state = worker.state,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = WorkerHealthErrorCode.CANCELLED,
            errorMessageForDebug = "cancelled",
        )
    }

    private fun failedResult(
        worker: WorkerEndpoint,
        maskDomains: Boolean,
        startedAt: Long,
        code: WorkerHealthErrorCode,
        message: String,
    ): WorkerHealthCheckResult {
        val finishedAt = System.currentTimeMillis()
        val (state, _) = WorkerHealthStateResolver.resolveAfterCheck(worker.failureCount, WorkerHealthCheckStatus.FAIL)
        val result = WorkerHealthCheckResult(
            workerId = worker.id,
            workerName = worker.name,
            targetUrlMasked = WorkerUrlSanitizer.maskForDisplay(worker.url, maskDomains),
            status = WorkerHealthCheckStatus.FAIL,
            state = state,
            steps = emptyList(),
            latencyMs = finishedAt - startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorCode = code,
            errorMessageForDebug = message,
        )
        WorkerHealthCheckLogger.finished(result)
        return result
    }

    private fun logSteps(context: Context, workerId: String, stepResults: List<RouteProbeStepResult>) {
        stepResults.forEach { step ->
            when (step.status) {
                RouteProbeStatus.OK ->
                    WorkerHealthCheckLogger.stepSuccess(context, workerId, step.step, step.latencyMs)
                RouteProbeStatus.TIMEOUT ->
                    WorkerHealthCheckLogger.stepFailed(
                        context,
                        workerId,
                        step.step,
                        WorkerHealthErrorCode.TIMEOUT,
                        step.debugDetail,
                    )
                RouteProbeStatus.SKIPPED,
                RouteProbeStatus.UNSUPPORTED,
                -> Unit
                else ->
                    WorkerHealthCheckLogger.stepFailed(
                        context,
                        workerId,
                        step.step,
                        step.error.code.toWorkerErrorCode(),
                        step.debugDetail,
                    )
            }
        }
    }
}

internal object WorkerHealthCheckLogger {
    private const val TAG = "TgWsProxy"

    fun started(workerId: String, workerName: String) {
        Log.i(TAG, "Worker health check started: id=$workerId, name=$workerName")
    }

    fun stepSuccess(context: Context, workerId: String, step: RouteProbeStep, latencyMs: Long) {
        val stepName = step.toWorkerStepName()
        Log.i(
            TAG,
            "Worker health step success: id=$workerId, step=$stepName, latencyMs=$latencyMs",
        )
        AppLogger.i(
            context,
            AppLogCategory.NETWORK,
            "Worker health step success",
            mapOf("id" to workerId, "step" to stepName, "latencyMs" to latencyMs.toString()),
        )
    }

    fun stepFailed(
        context: Context,
        workerId: String,
        step: RouteProbeStep,
        error: WorkerHealthErrorCode,
        detail: String = "",
    ) {
        val stepName = step.toWorkerStepName()
        Log.w(
            TAG,
            "Worker health step failed: id=$workerId, step=$stepName, error=${error.name}, detail=$detail",
        )
        AppLogger.w(
            context,
            AppLogCategory.NETWORK,
            "Worker health step failed",
            mapOf(
                "id" to workerId,
                "step" to stepName,
                "error" to error.name,
            ),
        )
    }

    fun finished(result: WorkerHealthCheckResult) {
        Log.i(
            TAG,
            "Worker health check finished: id=${result.workerId}, status=${result.status.name}, " +
                "state=${result.state.name}, latencyMs=${result.latencyMs}",
        )
    }

    fun allStarted(count: Int) {
        Log.i(TAG, "Worker health check all started: count=$count")
    }

    fun allFinished(healthy: Int, degraded: Int, dead: Int, skipped: Int) {
        Log.i(
            TAG,
            "Worker health check all finished: healthy=$healthy, degraded=$degraded, dead=$dead, skipped=$skipped",
        )
    }
}

private fun RouteProbeStepResult.toWorkerStep(): WorkerHealthCheckStepResult {
    return WorkerHealthCheckStepResult(
        step = step.toWorkerHealthStep(),
        status = status.toWorkerHealthStatus(),
        latencyMs = latencyMs,
        errorCode = error.code.toWorkerErrorCode(),
        debugDetail = debugDetail,
    )
}

private fun RouteProbeStep.toWorkerHealthStep(): WorkerHealthCheckStep = when (this) {
    RouteProbeStep.DNS_RESOLVE -> WorkerHealthCheckStep.DNS_RESOLVE
    RouteProbeStep.TCP_CONNECT -> WorkerHealthCheckStep.TCP_CONNECT
    RouteProbeStep.TLS_HANDSHAKE -> WorkerHealthCheckStep.TLS_HANDSHAKE
    RouteProbeStep.HTTP_PROBE -> WorkerHealthCheckStep.HTTP_PROBE
    RouteProbeStep.WEBSOCKET_HANDSHAKE -> WorkerHealthCheckStep.WEBSOCKET_HANDSHAKE
    else -> WorkerHealthCheckStep.CONFIG_VALIDATION
}

private fun RouteProbeStep.toWorkerStepName(): String = when (this) {
    RouteProbeStep.DNS_RESOLVE -> "dns_resolve"
    RouteProbeStep.TCP_CONNECT -> "tcp_connect"
    RouteProbeStep.TLS_HANDSHAKE -> "tls_handshake"
    RouteProbeStep.HTTP_PROBE -> "http_probe"
    RouteProbeStep.WEBSOCKET_HANDSHAKE -> "websocket_handshake"
    else -> name.lowercase()
}

private fun RouteProbeStatus.toWorkerHealthStatus(): WorkerHealthCheckStatus = when (this) {
    RouteProbeStatus.OK -> WorkerHealthCheckStatus.OK
    RouteProbeStatus.FAIL -> WorkerHealthCheckStatus.FAIL
    RouteProbeStatus.PARTIAL -> WorkerHealthCheckStatus.PARTIAL
    RouteProbeStatus.TIMEOUT -> WorkerHealthCheckStatus.TIMEOUT
    RouteProbeStatus.SKIPPED -> WorkerHealthCheckStatus.SKIPPED
    RouteProbeStatus.UNSUPPORTED -> WorkerHealthCheckStatus.SKIPPED
    RouteProbeStatus.UNKNOWN -> WorkerHealthCheckStatus.UNKNOWN
}

private fun RouteProbeErrorCode.toWorkerErrorCode(): WorkerHealthErrorCode = when (this) {
    RouteProbeErrorCode.NONE -> WorkerHealthErrorCode.NONE
    RouteProbeErrorCode.DNS_FAILED -> WorkerHealthErrorCode.DNS_FAILED
    RouteProbeErrorCode.TCP_CONNECT_FAILED -> WorkerHealthErrorCode.TCP_CONNECT_FAILED
    RouteProbeErrorCode.TLS_HANDSHAKE_FAILED -> WorkerHealthErrorCode.TLS_HANDSHAKE_FAILED
    RouteProbeErrorCode.HTTP_STATUS_ERROR -> WorkerHealthErrorCode.HTTP_STATUS_ERROR
    RouteProbeErrorCode.WEBSOCKET_HANDSHAKE_FAILED -> WorkerHealthErrorCode.WEBSOCKET_HANDSHAKE_FAILED
    RouteProbeErrorCode.TIMEOUT -> WorkerHealthErrorCode.TIMEOUT
    RouteProbeErrorCode.NETWORK_UNAVAILABLE -> WorkerHealthErrorCode.NETWORK_UNAVAILABLE
    RouteProbeErrorCode.INVALID_CONFIG -> WorkerHealthErrorCode.INVALID_WORKER_URL
    RouteProbeErrorCode.CANCELLED -> WorkerHealthErrorCode.CANCELLED
    else -> WorkerHealthErrorCode.UNKNOWN_ERROR
}
