package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.R

object WorkerSelectionUiMapper {
    fun strategyLabelRes(strategy: WorkerSelectionStrategy): Int = when (strategy) {
        WorkerSelectionStrategy.MANUAL -> R.string.worker_selection_strategy_manual
        WorkerSelectionStrategy.PRIORITY -> R.string.worker_selection_strategy_priority
        WorkerSelectionStrategy.FAILOVER -> R.string.worker_selection_strategy_failover
        WorkerSelectionStrategy.ROUND_ROBIN -> R.string.worker_selection_strategy_round_robin
        WorkerSelectionStrategy.LOWEST_LATENCY -> R.string.worker_selection_strategy_lowest_latency
    }

    fun strategyDescriptionRes(strategy: WorkerSelectionStrategy): Int = when (strategy) {
        WorkerSelectionStrategy.MANUAL -> R.string.worker_selection_strategy_manual_description
        WorkerSelectionStrategy.PRIORITY -> R.string.worker_selection_strategy_priority_description
        WorkerSelectionStrategy.FAILOVER -> R.string.worker_selection_strategy_failover_description
        WorkerSelectionStrategy.ROUND_ROBIN -> R.string.worker_selection_strategy_round_robin_description
        WorkerSelectionStrategy.LOWEST_LATENCY -> R.string.worker_selection_strategy_lowest_latency_description
    }

    fun reasonLabel(context: android.content.Context, raw: String): String {
        return context.getString(reasonLabelRes(WorkerSelectionReason.fromWire(raw)))
    }

    fun reasonLabelRes(reason: WorkerSelectionReason): Int = when (reason) {
        WorkerSelectionReason.SELECTED_WORKER -> R.string.worker_selection_reason_selected_worker
        WorkerSelectionReason.HIGHEST_PRIORITY -> R.string.worker_selection_reason_highest_priority
        WorkerSelectionReason.FAILOVER_ORDER -> R.string.worker_selection_reason_failover_order
        WorkerSelectionReason.ROUND_ROBIN -> R.string.worker_selection_reason_round_robin
        WorkerSelectionReason.LOWEST_CACHED_LATENCY -> R.string.worker_selection_reason_lowest_latency
        WorkerSelectionReason.NO_LATENCY_DATA -> R.string.worker_selection_reason_no_latency_data
        WorkerSelectionReason.NO_ENABLED_WORKER -> R.string.worker_selection_reason_no_enabled_worker
        WorkerSelectionReason.SELECTED_WORKER_DISABLED -> R.string.worker_selection_reason_selected_worker_disabled
        WorkerSelectionReason.SELECTED_WORKER_NOT_FOUND -> R.string.worker_selection_reason_selected_worker_not_found
        WorkerSelectionReason.SELECTED_WORKER_UNAVAILABLE -> R.string.worker_selection_reason_selected_worker_unavailable
        WorkerSelectionReason.ALL_WORKERS_IN_BACKOFF -> R.string.worker_failover_backoff_active
        WorkerSelectionReason.LOWEST_LATENCY_DATA_EXPIRED -> R.string.worker_selection_reason_no_latency_data
        WorkerSelectionReason.ROUND_ROBIN_CURSOR_INVALID -> R.string.worker_selection_reason_round_robin
        WorkerSelectionReason.INVALID_SELECTION_STRATEGY -> R.string.worker_selection_reason_unknown
        WorkerSelectionReason.UNKNOWN -> R.string.worker_selection_reason_unknown
    }
}
