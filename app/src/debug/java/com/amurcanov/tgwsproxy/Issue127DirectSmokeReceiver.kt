package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
                        ).resolve(NetworkProfileProvider.current(context))
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

                ACTION_STATUS -> {
                    val native = NativeProxy.getMtProtoProxyStatus().orEmpty()
                    check(native.isNotBlank()) { "MTProto native status unavailable" }
                    val fields = native.split(';').mapNotNull { item ->
                        val index = item.indexOf('=')
                        if (index < 1) null
                        else item.substring(0, index) to item.substring(index + 1)
                    }.toMap()
                    // Never expose the MTProto secret or its fingerprint in ADB output.
                    val safeFields = listOf(
                        "status", "port", "selected_backend", "actual_backend",
                        "route_reason", "active", "total", "fake_tls_accepted",
                        "fake_tls_rejected", "bytes_up", "bytes_down",
                    )
                    setResultCode(RESULT_OK)
                    setResultData(safeFields.joinToString(";") { name ->
                        "$name=${fields[name].orEmpty()}"
                    })
                }

                ACTION_PROBE -> {
                    // BroadcastReceiver.onReceive runs on Android's main thread.
                    // A real TCP dial must be dispatched off-main. goAsync holds
                    // the ordered ADB broadcast result until the probe finishes.
                    val pending = goAsync()
                    try {
                        Thread({
                            try {
                                val resultData = runDirectProbe(context, prefs, backup)
                                pending.setResultCode(RESULT_OK)
                                pending.setResultData(resultData)
                            } catch (exception: Exception) {
                                pending.setResultCode(RESULT_ERROR)
                                pending.setResultData(exception.message ?: exception.javaClass.simpleName)
                            } finally {
                                pending.finish()
                            }
                        }, "Issue127DirectProbe").start()
                    } catch (exception: Exception) {
                        pending.setResultCode(RESULT_ERROR)
                        pending.setResultData(exception.message ?: "probe_thread_start_failed")
                        pending.finish()
                    }
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


    // Invoked only from Issue127DirectProbe, never from the main/UI thread.
    private fun runDirectProbe(
        context: Context,
        prefs: SharedPreferences,
        backup: SharedPreferences,
    ): String {
        check(backup.getBoolean(SNAPSHOT_EXISTS, false)) {
            "Configure direct_ws-only before running synthetic probe"
        }
        check(NetworkProfileProvider.current(context).type == NetworkProfileType.WIFI) {
            "Wi-Fi network changed before synthetic probe"
        }
        val status = NativeProxy.getMtProtoProxyStatus().orEmpty()
        check(status.contains("status=LISTENING_ROUTE_READY;")) {
            "MTProto frontend is not listening"
        }
        check(status.contains(";selected_backend=direct_ws;")) {
            "Synthetic probe requires selected_backend=direct_ws"
        }
        val port = status.split(';').firstOrNull { it.startsWith("port=") }
            ?.substringAfter('=')?.toIntOrNull()
            ?: error("MTProto native listener port unavailable")
        val secretHex = MtProtoProxyConfigRepository(prefs).loadForDiagnostics().secret
        val init = Issue127SyntheticMtProtoClient.buildInit(secretHex)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1500)
            socket.getOutputStream().write(init)
            socket.getOutputStream().flush()
        }
        return "synthetic_mtproto_dc2_init_sent;port=$port"
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
        const val ACTION_STATUS = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_STATUS"
        const val ACTION_PROBE = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_PROBE"
        const val ACTION_STOP = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_STOP"
        const val ACTION_RESTORE = "io.github.regstar2.tgwsproxy.action.ISSUE127_DIRECT_RESTORE"
    }
}

/**
 * Test-only authenticated obfuscated2 init: triggers a real MTProto DC2 route
 * attempt without reading, recording or sending Telegram application messages.
 * Mirrors the existing Go mtproxyfrontend/session_test.go handshake construction.
 */
internal object Issue127SyntheticMtProtoClient {
    fun buildInit(secretHex: String): ByteArray {
        require(MtProtoProxyConfigValidator.isValidRawSecret(secretHex)) {
            "MTProto secret invalid for synthetic probe"
        }
        val secret = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val init = ByteArray(64).also { SecureRandom().nextBytes(it) }
        init[0] = 0x42 // avoid forbidden obfuscated2 prefixes
        init[4] = 0x41
        for (index in 56..59) init[index] = 0xee.toByte() // intermediate
        init[60] = 2 // Telegram DC2 (signed little-endian)
        init[61] = 0
        val keyMaterial = init.copyOfRange(8, 40) + secret
        val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(init.copyOfRange(40, 56)),
        )
        val encrypted = cipher.doFinal(init)
        System.arraycopy(encrypted, 56, init, 56, 8)
        return init
    }
}
