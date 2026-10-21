package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Tile starts are fail-closed: only the last successfully started configuration on the
 * same network and effective route policy can be reused outside the settings screen.
 */
internal object ProxyTileStartPolicy {
    private const val KEY_CONFIRMED_SIGNATURE = "quick_settings_confirmed_start_v1"

    private val relevantPreferences = setOf(
        "connection_mode",
        "auto_strategy",
        ProxyRuntimeConfigFactory.KEY_CFPROXY_ENABLED,
        ProxyRuntimeConfigFactory.KEY_CFPROXY_PRIORITY,
        ProxyRuntimeConfigFactory.KEY_CFPROXY_ONLY,
        ProxyRuntimeConfigFactory.KEY_WORKER_ENABLED,
        ProxyRuntimeConfigFactory.KEY_WORKER_TRANSPORT,
        ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE,
        ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_ENABLED,
        ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_DC,
        ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_IP,
    )

    fun canStart(context: Context): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = ProxySavedConfigStore(prefs).load()
            isConfirmed(
                saved = saved,
                storedSignature = prefs.getString(KEY_CONFIRMED_SIGNATURE, null),
                currentSignature = signature(context, prefs, saved),
            )
        }.getOrDefault(false)
    }

    fun confirmSuccessfulStart(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = ProxySavedConfigStore(prefs).load()
            val signature = signature(context, prefs, saved)
            if (saved.isSufficientForStart() && signature != null) {
                prefs.edit().putString(KEY_CONFIRMED_SIGNATURE, signature).apply()
            }
        }
    }

    internal fun isConfirmed(
        saved: ProxySavedConfig,
        storedSignature: String?,
        currentSignature: String?,
    ): Boolean {
        return saved.isSufficientForStart() &&
            !storedSignature.isNullOrBlank() &&
            currentSignature != null &&
            storedSignature == currentSignature
    }

    private fun signature(
        context: Context,
        prefs: SharedPreferences,
        saved: ProxySavedConfig,
    ): String? {
        if (!saved.isSufficientForStart()) return null
        val mtProto = if (saved.frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
            val values = prefs.all
            // loadForDiagnostics may return generated defaults on corrupt/missing preferences.
            if (values[MtProtoProxyConfigRepository.KEY_SECRET] !is String ||
                values[MtProtoProxyConfigRepository.KEY_HOST] !is String ||
                values[MtProtoProxyConfigRepository.KEY_PORT] !is Int
            ) return null
            val config = MtProtoProxyConfigRepository(prefs).loadForDiagnostics()
            if (!config.enabled ||
                !config.experimentalAcknowledged ||
                !MtProtoProxyConfigValidator.validate(config).isValid ||
                config.port != saved.port
            ) return null
            config.toString()
        } else {
            ""
        }
        val profile = NetworkProfileProvider.current(context)
        val effective = EffectiveRoutePolicyResolver(
            NetworkRoutePolicyRepository(prefs),
            prefs,
        ).resolve(profile)
        val relevant = prefs.all
            .filterKeys { it in relevantPreferences }
            .toSortedMap()
            .entries.joinToString("|") { (key, value) -> "$key=$value" }
        val awgPath = AwgWarpConfigStore.configPath(context).orEmpty()
        val awgModified = awgPath.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it).lastModified() } ?: 0L
        val text = listOf(
            saved.frontendType.prefValue,
            saved.port.toString(),
            saved.poolSize.toString(),
            saved.runtimeConfig,
            mtProto,
            profile.id,
            profile.type.name,
            effective.source.name,
            effective.legacyMode.name,
            effective.policy.toString(),
            relevant,
            awgPath,
            awgModified.toString(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
