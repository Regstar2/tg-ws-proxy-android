package com.amurcanov.tgwsproxy.worker

import android.content.Context
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.RouteRuntimeState
import java.text.DateFormat
import java.util.Date

object WorkerPoolUiStateMapper {
    fun map(input: WorkerPoolUiInput, context: Context): WorkerPoolUiState {
        val workers = input.workers
        val snapshot = input.failoverSnapshot
        val enabledWorkers = workers.filter { it.enabled }
        val configWarning = detectConfigWarning(input.config, workers, input.selectedWorker)
        val contentState = when {
            workers.isEmpty() -> WorkerPoolContentState.EMPTY
            enabledWorkers.isEmpty() -> WorkerPoolContentState.NO_ENABLED
            configWarning != null -> WorkerPoolContentState.INVALID_CONFIG
            else -> WorkerPoolContentState.CONTENT
        }
        val healthCounts = countHealthStates(workers)
        val lastCheck = input.lastHealthCheckAtMs ?: workers.mapNotNull { it.lastCheckedAt }.maxOrNull()
        val lastCheckLabel = lastCheck?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        }
        val failoverReason = snapshot?.failoverReason?.let { reason ->
            WorkerFailoverUiMapper.reasonLabel(context, reason.wireValue)
        }.orEmpty().ifBlank {
            input.routeState.workerFailoverReason.takeIf { it.isNotBlank() }?.let {
                WorkerFailoverUiMapper.reasonLabel(context, it)
            }
        }
        return WorkerPoolUiState(
            poolEnabled = input.poolEnabled,
            contentState = contentState,
            summary = WorkerPoolSummaryUiModel(
                poolEnabledLabelRes = if (input.poolEnabled) {
                    R.string.worker_pool_enabled
                } else {
                    R.string.worker_pool_disabled
                },
                strategyLabelRes = WorkerSelectionUiMapper.strategyLabelRes(input.config.selectionStrategy),
                totalCount = workers.size,
                enabledCount = enabledWorkers.size,
                disabledCount = workers.size - enabledWorkers.size,
                healthyCount = healthCounts.healthy,
                degradedCount = healthCounts.degraded,
                deadCount = healthCounts.dead,
                unknownCount = healthCounts.unknown,
                selectedWorkerName = snapshot?.selectedWorkerName?.takeIf { it.isNotBlank() }
                    ?: input.selectedWorker?.name
                    ?: context.getString(R.string.worker_pool_no_selected_worker),
                runtimeWorkerName = snapshot?.runtimeWorkerName?.takeIf { it.isNotBlank() }
                    ?: input.routeState.currentWorkerName.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.worker_pool_no_runtime_worker),
                lastSuccessfulWorkerName = snapshot?.lastSuccessfulWorkerName?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.common_none),
                lastFailedWorkerName = snapshot?.lastFailedWorkerName?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.common_none),
                lastCheckTimeLabel = lastCheckLabel,
                lastFailoverReasonLabel = failoverReason?.takeIf { it.isNotBlank() },
            ),
            strategy = WorkerStrategyUiModel(
                strategy = input.config.selectionStrategy,
                labelRes = WorkerSelectionUiMapper.strategyLabelRes(input.config.selectionStrategy),
                descriptionRes = WorkerSelectionUiMapper.strategyDescriptionRes(input.config.selectionStrategy),
            ),
            runtime = mapRuntime(input.routeState, snapshot, context),
            workers = workers.map { worker ->
                mapWorkerItem(
                    worker = worker,
                    input = input,
                    snapshot = snapshot,
                    context = context,
                )
            },
            configWarning = configWarning,
            isProxyRunning = input.isProxyRunning,
            isDiagRunning = input.isDiagRunning,
            maskDomains = input.maskDomains,
            checkingWorkerIds = input.checkingWorkerIds,
            isCheckingAllWorkers = input.isCheckingAllWorkers,
        )
    }

    fun mapCompact(input: WorkerPoolUiInput, context: Context): WorkerPoolCompactUiModel? {
        if (!input.poolEnabled) return null
        val state = map(input, context)
        val runtimeWorker = input.workers.firstOrNull { it.id == input.failoverSnapshot?.runtimeWorkerId }
        return WorkerPoolCompactUiModel(
            poolEnabled = true,
            strategyLabelRes = state.strategy.labelRes,
            runtimeWorkerName = state.summary.runtimeWorkerName,
            runtimeHealthLabelRes = runtimeWorker?.let { WorkerHealthUiMapper.stateLabelRes(it.state) },
            healthyCount = state.summary.healthyCount,
            degradedCount = state.summary.degradedCount,
        )
    }

    private fun mapRuntime(
        route: RouteRuntimeState,
        snapshot: WorkerFailoverRuntimeSnapshot?,
        context: Context,
    ): WorkerRuntimeUiModel {
        return WorkerRuntimeUiModel(
            configuredMode = route.configuredMode,
            selectedRoute = route.selectedRoute,
            activeRoute = route.activeRoute,
            selectedWorkerName = snapshot?.selectedWorkerName?.takeIf { it.isNotBlank() }
                ?: route.selectedWorkerName.ifBlank { context.getString(R.string.worker_pool_no_selected_worker) },
            runtimeWorkerName = snapshot?.runtimeWorkerName?.takeIf { it.isNotBlank() }
                ?: route.currentWorkerName.ifBlank { context.getString(R.string.worker_pool_no_runtime_worker) },
            lastSuccessfulWorkerName = snapshot?.lastSuccessfulWorkerName?.takeIf { it.isNotBlank() }
                ?: route.lastSuccessfulWorkerName.ifBlank { context.getString(R.string.common_none) },
            lastFailedWorkerName = snapshot?.lastFailedWorkerName?.takeIf { it.isNotBlank() }
                ?: route.lastFailedWorkerName.ifBlank { context.getString(R.string.common_none) },
            failoverReason = snapshot?.failoverReason?.let {
                WorkerFailoverUiMapper.reasonLabel(context, it.wireValue)
            }.orEmpty().ifBlank {
                route.workerFailoverReason.takeIf { it.isNotBlank() }?.let {
                    WorkerFailoverUiMapper.reasonLabel(context, it)
                }.orEmpty()
            },
            failoverAttemptCount = snapshot?.attemptCount ?: route.workerFailoverAttemptCount,
            failoverActive = snapshot?.failoverActive ?: route.workerFailoverActive,
        )
    }

    private fun mapWorkerItem(
        worker: WorkerEndpoint,
        input: WorkerPoolUiInput,
        snapshot: WorkerFailoverRuntimeSnapshot?,
        context: Context,
    ): WorkerItemUiModel {
        val nowMs = System.currentTimeMillis()
        val backoffActive = worker.enabled &&
            worker.state == WorkerHealthState.DEAD &&
            WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(worker, worker.lastCheckedAt, nowMs)
        val isSelected = input.selectedWorker?.id == worker.id
        val tags = buildList {
            if (isSelected) add(R.string.worker_pool_selected)
            if (snapshot != null && worker.id == snapshot.runtimeWorkerId && snapshot.runtimeWorkerId.isNotBlank()) {
                add(R.string.worker_pool_runtime_worker)
            }
            if (snapshot != null && worker.id == snapshot.lastSuccessfulWorkerId && snapshot.lastSuccessfulWorkerId.isNotBlank()) {
                add(R.string.worker_pool_last_successful_worker)
            }
            if (snapshot != null && worker.id == snapshot.lastFailedWorkerId && snapshot.lastFailedWorkerId.isNotBlank()) {
                add(R.string.worker_pool_last_failed_worker)
            }
            if (backoffActive) add(R.string.worker_failover_worker_skipped_backoff_tag)
        }
        val lastChecked = worker.lastCheckedAt?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        }
        val errorRes = if (worker.enabled) {
            WorkerHealthUiMapper.errorCodeFromStored(worker.lastErrorCode)?.let {
                WorkerHealthUiMapper.errorCodeLabelRes(it)
            }
        } else {
            null
        }
        return WorkerItemUiModel(
            id = worker.id,
            name = worker.name,
            maskedUrl = WorkerUrlSanitizer.maskForDisplay(worker.url, input.maskDomains),
            enabled = worker.enabled,
            healthState = worker.state,
            healthStateLabelRes = WorkerHealthUiMapper.stateLabelRes(worker.state),
            enabledLabelRes = if (worker.enabled) {
                R.string.worker_pool_worker_enabled
            } else {
                R.string.worker_pool_worker_disabled
            },
            latencyMs = worker.latencyMs,
            lastCheckedLabel = lastChecked,
            failureCount = worker.failureCount,
            lastErrorLabelRes = errorRes,
            isSelected = isSelected,
            isRuntimeWorker = snapshot?.runtimeWorkerId == worker.id,
            isLastSuccessful = snapshot?.lastSuccessfulWorkerId == worker.id,
            isLastFailed = snapshot?.lastFailedWorkerId == worker.id,
            tagLabelResIds = tags,
            priority = worker.priority,
            canSelect = worker.enabled && !isSelected && !input.isProxyRunning,
            canCheck = worker.enabled && !input.isProxyRunning,
            canEdit = !input.isProxyRunning,
            canDelete = !input.isProxyRunning,
            endpoint = worker,
        )
    }

    internal fun detectConfigWarning(
        config: WorkerPoolConfig,
        workers: List<WorkerEndpoint>,
        selectedWorker: WorkerEndpoint?,
    ): WorkerPoolConfigWarning? {
        if (workers.isNotEmpty() && workers.none { it.enabled }) {
            return WorkerPoolConfigWarning.NO_ENABLED_WORKERS
        }
        val selectedId = config.selectedWorkerId?.takeIf { it.isNotBlank() } ?: return null
        val worker = workers.firstOrNull { it.id == selectedId }
        return when {
            worker == null -> WorkerPoolConfigWarning.SELECTED_NOT_FOUND
            !worker.enabled -> WorkerPoolConfigWarning.SELECTED_DISABLED
            worker.state == WorkerHealthState.DEAD &&
                WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(worker, worker.lastCheckedAt) ->
                WorkerPoolConfigWarning.SELECTED_UNAVAILABLE
            else -> null
        }
    }

    private data class HealthCounts(
        val healthy: Int,
        val degraded: Int,
        val dead: Int,
        val unknown: Int,
    )

    private fun countHealthStates(workers: List<WorkerEndpoint>): HealthCounts {
        var healthy = 0
        var degraded = 0
        var dead = 0
        var unknown = 0
        workers.filter { it.enabled }.forEach { worker ->
            when (worker.state) {
                WorkerHealthState.HEALTHY -> healthy++
                WorkerHealthState.DEGRADED -> degraded++
                WorkerHealthState.DEAD -> dead++
                WorkerHealthState.UNKNOWN,
                WorkerHealthState.DISABLED,
                -> unknown++
            }
        }
        return HealthCounts(healthy, degraded, dead, unknown)
    }
}
