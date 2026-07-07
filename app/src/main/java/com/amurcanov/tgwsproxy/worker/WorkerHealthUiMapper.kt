package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.R

object WorkerHealthUiMapper {
    fun statusLabelRes(status: WorkerHealthCheckStatus): Int = when (status) {
        WorkerHealthCheckStatus.OK -> R.string.worker_health_status_ok
        WorkerHealthCheckStatus.FAIL -> R.string.worker_health_status_fail
        WorkerHealthCheckStatus.PARTIAL -> R.string.worker_health_status_partial
        WorkerHealthCheckStatus.TIMEOUT -> R.string.worker_health_status_timeout
        WorkerHealthCheckStatus.SKIPPED -> R.string.worker_health_status_skipped
        WorkerHealthCheckStatus.INVALID_CONFIG -> R.string.worker_health_status_invalid_config
        WorkerHealthCheckStatus.UNKNOWN -> R.string.worker_health_status_unknown
    }

    fun stateLabelRes(state: WorkerHealthState): Int = when (state) {
        WorkerHealthState.UNKNOWN -> R.string.worker_health_state_unknown
        WorkerHealthState.HEALTHY -> R.string.worker_health_state_healthy
        WorkerHealthState.DEGRADED -> R.string.worker_health_state_degraded
        WorkerHealthState.DEAD -> R.string.worker_health_state_dead
        WorkerHealthState.DISABLED -> R.string.worker_health_state_disabled
    }

    fun errorCodeLabelRes(code: WorkerHealthErrorCode): Int = when (code) {
        WorkerHealthErrorCode.NONE -> R.string.worker_health_status_unknown
        WorkerHealthErrorCode.INVALID_WORKER_URL -> R.string.worker_health_error_invalid_url
        WorkerHealthErrorCode.WORKER_DISABLED -> R.string.worker_health_status_skipped
        WorkerHealthErrorCode.DNS_FAILED -> R.string.worker_health_error_dns
        WorkerHealthErrorCode.TCP_CONNECT_FAILED -> R.string.worker_health_error_tcp
        WorkerHealthErrorCode.TLS_HANDSHAKE_FAILED -> R.string.worker_health_error_tls
        WorkerHealthErrorCode.HTTP_STATUS_ERROR -> R.string.worker_health_error_http
        WorkerHealthErrorCode.WEBSOCKET_HANDSHAKE_FAILED -> R.string.worker_health_error_websocket
        WorkerHealthErrorCode.TIMEOUT -> R.string.worker_health_status_timeout
        WorkerHealthErrorCode.NETWORK_UNAVAILABLE -> R.string.worker_health_error_network
        WorkerHealthErrorCode.CANCELLED -> R.string.worker_health_error_cancelled
        WorkerHealthErrorCode.UNKNOWN_ERROR -> R.string.worker_health_status_unknown
    }

    fun errorCodeFromStored(stored: String?): WorkerHealthErrorCode? {
        if (stored.isNullOrBlank()) return null
        return runCatching { WorkerHealthErrorCode.valueOf(stored) }.getOrNull()
    }
}
