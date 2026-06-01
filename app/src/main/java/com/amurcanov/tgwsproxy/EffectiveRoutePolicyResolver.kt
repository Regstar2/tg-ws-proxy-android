package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

data class EffectiveRoutePolicy(
    val profile: NetworkProfile,
    val policy: NetworkRoutePolicy,
    val legacyMode: ConnectionMode,
    val source: EffectiveRoutePolicySource,
)

enum class EffectiveRoutePolicySource {
    SAVED_NETWORK_POLICY,
    LEGACY_CONNECTION_MODE,
    DEFAULT_POLICY,
}

class EffectiveRoutePolicyResolver(
    private val repository: NetworkRoutePolicyRepository,
    private val prefs: SharedPreferences,
) {
    fun resolve(profile: NetworkProfile): EffectiveRoutePolicy {
        return runCatching {
            if (repository.hasSavedPolicy(profile.type)) {
                val policy = repository.load(profile.type)
                return@runCatching EffectiveRoutePolicy(
                    profile = profile,
                    policy = policy,
                    legacyMode = NetworkRoutePolicyMapper.toLegacyConnectionMode(policy),
                    source = EffectiveRoutePolicySource.SAVED_NETWORK_POLICY,
                )
            }

            val legacyMode = prefs.getString(KEY_CONNECTION_MODE, null)
                ?.let { ConnectionMode.fromPref(it) }
                ?: ConnectionMode.fromLegacy(
                    prefs.getBoolean(KEY_CFPROXY_ENABLED, true),
                    prefs.getBoolean(KEY_CFPROXY_PRIORITY, true),
                    prefs.getBoolean(KEY_CFPROXY_ONLY, false),
                )
            val strategy = parseStrategy(prefs.getString(KEY_AUTO_STRATEGY, null))
            val policy = NetworkRoutePolicyMapper.fromConnectionMode(
                networkType = profile.type,
                mode = legacyMode,
                strategy = strategy,
            ).copy(autoStrategy = strategy)

            EffectiveRoutePolicy(
                profile = profile,
                policy = policy,
                legacyMode = legacyMode,
                source = EffectiveRoutePolicySource.LEGACY_CONNECTION_MODE,
            )
        }.getOrElse {
            val policy = DefaultNetworkRoutePolicies.forType(profile.type)
            EffectiveRoutePolicy(
                profile = profile,
                policy = policy,
                legacyMode = NetworkRoutePolicyMapper.toLegacyConnectionMode(policy),
                source = EffectiveRoutePolicySource.DEFAULT_POLICY,
            )
        }
    }

    private companion object {
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val KEY_CFPROXY_ENABLED = "cfproxy_enabled"
        const val KEY_CFPROXY_PRIORITY = "cfproxy_priority"
        const val KEY_CFPROXY_ONLY = "cfproxy_only"
        const val KEY_AUTO_STRATEGY = "auto_strategy"

        fun parseStrategy(raw: String?): AutoStrategy {
            return AutoStrategy.fromPref(raw?.trim()?.lowercase())
        }
    }
}
