package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.SharedPreferences

data class ProxyRuntimeStartConfig(
    val port: Int,
    val ips: String,
    val poolSize: Int,
    val profile: NetworkProfile,
    val effectivePolicy: EffectiveRoutePolicy,
)

class ProxyRuntimeConfigFactory(
    private val prefs: SharedPreferences,
    private val routePolicyRepository: NetworkRoutePolicyRepository,
    private val adaptiveRouteStatsRepository: AdaptiveRouteStatsRepository,
    private val cfUpstreamDomainsProvider: () -> List<String>,
    private val manualCfDomainsProvider: () -> List<String>,
    private val workerDomainProvider: () -> String,
    private val dcEntriesProvider: () -> List<String>,
    private val poolSizeProvider: () -> Int,
    private val portProvider: () -> Int?,
    private val profileProvider: (Context) -> NetworkProfile = { NetworkProfileProvider.current(it) },
) {
    fun build(context: Context): ProxyRuntimeStartConfig? {
        val port = portProvider() ?: return null
        val networkProfile = profileProvider(context)
        val effectivePolicy = EffectiveRoutePolicyResolver(
            repository = routePolicyRepository,
            prefs = prefs,
        ).resolve(networkProfile)
        val ips = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = dcEntriesProvider(),
            mode = effectivePolicy.legacyMode,
            cfProxyEnabled = prefs.getBoolean(KEY_CFPROXY_ENABLED, true),
            cfProxyPriority = prefs.getBoolean(KEY_CFPROXY_PRIORITY, true),
            cfProxyOnly = prefs.getBoolean(KEY_CFPROXY_ONLY, false),
            cfDomain = "",
            manualCfDomains = manualCfDomainsProvider(),
            workerEnabled = prefs.getBoolean(KEY_WORKER_ENABLED, false),
            workerDomain = WorkerDomain.normalize(workerDomainProvider()),
            cachedCfDomains = cfUpstreamDomainsProvider(),
            networkProfile = networkProfile,
            adaptiveRouteStats = adaptiveRouteStatsRepository.loadEncodedStats(),
            autoStrategy = effectivePolicy.policy.autoStrategy,
            routePolicy = effectivePolicy.policy,
        )
        return ProxyRuntimeStartConfig(
            port = port,
            ips = ips,
            poolSize = poolSizeProvider(),
            profile = networkProfile,
            effectivePolicy = effectivePolicy,
        )
    }

    private companion object {
        const val KEY_CFPROXY_ENABLED = "cfproxy_enabled"
        const val KEY_CFPROXY_PRIORITY = "cfproxy_priority"
        const val KEY_CFPROXY_ONLY = "cfproxy_only"
        const val KEY_WORKER_ENABLED = "worker_enabled"
    }
}
