package com.amurcanov.tgwsproxy.worker

import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface WorkerPoolPersistence {
    fun loadConfig(): WorkerPoolConfig
    fun saveConfig(config: WorkerPoolConfig)
    fun loadWorkers(): List<WorkerEndpoint>
    fun saveWorkers(workers: List<WorkerEndpoint>)
    fun isMigrationCompleted(): Boolean
    fun markMigrationCompleted()
}

class SharedPreferencesWorkerPoolPersistence(
    private val prefs: SharedPreferences,
) : WorkerPoolPersistence {
    override fun loadConfig(): WorkerPoolConfig {
        return WorkerPoolConfig(
            enabled = prefs.getBoolean(KEY_POOL_ENABLED, false),
            selectedWorkerId = prefs.getString(KEY_SELECTED_WORKER_ID, null)?.takeIf { it.isNotBlank() },
            fallbackToSingleWorkerUrl = prefs.getBoolean(KEY_FALLBACK_LEGACY, true),
            defaultWorkerNamePattern = prefs.getString(KEY_NAME_PATTERN, "Worker %d") ?: "Worker %d",
            selectionMode = WorkerSelectionMode.fromPref(prefs.getString(KEY_SELECTION_MODE, null))
                ?: WorkerSelectionMode.SELECTED_ONLY,
        )
    }

    override fun saveConfig(config: WorkerPoolConfig) {
        prefs.edit()
            .putBoolean(KEY_POOL_ENABLED, config.enabled)
            .putString(KEY_SELECTED_WORKER_ID, config.selectedWorkerId)
            .putBoolean(KEY_FALLBACK_LEGACY, config.fallbackToSingleWorkerUrl)
            .putString(KEY_NAME_PATTERN, config.defaultWorkerNamePattern)
            .putString(KEY_SELECTION_MODE, config.selectionMode.prefValue)
            .apply()
    }

    override fun loadWorkers(): List<WorkerEndpoint> {
        val raw = prefs.getString(KEY_WORKERS, "").orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.lineSequence()
            .mapNotNull { line -> decodeWorker(line) }
            .toList()
    }

    override fun saveWorkers(workers: List<WorkerEndpoint>) {
        val encoded = workers.joinToString("\n") { encodeWorker(it) }
        prefs.edit().putString(KEY_WORKERS, encoded).apply()
    }

    override fun isMigrationCompleted(): Boolean {
        return prefs.getBoolean(KEY_MIGRATION_DONE, false)
    }

    override fun markMigrationCompleted() {
        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
    }

    private fun encodeWorker(worker: WorkerEndpoint): String {
        val fields = linkedMapOf(
            "id" to worker.id,
            "name" to worker.name,
            "url" to worker.url,
            "enabled" to if (worker.enabled) "1" else "0",
            "state" to worker.state.prefValue,
            "priority" to worker.priority.toString(),
            "weight" to worker.weight.toString(),
            "lastSuccessAt" to worker.lastSuccessAt?.toString().orEmpty(),
            "lastFailureAt" to worker.lastFailureAt?.toString().orEmpty(),
            "latencyMs" to worker.latencyMs?.toString().orEmpty(),
            "failureCount" to worker.failureCount.toString(),
            "createdAt" to worker.createdAt.toString(),
            "updatedAt" to worker.updatedAt.toString(),
        )
        return fields.entries.joinToString(";") { (key, value) ->
            "$key=${encode(value)}"
        }
    }

    private fun decodeWorker(line: String): WorkerEndpoint? {
        return runCatching {
            val fields = line.split(';')
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index) to decode(part.substring(index + 1))
                }
                .toMap()
            val id = fields["id"].orEmpty()
            if (id.isBlank()) return@runCatching null
            WorkerEndpoint(
                id = id,
                name = fields["name"].orEmpty(),
                url = fields["url"].orEmpty(),
                enabled = fields["enabled"] == "1",
                priority = fields["priority"]?.toIntOrNull() ?: 0,
                weight = fields["weight"]?.toIntOrNull() ?: 1,
                state = WorkerHealthState.fromPref(fields["state"]),
                lastSuccessAt = fields["lastSuccessAt"]?.toLongOrNull(),
                lastFailureAt = fields["lastFailureAt"]?.toLongOrNull(),
                latencyMs = fields["latencyMs"]?.toLongOrNull(),
                failureCount = fields["failureCount"]?.toIntOrNull() ?: 0,
                createdAt = fields["createdAt"]?.toLongOrNull() ?: 0L,
                updatedAt = fields["updatedAt"]?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    companion object {
        private const val KEY_POOL_ENABLED = "worker_pool_enabled_v1"
        private const val KEY_SELECTED_WORKER_ID = "worker_pool_selected_id_v1"
        private const val KEY_FALLBACK_LEGACY = "worker_pool_fallback_legacy_v1"
        private const val KEY_NAME_PATTERN = "worker_pool_name_pattern_v1"
        private const val KEY_SELECTION_MODE = "worker_pool_selection_mode_v1"
        private const val KEY_WORKERS = "worker_pool_workers_v1"
        private const val KEY_MIGRATION_DONE = "worker_pool_migration_done_v1"
    }
}
