package com.amurcanov.tgwsproxy.worker

import android.content.SharedPreferences
import android.util.Log
import com.amurcanov.tgwsproxy.WorkerDomain

object WorkerPoolMigration {
    private const val TAG = "TgWsProxy"
    private const val KEY_LEGACY_WORKER_DOMAIN = "worker_domain"

    fun applyIfNeeded(
        prefs: SharedPreferences,
        repository: WorkerPoolRepository,
    ) {
        if (repository.isMigrationCompleted() && repository.getWorkers().isNotEmpty()) {
            Log.i(TAG, "Worker pool migration skipped: already migrated")
            return
        }
        if (repository.getWorkers().isNotEmpty()) {
            repository.markMigrationCompleted()
            Log.i(TAG, "Worker pool migration skipped: already migrated")
            return
        }
        if (repository.isMigrationCompleted()) {
            Log.i(TAG, "Worker pool migration skipped: already migrated")
            return
        }

        Log.i(TAG, "Worker pool migration started")
        val legacyDomain = WorkerDomain.normalize(prefs.getString(KEY_LEGACY_WORKER_DOMAIN, "").orEmpty())
        if (legacyDomain.isBlank()) {
            repository.markMigrationCompleted()
            Log.i(TAG, "Worker pool migration skipped: no legacy worker domain")
            return
        }

        val worker = WorkerEndpoint.create(
            name = "Worker 1",
            url = legacyDomain,
            enabled = true,
        )
        repository.addWorker(worker).onSuccess { created ->
            repository.saveWorkerPoolConfig(
                repository.getWorkerPoolConfig().copy(selectedWorkerId = created.id),
            )
            repository.markMigrationCompleted()
            Log.i(
                TAG,
                "Worker pool migration completed: id=${created.id}, url=${WorkerUrlSanitizer.maskForLog(created.url)}",
            )
        }.onFailure {
            Log.w(TAG, "Worker pool migration failed: ${it.message}")
        }
    }
}
