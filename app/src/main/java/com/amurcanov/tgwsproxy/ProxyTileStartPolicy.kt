package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import com.amurcanov.tgwsproxy.worker.SharedPreferencesWorkerPoolPersistence
import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import java.security.MessageDigest

/**
 * Tile starts are fail-closed: only the last successfully started configuration on the
 * same network and effective route policy can be reused outside the settings screen.
 */
internal enum class ProxyTileStartDecision {
    READY,
    SETUP_REQUIRED,
    CONFIG_CHANGED,
}

internal object ProxyTileStartPolicy {
    private const val KEY_CONFIRMED_SIGNATURE = "quick_settings_confirmed_start_v1"
    private const val KEY_CONFIRMED_SIGNATURE_V2 = "quick_settings_confirmed_start_v2"

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

    fun canStart(context: Context): Boolean =
        startDecision(context) == ProxyTileStartDecision.READY

    fun startDecision(context: Context): ProxyTileStartDecision = runCatching {
        val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = ProxySavedConfigStore(prefs).load()
        if (!saved.isSufficientForStart()) {
            return@runCatching ProxyTileStartDecision.SETUP_REQUIRED
        }

        val storedSignature = prefs.getString(KEY_CONFIRMED_SIGNATURE_V2, null)
        val previousSignature = if (storedSignature == null) {
            prefs.getString(KEY_CONFIRMED_SIGNATURE, null)
        } else {
            null
        }
        val currentSignature = signature(context, prefs, saved, stable = true)
        val oldSignatureMatches = previousSignature != null &&
            isConfirmed(
                saved,
                previousSignature,
                signature(context, prefs, saved, stable = false),
            )
        if (oldSignatureMatches) return@runCatching ProxyTileStartDecision.READY
        val legacyReusable = if (storedSignature == null &&
            previousSignature == null && currentSignature != null
        ) {
            // Only a successful configuration from older versions may be migrated.
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
        } else {
            false
        }
        if (previousSignature != null) {
            ProxyTileStartDecision.CONFIG_CHANGED
        } else {
            decide(saved, storedSignature, currentSignature, legacyReusable)
        }
    }.getOrDefault(ProxyTileStartDecision.CONFIG_CHANGED)

    internal fun decide(
        saved: ProxySavedConfig,
        storedSignature: String?,
        currentSignature: String?,
        legacyReusable: Boolean,
    ): ProxyTileStartDecision = when {
        !saved.isSufficientForStart() -> ProxyTileStartDecision.SETUP_REQUIRED
        isConfirmed(saved, storedSignature, currentSignature) -> ProxyTileStartDecision.READY
        storedSignature == null && currentSignature != null && legacyReusable ->
            ProxyTileStartDecision.READY
        else -> ProxyTileStartDecision.CONFIG_CHANGED
    }

    fun confirmSuccessfulStart(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = ProxySavedConfigStore(prefs).load()
            val signature = signature(context, prefs, saved, stable = true)
            if (saved.isSufficientForStart() && signature != null) {
                prefs.edit().putString(KEY_CONFIRMED_SIGNATURE_V2, signature).apply()
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
            saved.runtimeConfig.isBlank() || expectedTokens.isEmpty() ||
            currentAwgPath.isNotBlank()
        ) return false
        // Old versions did not save an AWG configuration timestamp, so an imported
        // WARP profile must be revalidated by starting from the app once.
        val tokens = saved.runtimeConfig.split(',')
            .filter { it.startsWith("@") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return expectedTokens.all { (key, value) -> tokens[key] == value } &&
            tokens["@awg_warp_config_path"].orEmpty() == currentAwgPath
    }

    // Endpoints are configuration; health checks and update timestamps are not.
    internal fun stableWorkerConfig(workers: List<WorkerEndpoint>): String =
        workers.sortedBy { it.id }.joinToString("\u0002") { worker ->
            listOf(
                worker.id,
                worker.name,
                worker.url,
                worker.transport.prefValue,
                worker.enabled.toString(),
                worker.priority.toString(),
                worker.weight.toString(),
                worker.createdAt.toString(),
            ).joinToString("\u0001")
        }

    private fun signature(
        context: Context,
        prefs: SharedPreferences,
        saved: ProxySavedConfig,
        stable: Boolean,
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
                (key in relevantPreferences && (!stable || key != "cf_upstream_domains")) ||
                    (key.startsWith("worker_pool_") &&
                        key != "worker_pool_round_robin_cursor_v1" &&
                        key != "worker_pool_migration_done_v1" &&
                        (!stable || key != "worker_pool_workers_v1"))
            }
            .toSortedMap()
            .entries.joinToString("|") { (key, value) -> "$key=$value" }
        val awgPath = AwgWarpConfigStore.configPath(context).orEmpty()
        val awgModified = awgPath.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it).lastModified() } ?: 0L
        val workerConfig = if (stable) {
            val workers = SharedPreferencesWorkerPoolPersistence(prefs).loadWorkers()
            stableWorkerConfig(workers)
        } else {
            null
        }
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
        ).let { base ->
            if (workerConfig != null) base + workerConfig else base
        }.plus(listOf(awgPath, awgModified.toString())).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
