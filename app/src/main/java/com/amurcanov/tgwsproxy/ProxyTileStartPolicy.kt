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
        "port",
        "pool",
        "worker_domain",
        "dc1",
        "dc2",
        "dc4",
        "dc203",
        "flowseal_dc_only_enabled",
        "cf_manual_domains",
        "cfproxy_domain",
        "cf_upstream_domains",
        "cf_domain_mirror_enabled",
        "cf_domain_mirror_url",
        "cf_auto_update_domains",
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
            val storedSignature = prefs.getString(KEY_CONFIRMED_SIGNATURE, null)
            val currentSignature = signature(context, prefs, saved)
            if (isConfirmed(saved, storedSignature, currentSignature)) {
                true
            } else if (storedSignature != null || currentSignature == null) {
                false
            } else {
                // Pre-tile versions saved these fields only after a successful frontend start.
                // Permit a one-time migration only on the same network and route policy.
                val profile = NetworkProfileProvider.current(context)
                val effective = EffectiveRoutePolicyResolver(
                    NetworkRoutePolicyRepository(prefs),
                    prefs,
                ).resolve(profile)
                val policy = effective.policy
                val expected = mapOf(
                    "@network_profile_id" to profile.id,
                    "@network_profile_type" to profile.type.prefValue,
                    "@connection_mode" to effective.legacyMode.prefValue,
                    "@auto_strategy" to policy.autoStrategy.prefValue,
                    "@route_direct_ws" to flag(RouteKind.DIRECT_WS in policy.enabledRoutes),
                    "@route_worker_ws" to flag(RouteKind.WORKER_WS in policy.enabledRoutes),
                    "@route_cf_proxy_ws" to flag(RouteKind.CF_PROXY_WS in policy.enabledRoutes),
                    "@route_awg_warp" to flag(RouteKind.AWG_WARP in policy.enabledRoutes),
                    "@route_tcp_fallback" to flag(RouteKind.TCP_FALLBACK in policy.enabledRoutes),
                    "@preferred_route" to policy.preferredRoute?.prefValue.orEmpty(),
                    "@route_fallback" to flag(policy.allowFallback),
                )
                isLegacyReusable(
                    saved = saved,
                    savedBySuccessfulStart = prefs.contains("last_proxy_port") &&
                        prefs.contains("last_proxy_pool") &&
                        prefs.contains("last_runtime_ips"),
                    expectedTokens = expected,
                    currentAwgPath = AwgWarpConfigStore.configPath(context).orEmpty(),
                )
            }
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

    private fun flag(enabled: Boolean) = if (enabled) "1" else "0"

    // Compatibility for previously running installations without a tile confirmation hash.
    // Every required route/profile token must match the currently selected policy.
    internal fun isLegacyReusable(
        saved: ProxySavedConfig,
        savedBySuccessfulStart: Boolean,
        expectedTokens: Map<String, String>,
        currentAwgPath: String,
    ): Boolean {
        if (!savedBySuccessfulStart || !saved.isSufficientForStart() ||
            saved.runtimeConfig.isBlank() || expectedTokens.isEmpty()
        ) return false
        val tokens = saved.runtimeConfig.split(',')
            .filter { it.startsWith("@") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return expectedTokens.all { (key, value) -> tokens[key] == value } &&
            tokens["@awg_warp_config_path"].orEmpty() == currentAwgPath
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
            .filterKeys { key ->
                key in relevantPreferences ||
                    (key.startsWith("worker_pool_") &&
                        key != "worker_pool_round_robin_cursor_v1" &&
                        key != "worker_pool_migration_done_v1")
            }
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
