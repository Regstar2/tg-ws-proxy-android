package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerPoolRepositoryTest {
    @Test
    fun addUpdateDeleteWorker() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val created = repository.addWorker(
            WorkerEndpoint.create(name = "Germany #1", url = "germany.user.workers.dev"),
        ).getOrThrow()
        assertEquals(1, repository.getWorkers().size)
        assertEquals("germany.user.workers.dev", created.normalizedDomain())

        val updated = repository.updateWorker(created.copy(name = "Germany #2")).getOrThrow()
        assertEquals("Germany #2", updated.name)

        repository.removeWorker(created.id)
        assertTrue(repository.getWorkers().isEmpty())
    }

    @Test
    fun selectWorkerPersists() {
        val persistence = InMemoryWorkerPoolPersistence()
        val repository = WorkerPoolRepository(persistence)
        val worker = repository.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev"),
        ).getOrThrow()
        repository.selectWorker(worker.id)
        assertEquals(worker.id, WorkerPoolRepository(persistence).getWorkerPoolConfig().selectedWorkerId)
    }

    @Test
    fun deleteSelectedWorkerSelectsNextEnabled() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val first = repository.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev"),
        ).getOrThrow()
        val second = repository.addWorker(
            WorkerEndpoint.create(name = "Worker 2", url = "two.user.workers.dev"),
        ).getOrThrow()
        repository.selectWorker(first.id)
        repository.removeWorker(first.id)
        assertEquals(second.id, repository.getWorkerPoolConfig().selectedWorkerId)
    }

    @Test
    fun disableSelectedWorkerSelectsNextEnabled() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val first = repository.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev"),
        ).getOrThrow()
        val second = repository.addWorker(
            WorkerEndpoint.create(name = "Worker 2", url = "two.user.workers.dev"),
        ).getOrThrow()
        repository.selectWorker(first.id)
        repository.setWorkerEnabled(first.id, false)
        assertEquals(second.id, repository.getWorkerPoolConfig().selectedWorkerId)
        assertEquals(WorkerHealthState.DISABLED, repository.getWorker(first.id)?.state)
    }

    @Test
    fun migrationCreatesSingleWorkerWithoutDuplicates() {
        val persistence = InMemoryWorkerPoolPersistence()
        val repository = WorkerPoolRepository(persistence)
        WorkerPoolMigration.applyIfNeeded(
            prefs = FakeLegacyWorkerPrefs("legacy.user.workers.dev"),
            repository = repository,
        )
        assertEquals(1, repository.getWorkers().size)
        assertEquals("Worker 1", repository.getWorkers().first().name)
        WorkerPoolMigration.applyIfNeeded(
            prefs = FakeLegacyWorkerPrefs("legacy.user.workers.dev"),
            repository = WorkerPoolRepository(persistence),
        )
        assertEquals(1, persistence.loadWorkers().size)
    }

    @Test
    fun workerRouteResolverUsesSelectedWorkerWhenPoolEnabled() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val worker = repository.addWorker(
            WorkerEndpoint.create(name = "Selected", url = "selected.user.workers.dev"),
        ).getOrThrow()
        repository.setPoolEnabled(true)
        repository.selectWorker(worker.id)
        val resolution = WorkerRouteResolver.resolve(repository, "legacy.user.workers.dev")
        assertTrue(resolution is WorkerRouteResolution.Pool)
        assertEquals("selected.user.workers.dev", (resolution as WorkerRouteResolution.Pool).domain)
    }

    @Test
    fun workerRouteResolverUsesLegacyWhenPoolDisabled() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        val resolution = WorkerRouteResolver.resolve(repository, "legacy.user.workers.dev")
        assertTrue(resolution is WorkerRouteResolution.Legacy)
        assertEquals("legacy.user.workers.dev", (resolution as WorkerRouteResolution.Legacy).domain)
    }

    @Test
    fun noEnabledWorkerReturnsError() {
        val repository = WorkerPoolRepository(InMemoryWorkerPoolPersistence())
        repository.addWorker(
            WorkerEndpoint.create(name = "Worker 1", url = "one.user.workers.dev", enabled = false),
        )
        repository.setPoolEnabled(true)
        val resolution = WorkerRouteResolver.resolve(repository, "")
        assertTrue(resolution is WorkerRouteResolution.Error)
        assertEquals(WorkerPoolError.NO_ENABLED_WORKER, (resolution as WorkerRouteResolution.Error).code)
    }
}

class WorkerEndpointValidatorTest {
    @Test
    fun validatesNameAndUrl() {
        assertNull(WorkerEndpointValidator.validate("Germany #1", "host.user.workers.dev"))
        assertEquals(WorkerValidationError.EMPTY_NAME, WorkerEndpointValidator.validate("", "host.user.workers.dev"))
        assertEquals(WorkerValidationError.INVALID_URL, WorkerEndpointValidator.validate("Worker", "not a url"))
    }
}

class WorkerUrlSanitizerTest {
    @Test
    fun masksWorkerUrl() {
        val masked = WorkerUrlSanitizer.maskForDisplay(
            "https://my-worker.example.workers.dev/path?secret=abc",
            maskDomains = true,
        )
        assertFalse(masked.contains("secret=abc"))
        assertTrue(masked.contains("***"))
    }
}

private class InMemoryWorkerPoolPersistence : WorkerPoolPersistence {
    private var config = WorkerPoolConfig()
    private var workers: List<WorkerEndpoint> = emptyList()
    private var migrated = false

    override fun loadConfig(): WorkerPoolConfig = config
    override fun saveConfig(config: WorkerPoolConfig) {
        this.config = config
    }

    override fun loadWorkers(): List<WorkerEndpoint> = workers
    override fun saveWorkers(workers: List<WorkerEndpoint>) {
        this.workers = workers
    }

    override fun isMigrationCompleted(): Boolean = migrated
    override fun markMigrationCompleted() {
        migrated = true
    }
}

private class FakeLegacyWorkerPrefs(
    private val domain: String,
) : android.content.SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf("worker_domain" to domain)
    override fun getString(key: String?, defValue: String?): String? {
        return if (key == "worker_domain") domain else defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = key == "worker_domain"
    override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
