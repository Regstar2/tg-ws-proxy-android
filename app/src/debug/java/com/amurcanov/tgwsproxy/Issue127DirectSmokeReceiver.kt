package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

/**
 * Debug-only, shell-permission-protected A/B fixture for Issue #127.
 *
 * Snapshots Wi-Fi routing and last runtime parameters before selecting
 * direct_ws-only. Mobile settings, Telegram data and CF domain pools are never
 * modified. Repeated CONFIGURE calls reuse the original snapshot so an APK
 * replacement cannot accidentally discard the user's route preferences.
 */
class Issue127DirectSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
        val backup = context.getSharedPreferences(BACKUP_NAME, Context.MODE_PRIVATE)
        try {
            when (intent.action) {
                ACTION_CONFIGURE -> {
                    require(NetworkProfileProvider.current(context).type == NetworkProfileType.WIFI) {
                        "Active Android network must be Wi-Fi, not VPN/mobile"
                    }
                    if (!backup.getBoolean(SNAPSHOT_EXISTS, false)) {
                        val editor = backup.edit()
                        STRING_KEYS.forEach { key ->
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putString("value_$key", prefs.getString(key, ""))
                            }
                        }
                        INT_KEYS.forEach { key ->
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putInt("value_$key", prefs.getInt(key, 0))
                            }
                        }
                        BOOLEAN_KEYS.forEach { key ->
                            editor.putBoolean("exists_$key", prefs.contains(key))
                            if (prefs.contains(key)) {
                                editor.putBoolean("value_$key", prefs.getBoolean(key, false))
                            }
                        }
                        check(editor.putBoolean(SNAPSHOT_EXISTS, true).commit()) {
                            "Could not persist the original Wi-Fi route-policy snapshot"
                        }
                    }

                    val direct = NetworkRoutePolicyEditor.normalize(
                        NetworkRoutePolicyMapper.fromConnectionMode(
                            NetworkProfileType.WIFI,
                            ConnectionMode.DirectOnly,
                            AutoStrategy.CF_PREFERRED,
                        ),
                    )
                    val encoded = "active=" + direct.activeRoutes.joinToString("|") { it.prefValue } +
                        ";inactive=" + direct.inactiveRoutes.joinToString("|") { it.prefValue } +
                        ";order=" + direct.displayOrder.joinToString("|") { it.prefValue } +
                        ";strategy=" + direct.autoStrategy.prefValue
                    check(prefs.edit().putString("route_policy_wifi_v2", encoded).commit()) {
                        "Could not apply direct_ws-only Wi-Fi policy"
                    }
                    check(
                        EffectiveRoutePolicyResolver(
                            NetworkRoutePolicyRepository(prefs),
                            prefs,
                        ).resolve(NetworkProfile(NetworkProfileType.WIFI, "", false))
                            .policy.activeRoutes == listOf(RouteKind.DIRECT_WS)
                    ) { "direct_ws-only policy was not applied" }
                    setResultCode(RESULT_OK)
                    setResultData("configured_direct_ws_wifi_only")
                }

                ACTION_START -> {
                    check(backup.getBoolean(SNAPSHOT_EXISTS, false)) {
                        "Configure direct_ws test before starting"
                    }
                    check(NetworkProfileProvider.current(context).type == NetworkProfileType.WIFI) {
                        "Active network changed from Wi-Fi"
                    }
                    val saved = ProxySavedConfigStore(prefs).load()
                    check(saved.frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
                        "MTProto frontend is required for direct_ws acceptance"
                    }
                    val config = ProxyRuntimeConfigFactory.fromPreferences(prefs).build(context)
                        ?: error("Cannot build direct_ws runtime config")
                    check(config.effectivePolicy.policy.activeRoutes == listOf(RouteKind.DIRECT_WS)) {
                        "Runtime policy is not direct_ws-only"
                    }
                    check(config.ips.isNotBlank()) { "Runtime config is empty" }
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ProxyService::class.java).apply {
                            action = ProxyService.ACTION_START
                            putExtra(ProxyService.EXTRA_PORT, config.port)
                            putExtra(ProxyService.EXTRA_IPS, config.ips)
                            putExtra(ProxyService.EXTRA_POOL_SIZE, config.poolSize)
                        },
                    )
                    setResultCode(RESULT_OK)
                    setResultData("start_requested_direct_ws")
                }

                ACTION_STOP -> {
                    context.startService(Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_STOP
                    })
                    setResultCode(RESULT_OK)
                    setResultData("stop_requested")
                }

                ACTION_RESTORE -> {
                    if (backup.getBoolean(SNAPSHOT_EXISTS, false)) {
                        val editor = prefs.edit()
                        STRING_KEYS.forEach { key ->
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putString(key, backup.getString("value_$key", ""))
                            } else {
                                editor.remove(key)
                            }
                        }
                        INT_KEYS.forEach { key ->
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putInt(key, backup.getInt("value_$key", 0))
                            } else {
                                editor.remove(key)
                            }
                        }
                        BOOLEAN_KEYS.forEach { key ->
                            if (backup.getBoolean("exists_$key", false)) {
                                editor.putBoolean(key, backup.getBoolean("value_$key", false))
                            } else {
                                editor.remove(key)
                            }
                        }
                        check(editor.commit()) { "Could not restore original Wi-Fi settings" }
                        check(backup.edit().clear().commit()) {
                            "Could not clear successful test backup"
                        }
                    }
                    setResultCode(RESULT_OK)
                    setResultData("original_wifi_settings_restored")
                }

                else -> {
                    setResultCode(RESULT_ERROR)
                    setResultData("unknown_action")
                }
            }
        } catch (e: Exception) {
            setResultCode(RESULT_ERROR)
            setResultData(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val BACKUP_NAME = "issue127_direct_smoke_backup"
        private const val SNAPSHOT_EXISTS = "snapshot_exists"
        private const val RESULT_OK = 101
        private const val RESULT_ERROR = 102
        private val STRING_KEYS = listOf(
            "route_policy_wifi_v1", "route_policy_wifi_v2", "last_runtime_ips",
        )
        private val INT_KEYS = listOf("last_proxy_port", "last_proxy_pool")
        private val BOOLEAN_KEYS = listOf("last_proxy_port_default_1443_migrated")

        const val ACTION_CONFIGURE = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_CONFIGURE"
        const val ACTION_START = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_START"
        const val ACTION_STOP = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_STOP"
        const val ACTION_RESTORE = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_RESTORE"
    }
}
