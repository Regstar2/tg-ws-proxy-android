package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerHealthStateResolverTest {
    @Test
    fun successResetsToHealthy() {
        val (state, failures) = WorkerHealthStateResolver.resolveAfterCheck(
            currentFailureCount = 2,
            checkStatus = WorkerHealthCheckStatus.OK,
        )
        assertEquals(WorkerHealthState.HEALTHY, state)
        assertEquals(0, failures)
    }

    @Test
    fun oneFailureBecomesDegraded() {
        val (state, failures) = WorkerHealthStateResolver.resolveAfterCheck(
            currentFailureCount = 0,
            checkStatus = WorkerHealthCheckStatus.FAIL,
        )
        assertEquals(WorkerHealthState.DEGRADED, state)
        assertEquals(1, failures)
    }

    @Test
    fun threeFailuresBecomeDead() {
        val (state, failures) = WorkerHealthStateResolver.resolveAfterCheck(
            currentFailureCount = 2,
            checkStatus = WorkerHealthCheckStatus.TIMEOUT,
        )
        assertEquals(WorkerHealthState.DEAD, state)
        assertEquals(3, failures)
    }

    @Test
    fun disabledWorkerIsSkipped() {
        val (state, failures) = WorkerHealthStateResolver.resolveAfterCheck(
            currentFailureCount = 0,
            checkStatus = WorkerHealthCheckStatus.SKIPPED,
        )
        assertEquals(WorkerHealthState.DISABLED, state)
        assertEquals(0, failures)
    }
}

class WorkerHealthBackoffPolicyTest {
    @Test
    fun deadWorkerWithinBackoffIsSkippedForAutomaticCheck() {
        val worker = WorkerEndpoint.create(name = "W1", url = "one.user.workers.dev").copy(
            state = WorkerHealthState.DEAD,
            lastFailureAt = 1_000L,
        )
        assertTrue(
            WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(
                worker = worker,
                lastCheckAtMs = 1_000L,
                nowMs = 1_000L + WorkerHealthCheckConfig.DEFAULT_DEAD_WORKER_BACKOFF_MS - 1,
            ),
        )
    }

    @Test
    fun healthyWorkerIsNeverSkippedByBackoff() {
        val worker = WorkerEndpoint.create(name = "W1", url = "one.user.workers.dev").copy(
            state = WorkerHealthState.HEALTHY,
        )
        assertTrue(
            !WorkerHealthBackoffPolicy.shouldSkipAutomaticCheck(
                worker = worker,
                lastCheckAtMs = System.currentTimeMillis(),
            ),
        )
    }
}

class WorkerHealthCheckResultTest {
    @Test
    fun invalidConfigUsesTypedErrorCode() {
        val now = System.currentTimeMillis()
        val result = WorkerHealthCheckResult(
            workerId = "id",
            workerName = "Worker",
            targetUrlMasked = "https://***.workers.dev",
            status = WorkerHealthCheckStatus.INVALID_CONFIG,
            state = WorkerHealthState.DEGRADED,
            steps = listOf(
                WorkerHealthCheckStepResult(
                    step = WorkerHealthCheckStep.CONFIG_VALIDATION,
                    status = WorkerHealthCheckStatus.INVALID_CONFIG,
                    errorCode = WorkerHealthErrorCode.INVALID_WORKER_URL,
                ),
            ),
            latencyMs = 0,
            startedAt = now,
            finishedAt = now,
            errorCode = WorkerHealthErrorCode.INVALID_WORKER_URL,
        )
        assertEquals(WorkerHealthErrorCode.INVALID_WORKER_URL, result.errorCode)
    }
}

class WorkerHealthRepositoryPersistenceTest {
    @Test
    fun applyHealthUpdateDoesNotChangeSelectedWorker() {
        val pool = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val first = pool.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev"),
        ).getOrThrow()
        val second = pool.addWorker(
            WorkerEndpoint.create(name = "Worker 2", url = "two.user.workers.dev"),
        ).getOrThrow()
        pool.selectWorker(first.id)
        val selectedBefore = pool.getWorkerPoolConfig().selectedWorkerId
        pool.applyHealthUpdate(
            second.copy(
                state = WorkerHealthState.DEAD,
                failureCount = 3,
                lastErrorCode = WorkerHealthErrorCode.TIMEOUT.name,
                lastCheckedAt = System.currentTimeMillis(),
            ),
        )
        assertEquals(selectedBefore, pool.getWorkerPoolConfig().selectedWorkerId)
        assertEquals(WorkerHealthState.DEAD, pool.getWorker(second.id)?.state)
    }

    @Test
    fun checkAllEnabledWorkersSkipsDisabledInSummaryCount() {
        val pool = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        pool.addWorker(WorkerEndpoint.create(name = "Enabled", url = "enabled.user.workers.dev"))
        pool.addWorker(
            WorkerEndpoint.create(name = "Disabled", url = "disabled.user.workers.dev", enabled = false),
        )
        val enabledCount = pool.getWorkers().count { it.enabled }
        val disabledCount = pool.getWorkers().count { !it.enabled }
        assertEquals(1, enabledCount)
        assertEquals(1, disabledCount)
    }
}

class WorkerHealthReportMaskingTest {
    @Test
    fun masksWorkerUrlForHealthResult() {
        val masked = WorkerUrlSanitizer.maskForDisplay(
            "https://my-worker.example.workers.dev/path?secret=abc",
            maskDomains = true,
        )
        assertTrue(masked.contains("***"))
        assertTrue(!masked.contains("secret=abc"))
    }
}
