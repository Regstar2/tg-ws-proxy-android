package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.amurcanov.tgwsproxy.worker.DcIpTexts
import com.amurcanov.tgwsproxy.worker.FlowsealDcPreset
import com.amurcanov.tgwsproxy.worker.WorkerDestinationMode
import com.amurcanov.tgwsproxy.worker.WorkerTransport

/**
 * Debug-only ADB fixture for issue #101 device acceptance.
 *
 * Temporarily sets Worker-only routing across network profiles, preserving the
 * original route policy, Worker list and selected single Worker for restoration.
 * The smoke script must stop the application before sending each command.
 */
class WorkerSmokeConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val backup = context.getSharedPreferences("issue101_smoke_backup", Context.MODE_PRIVATE)

        try {
            when (intent.action) {
                ACTION_CONFIGURE -> {
                    val domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
                    val normalized = WorkerDomain.normalize(domain)
                    require(domain == normalized && WorkerDomain.validationWarning(domain) == null) {
                        "Expected a plain, valid workers.dev hostname"
                    }
                    val transport = intent.getStringExtra(EXTRA_TRANSPORT).orEmpty()
                    require(WorkerTransport.entries.any { it.prefValue == transport }) {
                        "Unknown Worker transport"
                    }
                    val dc2Target = intent.getStringExtra(EXTRA_DC2_TARGET).orEmpty()
                    require(dc2Target.isEmpty() ||
                        (transport == WorkerTransport.LEGACY_WEBSOCKET.prefValue &&
                            dc2Target in DC2_AB_TARGETS)) {
                        "DC2 comparison allows only legacy_websocket with approved DC2 targets"
                    }
                    if (!backup.getBoolean("snapshot_exists", false)) {
                        val editor = backup.edit()
                        for (key in STRING_KEYS) {
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putString("value_$key", prefs.getString(key, ""))
                            }
                        }
                        for (key in BOOLEAN_KEYS) {
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putBoolean("value_$key", prefs.getBoolean(key, false))
                            }
                        }
                        editor.putBoolean("exists_route_policy_defaults_version", prefs.contains("route_policy_defaults_version"))
                        if (prefs.contains("route_policy_defaults_version")) {
                            editor.putInt("value_route_policy_defaults_version", prefs.getInt("route_policy_defaults_version", 0))
                        }
                        for (key in INT_KEYS) {
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putInt("value_$key", prefs.getInt(key, 0))
                            }
                        }
                        check(editor.putBoolean("snapshot_exists", true).commit()) {
                            "Could not back up Worker settings"
                        }
                    }
                    val testEditor = prefs.edit()
                        .putString("worker_domain", domain)
                        .putString("worker_transport", transport)
                        .putBoolean("worker_enabled", true)
                        .putBoolean("worker_pool_enabled_v1", false)
                        .putBoolean("worker_pool_migration_done_v1", true)
                        .putString("connection_mode", ConnectionMode.WorkerOnly.prefValue)
                        .putString("route_policy_wifi_v1", workerOnlyPolicy)
                        .putString("route_policy_mobile_v1", workerOnlyPolicy)
                        .putString("route_policy_unknown_v1", workerOnlyPolicy)
                        .putInt("route_policy_defaults_version", RouteDefaultsMigration.DEFAULTS_VERSION_1800)
                    if (dc2Target.isNotEmpty()) {
                        // Keep logical DC2 intact: only the Worker TCP destination
                        // is varied via the existing FLOWSEAL_DC_MAP runtime path.
                        testEditor.putString(DcIpTexts.KEY_DC2, dc2Target)
                            .putString(
                                ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE,
                                WorkerDestinationMode.FLOWSEAL_DC_MAP.prefValue,
                            )
                            .putBoolean(FlowsealDcPreset.PREF_ENABLED, false)
                    }
                    check(testEditor.commit()) { "Could not configure temporary Worker settings" }
                    setResultCode(RESULT_OK)
                    setResultData("configured")
                }
                ACTION_START -> {
                    val saved = ProxySavedConfigStore(prefs).load()
                    val factory = ProxyRuntimeConfigFactory(
                        prefs = prefs,
                        routePolicyRepository = NetworkRoutePolicyRepository(prefs),
                        adaptiveRouteStatsRepository = AdaptiveRouteStatsRepository(prefs),
                        cfUpstreamDomainsProvider = { emptyList() },
                        manualCfDomainsProvider = { emptyList() },
                        workerDomainProvider = { prefs.getString("worker_domain", "").orEmpty() },
                        workerDestinationModeProvider = {
                            WorkerDestinationMode.fromPref(
                                prefs.getString(ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE, null),
                            )
                        },
                        flowsealMediaFixEnabledProvider = {
                            prefs.getBoolean(ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_ENABLED, false)
                        },
                        flowsealMediaFixDcProvider = {
                            prefs.getInt(
                                ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_DC,
                                WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC,
                            )
                        },
                        flowsealMediaFixIpProvider = {
                            prefs.getString(
                                ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_IP,
                                WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP,
                            ) ?: WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP
                        },
                        dcEntriesProvider = {
                            FlowsealDcPreset.dcEntries(
                                FlowsealDcPreset.loadTexts(prefs),
                                prefs.getBoolean(FlowsealDcPreset.PREF_ENABLED, false),
                            )
                        },
                        poolSizeProvider = { prefs.getInt("pool", 4) },
                        portProvider = { saved.port },
                    )
                    check(backup.getBoolean("snapshot_exists", false)) { "Missing test configuration backup" }
                    val config = factory.build(context)
                        ?: error("Worker smoke runtime config invalid")
                    check(config.ips.isNotBlank()) { "Worker runtime config is empty" }
                    val startIntent = Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_START
                        putExtra(ProxyService.EXTRA_PORT, config.port)
                        putExtra(ProxyService.EXTRA_IPS, config.ips)
                        putExtra(ProxyService.EXTRA_POOL_SIZE, config.poolSize)
                    }
                    ContextCompat.startForegroundService(context, startIntent)
                    setResultCode(RESULT_OK)
                    setResultData("start_requested")
                }
                ACTION_STOP -> {
                    context.startService(Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_STOP
                    })
                    setResultCode(RESULT_OK)
                    setResultData("stop_requested")
                }
                ACTION_RESTORE -> {
                    if (backup.getBoolean("snapshot_exists", false)) {
                        val editor = prefs.edit()
                        for (key in STRING_KEYS) {
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putString(key, backup.getString("value_$key", ""))
                            } else {
                                editor.remove(key)
                            }
                        }
                        for (key in BOOLEAN_KEYS) {
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putBoolean(key, backup.getBoolean("value_$key", false))
                            } else {
                                editor.remove(key)
                            }
                        }
                        if (backup.getBoolean("exists_route_policy_defaults_version", false)) {
                            editor.putInt(
                                "route_policy_defaults_version",
                                backup.getInt("value_route_policy_defaults_version", 0),
                            )
                        } else {
                            editor.remove("route_policy_defaults_version")
                        }
                        for (key in INT_KEYS) {
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putInt(key, backup.getInt("value_$key", 0))
                            } else {
                                editor.remove(key)
                            }
                        }
                        check(editor.commit()) { "Could not restore original Worker settings" }
                        check(backup.edit().clear().commit()) { "Could not clear Worker backup" }
                    }
                    setResultCode(RESULT_OK)
                    setResultData("restored")
                }
                else -> {
                    setResultCode(RESULT_ERROR)
                    setResultData("unknown_action")
                }
            }
        } catch (exception: Exception) {
            setResultCode(RESULT_ERROR)
            setResultData(exception.message ?: exception.javaClass.simpleName)
        }
    }

    companion object {
        private const val RESULT_OK = 101
        private const val RESULT_ERROR = 102
        private const val EXTRA_DOMAIN = "domain"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_DC2_TARGET = "dc2_target"
        private val DC2_AB_TARGETS = setOf("149.154.167.51", "149.154.167.220")
        private val STRING_KEYS = listOf(
            "worker_domain", "worker_transport", "connection_mode", "last_runtime_ips",
            "route_policy_wifi_v1", "route_policy_mobile_v1", "route_policy_unknown_v1",
            DcIpTexts.KEY_DC2, ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE,
        )
        private val workerOnlyPolicy = "routes=worker_ws;preferred=worker_ws;" +
            "strategy=${AutoStrategy.CF_PREFERRED.prefValue};fallback=0"
        private val BOOLEAN_KEYS = listOf(
            "worker_enabled", "worker_pool_enabled_v1", "worker_pool_migration_done_v1",
            "last_proxy_port_default_1443_migrated", FlowsealDcPreset.PREF_ENABLED,
        )
        private val INT_KEYS = listOf("last_proxy_port", "last_proxy_pool")
        const val ACTION_START = "io.github.regstar2.tgwsproxy.action.WORKER_SMOKE_START"
        const val ACTION_STOP = "io.github.regstar2.tgwsproxy.action.WORKER_SMOKE_STOP"
        const val ACTION_CONFIGURE = "io.github.regstar2.tgwsproxy.action.WORKER_SMOKE_CONFIGURE"
        const val ACTION_RESTORE = "io.github.regstar2.tgwsproxy.action.WORKER_SMOKE_RESTORE"
    }
}
