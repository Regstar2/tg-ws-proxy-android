package com.amurcanov.tgwsproxy

data class AwgWarpRuntimeConfig(
    val enabled: Boolean,
    val preferred: Boolean,
    val allowFallback: Boolean,
    val configPath: String,
) {
    companion object {
        fun fromRuntimeTokens(runtimeConfig: String): AwgWarpRuntimeConfig {
            val values = runtimeConfig
                .split(',')
                .mapNotNull { token ->
                    val trimmed = token.trim()
                    if (!trimmed.startsWith('@')) return@mapNotNull null
                    val separator = trimmed.indexOf('=')
                    if (separator <= 1) return@mapNotNull null
                    trimmed.substring(1, separator).trim().lowercase() to
                        trimmed.substring(separator + 1).trim()
                }
                .toMap()

            return AwgWarpRuntimeConfig(
                enabled = values["route_awg_warp"].toRuntimeBoolean(),
                preferred = values["preferred_route"].equals(RouteKind.AWG_WARP.prefValue, ignoreCase = true),
                allowFallback = values["route_fallback"].toRuntimeBoolean(),
                configPath = values["awg_warp_config_path"].orEmpty(),
            )
        }

        private fun String?.toRuntimeBoolean(): Boolean {
            return when (this?.trim()?.lowercase()) {
                "1", "true", "yes" -> true
                else -> false
            }
        }
    }
}

internal interface AwgWarpNativeRuntime {
    fun configure(config: AwgWarpRuntimeConfig): Int
    fun reset(): Int
}

internal object NativeAwgWarpRuntime : AwgWarpNativeRuntime {
    override fun configure(config: AwgWarpRuntimeConfig): Int {
        return NativeProxy.configureAwgWarp(
            configPath = config.configPath,
            enabled = config.enabled,
            preferred = config.preferred,
            allowFallback = config.allowFallback,
        )
    }

    override fun reset(): Int = NativeProxy.resetAwgWarp()
}
