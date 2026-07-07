package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSelectionResolverTest {
    private fun worker(
        id: String,
        name: String,
        url: String,
        enabled: Boolean = true,
        state: WorkerHealthState = WorkerHealthState.UNKNOWN,
        priority: Int = 0,
        latencyMs: Long? = null,
        lastCheckedAt: Long? = null,
        createdAt: Long = 1L,
    ): WorkerEndpoint {
        return WorkerEndpoint(
            id = id,
            name = name,
            url = url,
            enabled = enabled,
            state = state,
            priority = priority,
            latencyMs = latencyMs,
            lastCheckedAt = lastCheckedAt,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun config(
        strategy: WorkerSelectionStrategy = WorkerSelectionStrategy.FAILOVER,
        selectedId: String? = null,
        cursor: String? = null,
    ): WorkerPoolConfig {
        return WorkerPoolConfig(
            enabled = true,
            selectedWorkerId = selectedId,
            selectionStrategy = strategy,
            roundRobinCursor = cursor,
        )
    }

    @Test
    fun manualReturnsOnlySelectedWorker() {
        val workers = listOf(
            worker("w1", "One", "one.user.workers.dev"),
            worker("w2", "Two", "two.user.workers.dev"),
        )
        val result = WorkerSelectionResolver.resolveCandidates(config(WorkerSelectionStrategy.MANUAL, "w1"), workers)
        assertTrue(result is WorkerSelectionResult.Success)
        val success = result as WorkerSelectionResult.Success
        assertEquals(1, success.candidates.size)
        assertEquals("w1", success.candidates.first().workerId)
    }

    @Test
    fun manualSelectedDisabledReturnsError() {
        val workers = listOf(worker("w1", "One", "one.user.workers.dev", enabled = false))
        val result = WorkerSelectionResolver.resolveCandidates(config(WorkerSelectionStrategy.MANUAL, "w1"), workers)
        assertTrue(result is WorkerSelectionResult.Error)
        assertEquals(
            WorkerSelectionErrorCode.SELECTED_WORKER_DISABLED,
            (result as WorkerSelectionResult.Error).code,
        )
    }

    @Test
    fun manualSelectedMissingReturnsError() {
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.MANUAL, "missing"),
            listOf(worker("w1", "One", "one.user.workers.dev")),
        )
        assertTrue(result is WorkerSelectionResult.Error)
        assertEquals(
            WorkerSelectionErrorCode.SELECTED_WORKER_NOT_FOUND,
            (result as WorkerSelectionResult.Error).code,
        )
    }

    @Test
    fun priorityOrdersByPriority() {
        val workers = listOf(
            worker("w1", "Low", "one.user.workers.dev", priority = 1),
            worker("w2", "High", "two.user.workers.dev", priority = 100),
        )
        val result = WorkerSelectionResolver.resolveCandidates(config(WorkerSelectionStrategy.PRIORITY), workers)
        assertTrue(result is WorkerSelectionResult.Success)
        assertEquals("w2", (result as WorkerSelectionResult.Success).candidates.first().workerId)
    }

    @Test
    fun prioritySkipsDisabledAndBackoff() {
        val now = System.currentTimeMillis()
        val workers = listOf(
            worker("w1", "Dead", "one.user.workers.dev", priority = 100, state = WorkerHealthState.DEAD)
                .copy(lastFailureAt = now, lastCheckedAt = now),
            worker("w2", "Active", "two.user.workers.dev", priority = 1),
        )
        val result = WorkerSelectionResolver.resolveCandidates(config(WorkerSelectionStrategy.PRIORITY), workers, now)
        assertTrue(result is WorkerSelectionResult.Success)
        assertEquals("w2", (result as WorkerSelectionResult.Success).candidates.single().workerId)
    }

    @Test
    fun failoverStartsWithSelectedWorker() {
        val workers = listOf(
            worker("w1", "Selected", "one.user.workers.dev"),
            worker("w2", "Other", "two.user.workers.dev"),
        )
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.FAILOVER, "w1"),
            workers,
        )
        assertTrue(result is WorkerSelectionResult.Success)
        val success = result as WorkerSelectionResult.Success
        assertEquals("w1", success.candidates.first().workerId)
        assertEquals(2, success.candidates.size)
    }

    @Test
    fun roundRobinAdvancesCursorWhenRequested() {
        val workers = listOf(
            worker("w1", "One", "one.user.workers.dev", createdAt = 1L),
            worker("w2", "Two", "two.user.workers.dev", createdAt = 2L),
            worker("w3", "Three", "three.user.workers.dev", createdAt = 3L),
        )
        val first = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.ROUND_ROBIN, cursor = null),
            workers,
            advanceRoundRobin = true,
        ) as WorkerSelectionResult.Success
        assertEquals("w1", first.candidates.first().workerId)
        assertEquals("w2", first.roundRobinNextCursor)

        val second = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.ROUND_ROBIN, cursor = first.roundRobinNextCursor),
            workers,
            advanceRoundRobin = true,
        ) as WorkerSelectionResult.Success
        assertEquals("w2", second.candidates.first().workerId)
        assertEquals("w3", second.roundRobinNextCursor)
    }

    @Test
    fun roundRobinHandlesDeletedCursorWorker() {
        val workers = listOf(
            worker("w2", "Two", "two.user.workers.dev", createdAt = 2L),
            worker("w3", "Three", "three.user.workers.dev", createdAt = 3L),
        )
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.ROUND_ROBIN, cursor = "deleted"),
            workers,
        )
        assertTrue(result is WorkerSelectionResult.Success)
        assertEquals(
            WorkerSelectionReason.ROUND_ROBIN_CURSOR_INVALID,
            (result as WorkerSelectionResult.Success).reason,
        )
    }

    @Test
    fun lowestLatencyChoosesLowestCachedLatency() {
        val now = System.currentTimeMillis()
        val workers = listOf(
            worker("w1", "Slow", "one.user.workers.dev", latencyMs = 300, lastCheckedAt = now),
            worker("w2", "Fast", "two.user.workers.dev", latencyMs = 100, lastCheckedAt = now),
        )
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.LOWEST_LATENCY),
            workers,
            now,
        )
        assertTrue(result is WorkerSelectionResult.Success)
        assertEquals("w2", (result as WorkerSelectionResult.Success).candidates.first().workerId)
    }

    @Test
    fun lowestLatencyFallsBackWhenNoLatencyData() {
        val workers = listOf(
            worker("w1", "One", "one.user.workers.dev"),
            worker("w2", "Two", "two.user.workers.dev"),
        )
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.LOWEST_LATENCY, "w1"),
            workers,
        )
        assertTrue(result is WorkerSelectionResult.Success)
        val success = result as WorkerSelectionResult.Success
        assertEquals(WorkerSelectionReason.NO_LATENCY_DATA, success.reason)
        assertEquals("w1", success.candidates.first().workerId)
    }

    @Test
    fun lowestLatencyIgnoresStaleLatency() {
        val now = System.currentTimeMillis()
        val stale = now - WorkerSelectionConfig.DEFAULT_LOWEST_LATENCY_MAX_AGE_MS - 60_000
        val workers = listOf(
            worker("w1", "Stale", "one.user.workers.dev", latencyMs = 50, lastCheckedAt = stale),
            worker("w2", "Fresh", "two.user.workers.dev", latencyMs = 200, lastCheckedAt = now),
        )
        val result = WorkerSelectionResolver.resolveCandidates(
            config(WorkerSelectionStrategy.LOWEST_LATENCY),
            workers,
            now,
        )
        assertTrue(result is WorkerSelectionResult.Success)
        assertEquals("w2", (result as WorkerSelectionResult.Success).candidates.first().workerId)
    }

    @Test
    fun manualMaxAttemptsIsOne() {
        assertEquals(1, WorkerFailoverResolver.resolveMaxAttempts(WorkerSelectionStrategy.MANUAL, 3))
    }

    @Test
    fun repositoryRoundRobinCursorPersists() {
        val pool = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        pool.saveWorkerPoolConfig(
            WorkerPoolConfig(
                enabled = true,
                selectionStrategy = WorkerSelectionStrategy.ROUND_ROBIN,
            ),
        )
        pool.addWorker(WorkerEndpoint.create("One", "one.user.workers.dev"))
        pool.addWorker(WorkerEndpoint.create("Two", "two.user.workers.dev"))
        val first = pool.resolveSelectionForConnection() as WorkerSelectionResult.Success
        val cursorAfterFirst = pool.getWorkerPoolConfig().roundRobinCursor
        assertEquals(first.roundRobinNextCursor, cursorAfterFirst)
        val second = pool.resolveSelectionForConnection() as WorkerSelectionResult.Success
        assertEquals("Two", second.candidates.first().workerName)
    }
}
