package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerPoolUiStateMapperTest {
    @Test
    fun detectConfigWarning_noEnabledWorkers() {
        val workers = listOf(
            WorkerEndpoint.create(name = "W1", url = "one.user.workers.dev", enabled = false),
        )
        val config = WorkerPoolConfig(selectedWorkerId = workers.first().id)
        assertEquals(
            WorkerPoolConfigWarning.NO_ENABLED_WORKERS,
            WorkerPoolUiStateMapper.detectConfigWarning(config, workers, workers.first()),
        )
    }

    @Test
    fun detectConfigWarning_selectedNotFound() {
        val workers = listOf(
            WorkerEndpoint.create(name = "W1", url = "one.user.workers.dev"),
        )
        val config = WorkerPoolConfig(selectedWorkerId = "missing-id")
        assertEquals(
            WorkerPoolConfigWarning.SELECTED_NOT_FOUND,
            WorkerPoolUiStateMapper.detectConfigWarning(config, workers, null),
        )
    }

    @Test
    fun detectConfigWarning_selectedDisabled() {
        val enabled = WorkerEndpoint.create(name = "W2", url = "two.user.workers.dev")
        val disabled = WorkerEndpoint.create(name = "W1", url = "one.user.workers.dev", enabled = false)
        val config = WorkerPoolConfig(selectedWorkerId = disabled.id)
        assertEquals(
            WorkerPoolConfigWarning.SELECTED_DISABLED,
            WorkerPoolUiStateMapper.detectConfigWarning(config, listOf(enabled, disabled), disabled),
        )
    }

    @Test
    fun strategyLabels_matchSpec() {
        assertEquals(
            R.string.worker_selection_strategy_manual,
            WorkerSelectionUiMapper.strategyLabelRes(WorkerSelectionStrategy.MANUAL),
        )
        assertEquals(
            R.string.worker_selection_strategy_failover,
            WorkerSelectionUiMapper.strategyLabelRes(WorkerSelectionStrategy.FAILOVER),
        )
        assertEquals(
            R.string.worker_selection_strategy_lowest_latency,
            WorkerSelectionUiMapper.strategyLabelRes(WorkerSelectionStrategy.LOWEST_LATENCY),
        )
    }

    @Test
    fun maskedUrl_hidesQueryAndSecrets() {
        val masked = WorkerUrlSanitizer.maskForDisplay(
            "wss://token:secret@my-worker.example.workers.dev/path?key=value",
            maskDomains = true,
        )
        assertFalse(masked.contains("token"))
        assertFalse(masked.contains("secret"))
        assertFalse(masked.contains("key=value"))
        assertTrue(masked.contains("workers.dev") || masked.contains("***"))
    }
}
