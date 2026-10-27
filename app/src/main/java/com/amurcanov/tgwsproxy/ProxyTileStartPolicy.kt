package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Quick Settings must construct a new runtime from current settings on every start.
 * A previous successful app start is required for initial setup, but its network,
 * route tokens and signature must never be reused or treated as a freshness gate.
 */
internal enum class ProxyTileStartDecision {
    READY,
    SETUP_REQUIRED,
    CONFIG_CHANGED,
    CONFIRMATION_MISSING,
    FRONTEND_INVALID,
    NETWORK_CHANGED,
    ROUTE_POLICY_CHANGED,
    AWG_CHANGED,
    VALIDATION_FAILED,
}

internal data class ProxyTileStartCheck(
    val decision: ProxyTileStartDecision,
    val saved: ProxySavedConfig? = null,
)

internal object ProxyTileStartPolicy {
    private const val KEY_SUCCESSFUL_START = "quick_settings_successful_start_v3"

    fun canStart(context: Context): Boolean =
        startDecision(context) == ProxyTileStartDecision.READY

    fun startDecision(context: Context): ProxyTileStartDecision = startCheck(context).decision

    fun startCheck(context: Context): ProxyTileStartCheck {
        return try {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val previouslyStarted = hasSuccessfulStart(prefs)
            if (!previouslyStarted) {
                return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
            }

            val frontendType = LocalProxyFrontendRepository(prefs).load()
            val mtProto = if (frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
                MtProtoProxyConfigRepository(prefs).loadForDiagnostics()
            } else {
                null
            }
            evaluateFreshStart(previouslyStarted, mtProto) {
                ProxyRuntimeConfigFactory.fromPreferences(prefs).build(context)?.let { current ->
                    ProxySavedConfig(
                        port = current.port,
                        runtimeConfig = current.ips,
                        poolSize = current.poolSize,
                        frontendType = frontendType,
                    )
                }
            }
        } catch (error: Exception) {
            Log.w("ProxyTileStartPolicy", "TILE_VALIDATION_FAILED exception=${error.javaClass.simpleName}")
            ProxyTileStartCheck(ProxyTileStartDecision.VALIDATION_FAILED)
        }
    }

    /**
     * The builder must run for each start attempt: no cached last_runtime_ips or
     * signature-based rejection when switching networks or changing route policy.
     */
    internal fun evaluateFreshStart(
        previouslyStarted: Boolean,
        mtProto: MtProtoProxyConfig?,
        rebuild: () -> ProxySavedConfig?,
    ): ProxyTileStartCheck {
        if (!previouslyStarted) return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
        val current = rebuild()
            ?: return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
        if (!current.isSufficientForStart() || current.runtimeConfig.isBlank()) {
            return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
        }
        if (!isFrontendValid(current, mtProto)) {
            return ProxyTileStartCheck(ProxyTileStartDecision.FRONTEND_INVALID)
        }
        return ProxyTileStartCheck(ProxyTileStartDecision.READY, current)
    }

    private fun hasSuccessfulStart(prefs: SharedPreferences): Boolean {
        // Upgraded installations have last_* from ProxyService, but not this new marker.
        return prefs.getBoolean(KEY_SUCCESSFUL_START, false) ||
            (prefs.contains("last_proxy_port") &&
                prefs.contains("last_proxy_pool") &&
                prefs.contains("last_runtime_ips"))
    }

    fun confirmSuccessfulStart(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = ProxySavedConfigStore(prefs).load()
            if (saved.isSufficientForStart() && saved.runtimeConfig.isNotBlank()) {
                check(prefs.edit().putBoolean(KEY_SUCCESSFUL_START, true).commit()) {
                    "Tile confirmation persistence failed"
                }
                Log.i("ProxyTileStartPolicy", "TILE_CONFIRMATION_SAVED")
            } else {
                Log.w("ProxyTileStartPolicy", "TILE_CONFIRMATION_SKIPPED reason=INVALID_SAVED_STATE")
            }
        }.onFailure { error ->
            Log.w("ProxyTileStartPolicy", "TILE_CONFIRMATION_FAILED exception=${error.javaClass.simpleName}")
        }
    }

    internal fun isFrontendValid(
        saved: ProxySavedConfig,
        mtProto: MtProtoProxyConfig?,
    ): Boolean {
        if (!saved.isSufficientForStart()) return false
        if (saved.frontendType == LocalProxyFrontendType.SOCKS5) return true
        return mtProto != null &&
            mtProto.enabled &&
            mtProto.experimentalAcknowledged &&
            mtProto.port == saved.port &&
            MtProtoProxyConfigValidator.validate(mtProto).isValid
    }
}
