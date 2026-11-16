package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Debug-only, DUMP-protected CF-only device acceptance fixture. */
class CfProxySmokeConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
        val backup = context.getSharedPreferences("issue156_cf_backup", Context.MODE_PRIVATE)
        try {
            when (intent.action) {
                CONFIGURE -> {
                    check(!context.getSharedPreferences("issue101_smoke_backup", Context.MODE_PRIVATE)
                        .getBoolean("snapshot_exists", false)) { "Restore Worker smoke first" }
                    if (!backup.getBoolean("saved", false)) {
                        val save = backup.edit()
                        for (key in STRINGS) {
                            save.putBoolean("has_" + key, prefs.contains(key))
                            if (prefs.contains(key)) save.putString("val_" + key, prefs.getString(key, ""))
                        }
                        for (key in BOOLEANS) {
                            save.putBoolean("has_" + key, prefs.contains(key))
                            if (prefs.contains(key)) save.putBoolean("val_" + key, prefs.getBoolean(key, false))
                        }
                        for (key in INTS) {
                            save.putBoolean("has_" + key, prefs.contains(key))
                            if (prefs.contains(key)) save.putInt("val_" + key, prefs.getInt(key, 0))
                        }
                        check(save.putBoolean("saved", true).commit()) { "Backup failed" }
                    }
                    val policy = "active=cf_proxy_ws;inactive=awg_warp|worker_ws|direct_ws|tcp_fallback;" +
                        "order=cf_proxy_ws|awg_warp|worker_ws|direct_ws|tcp_fallback;strategy=" +
                        AutoStrategy.CF_PREFERRED.prefValue
                    val change = prefs.edit().putString("connection_mode", ConnectionMode.CFOnly.prefValue)
                        .putBoolean("cfproxy_enabled", true)
                        .putBoolean("cfproxy_priority", true)
                        .putBoolean("cfproxy_only", true)
                    for (type in listOf("wifi", "mobile", "unknown")) {
                        change.putString("route_policy_" + type + "_v2", policy)
                    }
                    check(change.commit()) { "CF-only configuration failed" }
                    setResultCode(101)
                }
                START -> {
                    check(backup.getBoolean("saved", false)) { "Configure CF-only first" }
                    val saved = ProxySavedConfigStore(prefs).load()
                    check(saved.frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
                        "MTProto frontend required for Telegram acceptance"
                    }
                    val config = ProxyRuntimeConfigFactory.fromPreferences(prefs).build(context)
                        ?: error("Invalid runtime configuration")
                    check(config.effectivePolicy.policy.activeRoutes == listOf(RouteKind.CF_PROXY_WS)) {
                        "CF-only policy not applied"
                    }
                    check(config.ips.contains("@route_order=cf_proxy_ws")) { "Native policy missing" }
                    ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_START
                        putExtra(ProxyService.EXTRA_PORT, config.port)
                        putExtra(ProxyService.EXTRA_IPS, config.ips)
                        putExtra(ProxyService.EXTRA_POOL_SIZE, config.poolSize)
                    })
                    setResultCode(101)
                }
                STOP -> {
                    context.startService(Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_STOP
                    })
                    setResultCode(101)
                }
                RESTORE -> {
                    if (backup.getBoolean("saved", false)) {
                        val change = prefs.edit()
                        for (key in STRINGS) {
                            if (backup.getBoolean("has_" + key, false))
                                change.putString(key, backup.getString("val_" + key, ""))
                            else change.remove(key)
                        }
                        for (key in BOOLEANS) {
                            if (backup.getBoolean("has_" + key, false))
                                change.putBoolean(key, backup.getBoolean("val_" + key, false))
                            else change.remove(key)
                        }
                        for (key in INTS) {
                            if (backup.getBoolean("has_" + key, false))
                                change.putInt(key, backup.getInt("val_" + key, 0))
                            else change.remove(key)
                        }
                        check(change.commit()) { "Restore failed" }
                        check(backup.edit().clear().commit()) { "Backup cleanup failed" }
                    }
                    setResultCode(101)
                }
                else -> setResultCode(102)
            }
        } catch (e: Exception) {
            setResultCode(102)
            setResultData(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private val STRINGS = listOf("connection_mode", "last_runtime_ips",
            "route_policy_wifi_v1", "route_policy_mobile_v1", "route_policy_unknown_v1",
            "route_policy_wifi_v2", "route_policy_mobile_v2", "route_policy_unknown_v2")
        private val BOOLEANS = listOf("cfproxy_enabled", "cfproxy_priority", "cfproxy_only",
            "last_proxy_port_default_1443_migrated")
        private val INTS = listOf("last_proxy_port", "last_proxy_pool", "route_policy_defaults_version")
        const val CONFIGURE = "io.github.regstar2.tgwsproxy.action.CF_SMOKE_CONFIGURE"
        const val START = "io.github.regstar2.tgwsproxy.action.CF_SMOKE_START"
        const val STOP = "io.github.regstar2.tgwsproxy.action.CF_SMOKE_STOP"
        const val RESTORE = "io.github.regstar2.tgwsproxy.action.CF_SMOKE_RESTORE"
    }
}
