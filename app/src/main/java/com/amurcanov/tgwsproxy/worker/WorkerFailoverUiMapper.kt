package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.R

object WorkerFailoverUiMapper {
    fun reasonLabel(context: android.content.Context, raw: String): String {
        return context.getString(reasonLabelRes(WorkerFailoverReason.fromWire(raw)))
    }

    fun reasonLabelRes(reason: WorkerFailoverReason): Int = when (reason) {
        WorkerFailoverReason.SELECTED_WORKER_UNAVAILABLE -> R.string.worker_failover_reason_selected_unavailable
        WorkerFailoverReason.SELECTED_WORKER_DISABLED -> R.string.worker_failover_reason_selected_worker_disabled
        WorkerFailoverReason.SELECTED_WORKER_DEAD -> R.string.worker_failover_reason_selected_worker_dead
        WorkerFailoverReason.WORKER_CONNECT_TIMEOUT -> R.string.worker_failover_reason_timeout
        WorkerFailoverReason.WORKER_DNS_FAILED -> R.string.worker_failover_reason_dns_failed
        WorkerFailoverReason.WORKER_TLS_FAILED -> R.string.worker_failover_reason_tls_failed
        WorkerFailoverReason.WORKER_WEBSOCKET_FAILED -> R.string.worker_failover_reason_websocket_failed
        WorkerFailoverReason.WORKER_RUNTIME_FAILURE -> R.string.worker_failover_reason_unknown
        WorkerFailoverReason.NO_ENABLED_WORKER -> R.string.worker_failover_no_enabled_workers
        WorkerFailoverReason.ALL_WORKERS_FAILED -> R.string.worker_failover_all_workers_failed
        WorkerFailoverReason.BACKOFF_ACTIVE -> R.string.worker_failover_backoff_active
        WorkerFailoverReason.UNKNOWN -> R.string.worker_failover_reason_unknown
    }
}
