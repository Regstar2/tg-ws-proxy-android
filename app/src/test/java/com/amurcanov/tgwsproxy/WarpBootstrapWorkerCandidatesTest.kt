package com.amurcanov.tgwsproxy

import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerHealthState
import org.junit.Assert.assertEquals
import org.junit.Test

class WarpBootstrapWorkerCandidatesTest {
    @Test
    fun selectedWorkerComesFirstAndDeadWorkersAreSkipped() {
        val selected = worker("selected", "https://selected.example.workers.dev", WorkerHealthState.HEALTHY)
        val healthy = worker("healthy", "healthy.example.workers.dev", WorkerHealthState.HEALTHY)
        val dead = worker("dead", "dead.example.workers.dev", WorkerHealthState.DEAD)

        val result = WarpBootstrapWorkerCandidates.resolve(
            selected = selected,
            workers = listOf(healthy, dead, selected),
            legacyDomain = "legacy.example.workers.dev",
        )

        assertEquals(
            listOf(
                "https://selected.example.workers.dev",
                "https://healthy.example.workers.dev",
                "https://legacy.example.workers.dev",
            ),
            result,
        )
    }

    @Test
    fun duplicateAndDisabledCandidatesAreRemoved() {
        val first = worker("first", "worker.example.workers.dev", WorkerHealthState.UNKNOWN)
        val duplicate = worker("duplicate", "https://worker.example.workers.dev/apiws", WorkerHealthState.DEGRADED)
        val disabled = worker(
            "disabled",
            "disabled.example.workers.dev",
            WorkerHealthState.DISABLED,
            enabled = false,
        )

        val result = WarpBootstrapWorkerCandidates.resolve(
            selected = null,
            workers = listOf(first, duplicate, disabled),
            legacyDomain = "worker.example.workers.dev",
        )

        assertEquals(listOf("https://worker.example.workers.dev"), result)
    }

    private fun worker(
        id: String,
        url: String,
        state: WorkerHealthState,
        enabled: Boolean = true,
    ): WorkerEndpoint = WorkerEndpoint(
        id = id,
        name = id,
        url = url,
        enabled = enabled,
        state = state,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
