package com.amurcanov.tgwsproxy

import android.content.Context
import com.amurcanov.tgwsproxy.worker.SharedPreferencesWorkerPoolPersistence
import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerHealthState
import com.amurcanov.tgwsproxy.worker.WorkerPoolRepository

internal object WarpBootstrapWorkerCandidates {
    fun load(context: Context): List<String> {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val repository = WorkerPoolRepository(SharedPreferencesWorkerPoolPersistence(prefs))
        val workers = repository.getWorkers()
        val selected = repository.getSelectedWorker()
        val legacyDomain = prefs.getString("worker_domain", "").orEmpty()
        return resolve(selected, workers, legacyDomain)
    }

    internal fun resolve(
        selected: WorkerEndpoint?,
        workers: List<WorkerEndpoint>,
        legacyDomain: String,
    ): List<String> {
        val candidates = ArrayList<String>()

        fun addWorker(worker: WorkerEndpoint?) {
            if (worker == null || !worker.enabled || worker.state == WorkerHealthState.DEAD) return
            val domain = worker.normalizedDomain()
            if (domain.isNotBlank()) candidates += domain
        }

        addWorker(selected)
        workers.forEach(::addWorker)

        val legacy = WorkerDomain.normalize(legacyDomain)
        if (legacy.isNotBlank()) candidates += legacy

        return candidates
            .distinctBy { it.lowercase() }
            .map { "https://$it" }
    }
}
