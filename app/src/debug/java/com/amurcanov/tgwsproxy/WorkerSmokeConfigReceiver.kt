package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.amurcanov.tgwsproxy.worker.WorkerTransport

/**
 * Debug-only ADB fixture for issue #101 device acceptance.
 *
 * Does not alter routing policy, the saved Worker list, or user credentials.
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
                        check(editor.putBoolean("snapshot_exists", true).commit()) {
                            "Could not back up Worker settings"
                        }
                    }
                    check(prefs.edit()
                        .putString("worker_domain", domain)
                        .putString("worker_transport", transport)
                        .putBoolean("worker_enabled", true)
                        .putBoolean("worker_pool_enabled_v1", false)
                        .putBoolean("worker_pool_migration_done_v1", true)
                        .commit()
                    ) { "Could not configure temporary Worker settings" }
                    setResultCode(RESULT_OK)
                    setResultData("configured")
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
        private val STRING_KEYS = listOf("worker_domain", "worker_transport")
        private val BOOLEAN_KEYS = listOf(
            "worker_enabled", "worker_pool_enabled_v1", "worker_pool_migration_done_v1",
        )
        const val ACTION_CONFIGURE = "com.amurcanov.tgwsproxy.action.WORKER_SMOKE_CONFIGURE"
        const val ACTION_RESTORE = "com.amurcanov.tgwsproxy.action.WORKER_SMOKE_RESTORE"
    }
}
