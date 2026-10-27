package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    private const val KEY_CONFIRMED_SIGNATURE = "quick_settings_confirmed_start_v1"
    private const val KEY_CONFIRMED_SIGNATURE_V2 = "quick_settings_confirmed_start_v2"
    private const val KEY_AWG_CONFIRMED_MTIME = "quick_settings_awg_modified_v1"

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

    fun startDecision(context: Context): ProxyTileStartDecision = startCheck(context).decision

    fun startCheck(context: Context): ProxyTileStartCheck {
        return try {
            val prefs = context.getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = ProxySavedConfigStore(prefs).load()
            if (!saved.isSufficientForStart()) {
                return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
            }

            val mtProto = if (saved.frontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
                MtProtoProxyConfigRepository(prefs).loadForDiagnostics()
            } else {
                null
            }
            if (!isFrontendValid(saved, mtProto)) {
                return ProxyTileStartCheck(ProxyTileStartDecision.FRONTEND_INVALID)
            }

            val confirmedV2 = prefs.getString(KEY_CONFIRMED_SIGNATURE_V2, null)
            val confirmedV1 = if (confirmedV2 == null) {
                prefs.getString(KEY_CONFIRMED_SIGNATURE, null)
            } else {
                null
            }
            val confirmedByService = prefs.contains("last_proxy_port") &&
                prefs.contains("last_proxy_pool") &&
                prefs.contains("last_runtime_ips")
            if (confirmedV2 == null && confirmedV1 == null && !confirmedByService) {
                return ProxyTileStartCheck(ProxyTileStartDecision.SETUP_REQUIRED)
            }

            val currentV2 = if (confirmedV2 != null) {
                signature(context, prefs, saved, stable = true)
            } else null
            val currentV1 = if (confirmedV1 != null) {
                signature(context, prefs, saved, stable = false)
            } else null

            // No unsigned legacy fallback is allowed once a v1/v2 confirmation exists.
            val legacyReusable = if (confirmedV2 == null && confirmedV1 == null) {
                // A Worker pool cannot be validated from route tokens alone.
                val workerPoolConfigured = prefs.getBoolean("worker_pool_enabled_v1", false) ||
                    prefs.getBoolean(ProxyRuntimeConfigFactory.KEY_WORKER_ENABLED, false)
                val awgPath = AwgWarpConfigStore.configPath(context).orEmpty()
                val awgConfirmed = awgPath.isBlank() ||
                    (prefs.contains(KEY_AWG_CONFIRMED_MTIME) &&
                        prefs.getLong(KEY_AWG_CONFIRMED_MTIME, -1L) ==
                            java.io.File(awgPath).lastModified())
                !workerPoolConfigured && isLegacyReusable(
                    saved = saved,
                    savedBySuccessfulStart = confirmedByService,
                    expectedTokens = expectedRuntimeTokens(context, prefs),
                    currentAwgPath = awgPath,
                    awgPreviouslyConfirmed = awgConfirmed,
                )
            } else false
            val checkedDecision = decideVersioned(
                saved, confirmedV2, currentV2, confirmedV1, currentV1, legacyReusable,
            )
            val decision = if (checkedDecision == ProxyTileStartDecision.CONFIG_CHANGED) {
                mismatchReason(context, prefs, saved)
            } else checkedDecision
            ProxyTileStartCheck(decision, if (decision == ProxyTileStartDecision.READY) saved else null)
        } catch (error: Exception) {
            Log.w("ProxyTileStartPolicy", "TILE_VALIDATION_FAILED exception=${error.javaClass.simpleName}")
            ProxyTileStartCheck(ProxyTileStartDecision.VALIDATION_FAILED)
        }
    }

    private fun mismatchReason(
        context: Context,
        prefs: SharedPreferences,
        saved: ProxySavedConfig,
    ): ProxyTileStartDecision {
        val awgPath = AwgWarpConfigStore.configPath(context).orEmpty()
        val awgMtimeChanged = awgPath.isNotBlank() &&
            prefs.contains(KEY_AWG_CONFIRMED_MTIME) &&
            prefs.getLong(KEY_AWG_CONFIRMED_MTIME, -1L) !=
                java.io.File(awgPath).lastModified()
        return classifyRuntimeMismatch(
            saved.runtimeConfig,
            expectedRuntimeTokens(context, prefs),
            awgPath,
            awgMtimeChanged,
        )
    }

    internal fun classifyRuntimeMismatch(
        runtimeConfig: String,
        expectedTokens: Map<String, String>,
        currentAwgPath: String,
        awgMtimeChanged: Boolean,
    ): ProxyTileStartDecision {
        val tokens = runtimeConfig.split(',')
            .filter { it.startsWith("@") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val networkKeys = setOf("@network_profile_id", "@network_profile_type")
        if (networkKeys.any { tokens[it] != null && tokens[it] != expectedTokens[it] }) {
            return ProxyTileStartDecision.NETWORK_CHANGED
        }
        if (expectedTokens.any { (key, value) -> key !in networkKeys && tokens[key] != value }) {
            return ProxyTileStartDecision.ROUTE_POLICY_CHANGED
        }
        if (tokens["@awg_warp_config_path"].orEmpty() != currentAwgPath || awgMtimeChanged) {
            return ProxyTileStartDecision.AWG_CHANGED
        }
        return ProxyTileStartDecision.CONFIG_CHANGED
    }

    // Versioned confirmations must be checked before any legacy reuse.
    internal fun decideVersioned(
        saved: ProxySavedConfig,
        confirmedV2: String?,
        currentV2: String?,
        confirmedV1: String?,
        currentV1: String?,
        legacyReusable: Boolean,
    ): ProxyTileStartDecision = when {
        !saved.isSufficientForStart() -> ProxyTileStartDecision.SETUP_REQUIRED
        confirmedV2 != null -> if (currentV2 == null) {
            ProxyTileStartDecision.VALIDATION_FAILED
        } else decide(saved, confirmedV2, currentV2, false)
        confirmedV1 != null -> if (currentV1 == null) {
            ProxyTileStartDecision.VALIDATION_FAILED
        } else decide(saved, confirmedV1, currentV1, false)
        legacyReusable -> ProxyTileStartDecision.READY
        else -> ProxyTileStartDecision.CONFIRMATION_MISSING
    }

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
                val awgPath = AwgWarpConfigStore.configPath(context).orEmpty()
                val persisted = prefs.edit()
                    .putString(KEY_CONFIRMED_SIGNATURE_V2, signature)
                    .putLong(
                        KEY_AWG_CONFIRMED_MTIME,
                        if (awgPath.isBlank()) -1L else java.io.File(awgPath).lastModified(),
                    )
                    .commit()
                check(persisted) { "Tile confirmation persistence failed" }
            }
        }.onFailure { error ->
            Log.w("ProxyTileStartPolicy", "TILE_CONFIRMATION_FAILED exception=${error.javaClass.simpleName}")
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

    private fun expectedRuntimeTokens(
        context: Context,
        prefs: SharedPreferences,
    ): Map<String, String> {
        val profile = NetworkProfileProvider.current(context)
        val effective = EffectiveRoutePolicyResolver(
            NetworkRoutePolicyRepository(prefs),
            prefs,
        ).resolve(profile)
        val policy = effective.policy
        return mapOf(
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
    }

    private fun flag(enabled: Boolean) = if (enabled) "1" else "0"

    // Compatibility for previously running installations without a tile confirmation hash.
    // Every required route/profile token must match the currently selected policy.
    internal fun isLegacyReusable(
        saved: ProxySavedConfig,
        savedBySuccessfulStart: Boolean,
        expectedTokens: Map<String, String>,
        currentAwgPath: String,
        awgPreviouslyConfirmed: Boolean = false,
    ): Boolean {
        if (!savedBySuccessfulStart || !saved.isSufficientForStart() ||
            saved.runtimeConfig.isBlank() || expectedTokens.isEmpty() ||
            !awgPreviouslyConfirmed && currentAwgPath.isNotBlank()
        ) return false
        val tokens = saved.runtimeConfig.split(',')
            .filter { it.startsWith("@") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return expectedTokens.all { (key, value) -> tokens[key] == value } &&
            tokens["@awg_warp_config_path"].orEmpty() == currentAwgPath
    }

    internal fun isSignaturePreference(key: String, stable: Boolean): Boolean =
        (key in relevantPreferences && (!stable || key != "cf_upstream_domains")) ||
            (key.startsWith("worker_pool_") &&
                key != "worker_pool_round_robin_cursor_v1" &&
                key != "worker_pool_migration_done_v1" &&
                (!stable || key != "worker_pool_workers_v1"))

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
            .filterKeys { key -> isSignaturePreference(key, stable) }
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
