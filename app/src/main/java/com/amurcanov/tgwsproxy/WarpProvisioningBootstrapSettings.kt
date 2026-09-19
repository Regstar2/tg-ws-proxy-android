package com.amurcanov.tgwsproxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal enum class ProvisioningWorkerHealthStatus {
    UNCHECKED,
    HEALTHY,
    FAILED,
}

internal data class ProvisioningWorkerEndpoint(
    val id: String,
    val url: String,
    val enabled: Boolean,
    val order: Int,
    val lastHealthStatus: ProvisioningWorkerHealthStatus,
    val lastCheckedAtMs: Long?,
    val lastErrorCode: String?,
)

internal data class WarpProvisioningBootstrapSettings(
    val useBuiltInWorkers: Boolean,
    val customWorkers: List<ProvisioningWorkerEndpoint>,
)

internal class WarpProvisioningBootstrapException(
    val code: String,
) : IllegalArgumentException(code)

internal object BuiltInWarpProvisioningWorkers {
    // Issue #88: keep project-provided provisioning endpoints centralized and separate from
    // the Telegram cf_worker_ws pool. These deployments are provisioning-only bootstrap
    // Workers and are never inferred from the user's normal Telegram Worker settings.
    val endpoints: List<String> = listOf(
        "https://floral-surf-cc2c.awpmxkmo.workers.dev",
        "https://lucky-frog-795f.ixmxdpw8.workers.dev",
        "https://steep-snow-3ae9.4048pm01.workers.dev",
    )
}

internal object ProvisioningWorkerUrlValidator {
    fun normalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) throw WarpProvisioningBootstrapException("worker_url_empty")

        val uri = runCatching { URI(trimmed) }
            .getOrElse { throw WarpProvisioningBootstrapException("worker_url_invalid") }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw WarpProvisioningBootstrapException("worker_url_https_required")
        }
        if (uri.rawUserInfo != null) {
            throw WarpProvisioningBootstrapException("worker_url_credentials_forbidden")
        }
        if (uri.host.isNullOrBlank()) {
            throw WarpProvisioningBootstrapException("worker_url_host_missing")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw WarpProvisioningBootstrapException("worker_url_query_forbidden")
        }
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") {
            throw WarpProvisioningBootstrapException("worker_url_path_forbidden")
        }

        return URI(
            "https",
            null,
            uri.host.lowercase(),
            uri.port,
            null,
            null,
            null,
        ).toASCIIString()
    }

    fun safeHost(rawUrl: String): String {
        return runCatching {
            val uri = URI(normalize(rawUrl))
            if (uri.port >= 0) "${uri.host}:${uri.port}" else uri.host
        }.getOrDefault("invalid")
    }
}

internal class WarpProvisioningBootstrapSettingsRepository(
    context: Context,
) {
    companion object {
        private const val PREFS_NAME = "WarpProvisioningBootstrapPrefs"
        private const val KEY_USE_BUILT_IN = "use_built_in_workers"
        private const val KEY_CUSTOM_WORKERS = "custom_workers_json"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): WarpProvisioningBootstrapSettings {
        val workers = decodeWorkers(prefs.getString(KEY_CUSTOM_WORKERS, null))
        return WarpProvisioningBootstrapSettings(
            useBuiltInWorkers = prefs.getBoolean(KEY_USE_BUILT_IN, true),
            customWorkers = workers.sortedBy { it.order },
        )
    }

    @Synchronized
    fun setUseBuiltInWorkers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_BUILT_IN, enabled).apply()
    }

    @Synchronized
    fun addCustomWorker(rawUrl: String): ProvisioningWorkerEndpoint {
        val normalizedUrl = ProvisioningWorkerUrlValidator.normalize(rawUrl)
        val settings = load()
        if (settings.customWorkers.any { it.url.equals(normalizedUrl, ignoreCase = true) }) {
            throw WarpProvisioningBootstrapException("worker_url_duplicate")
        }

        val worker = ProvisioningWorkerEndpoint(
            id = UUID.randomUUID().toString(),
            url = normalizedUrl,
            enabled = true,
            order = settings.customWorkers.size,
            lastHealthStatus = ProvisioningWorkerHealthStatus.UNCHECKED,
            lastCheckedAtMs = null,
            lastErrorCode = null,
        )
        saveWorkers(settings.customWorkers + worker)
        return worker
    }

    @Synchronized
    fun setCustomWorkerEnabled(id: String, enabled: Boolean): Boolean {
        val workers = load().customWorkers
        var changed = false
        val updated = workers.map { worker ->
            if (worker.id == id) {
                changed = true
                worker.copy(enabled = enabled)
            } else {
                worker
            }
        }
        if (changed) saveWorkers(updated)
        return changed
    }

    @Synchronized
    fun deleteCustomWorker(id: String): Boolean {
        val workers = load().customWorkers
        val filtered = workers.filterNot { it.id == id }
        if (filtered.size == workers.size) return false
        saveWorkers(filtered.mapIndexed { index, worker -> worker.copy(order = index) })
        return true
    }

    @Synchronized
    fun updateHealth(
        id: String,
        result: ProvisioningWorkerHealthCheckResult,
        checkedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val workers = load().customWorkers
        var changed = false
        val updated = workers.map { worker ->
            if (worker.id == id) {
                changed = true
                worker.copy(
                    lastHealthStatus = if (result.ok) {
                        ProvisioningWorkerHealthStatus.HEALTHY
                    } else {
                        ProvisioningWorkerHealthStatus.FAILED
                    },
                    lastCheckedAtMs = checkedAtMs,
                    lastErrorCode = result.errorCode,
                )
            } else {
                worker
            }
        }
        if (changed) saveWorkers(updated)
        return changed
    }

    private fun saveWorkers(workers: List<ProvisioningWorkerEndpoint>) {
        val array = JSONArray()
        workers.sortedBy { it.order }.forEach { worker ->
            array.put(
                JSONObject()
                    .put("id", worker.id)
                    .put("url", worker.url)
                    .put("enabled", worker.enabled)
                    .put("order", worker.order)
                    .put("lastHealthStatus", worker.lastHealthStatus.name)
                    .put("lastCheckedAtMs", worker.lastCheckedAtMs ?: JSONObject.NULL)
                    .put("lastErrorCode", worker.lastErrorCode ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY_CUSTOM_WORKERS, array.toString()).apply()
    }

    private fun decodeWorkers(raw: String?): List<ProvisioningWorkerEndpoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val id = value.optString("id").trim()
                    val url = runCatching {
                        ProvisioningWorkerUrlValidator.normalize(value.optString("url"))
                    }.getOrNull() ?: continue
                    if (id.isBlank()) continue

                    add(
                        ProvisioningWorkerEndpoint(
                            id = id,
                            url = url,
                            enabled = value.optBoolean("enabled", true),
                            order = value.optInt("order", index).coerceAtLeast(0),
                            lastHealthStatus = runCatching {
                                ProvisioningWorkerHealthStatus.valueOf(
                                    value.optString(
                                        "lastHealthStatus",
                                        ProvisioningWorkerHealthStatus.UNCHECKED.name,
                                    ),
                                )
                            }.getOrDefault(ProvisioningWorkerHealthStatus.UNCHECKED),
                            lastCheckedAtMs = value.optLong("lastCheckedAtMs", 0L).takeIf { it > 0L },
                            lastErrorCode = value.optString("lastErrorCode")
                                .trim()
                                .takeIf { it.isNotEmpty() && it != "null" },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
