package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerFailoverResolverTest {
    private fun worker(
        id: String,
        name: String,
        url: String,
        enabled: Boolean = true,
        state: WorkerHealthState = WorkerHealthState.UNKNOWN,
        priority: Int = 0,
        failureCount: Int = 0,
        createdAt: Long = 1L,
    ): WorkerEndpoint {
        return WorkerEndpoint(
            id = id,
            name = name,
            url = url,
            enabled = enabled,
            state = state,
            priority = priority,
            failureCount = failureCount,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    @Test
    fun selectedWorkerFirst() {
        val workers = listOf(
            worker("w1", "Germany #1", "one.user.workers.dev", priority = 1),
            worker("w2", "Netherlands #2", "two.user.workers.dev", priority = 0),
        )
        val (candidates, _) = WorkerFailoverResolver.buildOrderedCandidates(workers, "w1")
        assertEquals("w1", candidates.first().workerId)
        assertTrue(candidates.first().isSelectedWorker)
    }

    @Test
    fun disabledWorkerSkipped() {
        val workers = listOf(
            worker("w1", "Disabled", "one.user.workers.dev", enabled = false),
            worker("w2", "Active", "two.user.workers.dev"),
        )
        val (candidates, _) = WorkerFailoverResolver.buildOrderedCandidates(workers, "w1")
        assertEquals(1, candidates.size)
        assertEquals("w2", candidates.first().workerId)
    }

    @Test
    fun deadWorkerWithBackoffSkipped() {
        val now = System.currentTimeMillis()
        val workers = listOf(
            worker("w1", "Dead", "one.user.workers.dev", state = WorkerHealthState.DEAD)
                .copy(lastFailureAt = now, lastCheckedAt = now),
            worker("w2", "Active", "two.user.workers.dev"),
        )
        val (candidates, skipped) = WorkerFailoverResolver.buildOrderedCandidates(workers, "w1", now)
        assertEquals(1, candidates.size)
        assertEquals("w2", candidates.first().workerId)
        assertEquals(1, skipped)
    }

    @Test
    fun alreadyFailedWorkerSkippedInSameAttempt() {
        val workers = listOf(
            worker("w1", "One", "one.user.workers.dev"),
            worker("w2", "Two", "two.user.workers.dev"),
        )
        val (candidates, _) = WorkerFailoverResolver.buildOrderedCandidates(workers, "w1")
        val second = WorkerFailoverResolver.nextCandidate(candidates, setOf("w1"))
        assertTrue(second is WorkerFailoverResult.Selected)
        assertEquals("w2", (second as WorkerFailoverResult.Selected).candidate.workerId)
    }

    @Test
    fun noEnabledWorkerReturnsError() {
        val result = WorkerFailoverResolver.nextCandidate(emptyList(), emptySet())
        assertTrue(result is WorkerFailoverResult.Error)
        assertEquals(
            WorkerFailoverErrorCode.NO_ENABLED_WORKER,
            (result as WorkerFailoverResult.Error).code,
        )
    }

    @Test
    fun allWorkersFailedWhenExhausted() {
        val workers = listOf(worker("w1", "One", "one.user.workers.dev"))
        val (candidates, _) = WorkerFailoverResolver.buildOrderedCandidates(workers, "w1")
        val result = WorkerFailoverResolver.nextCandidate(candidates, setOf("w1"))
        assertTrue(result is WorkerFailoverResult.Error)
        assertEquals(
            WorkerFailoverErrorCode.ALL_WORKERS_FAILED,
            (result as WorkerFailoverResult.Error).code,
        )
    }

    @Test
    fun maxAttemptsLimited() {
        assertEquals(3, WorkerFailoverResolver.resolveMaxAttempts(5))
        assertEquals(2, WorkerFailoverResolver.resolveMaxAttempts(2))
        assertEquals(0, WorkerFailoverResolver.resolveMaxAttempts(0))
    }
}

class WorkerFailoverPersistenceTest {
    @Test
    fun selectedWorkerIdNotChangedByHealthUpdate() {
        val pool = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val first = pool.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev"),
        ).getOrThrow()
        val second = pool.addWorker(
            WorkerEndpoint.create(name = "Worker 2", url = "two.user.workers.dev"),
        ).getOrThrow()
        pool.selectWorker(first.id)
        pool.applyHealthUpdate(
            second.copy(
                state = WorkerHealthState.HEALTHY,
                lastSuccessAt = System.currentTimeMillis(),
            ),
        )
        assertEquals(first.id, pool.getWorkerPoolConfig().selectedWorkerId)
    }
}
