package com.amurcanov.tgwsproxy

internal const val DEFAULT_LOCAL_PROXY_HOST = "127.0.0.1"
internal const val DEFAULT_LOCAL_PROXY_PORT = 1443
internal const val LEGACY_DEFAULT_LOCAL_PROXY_PORT = 1081
internal const val LOCAL_PROXY_PORT_DEFAULT_MIGRATION_KEY = "local_proxy_port_default_1443_migrated"

internal enum class LocalProxyFrontendType(val prefValue: String) {
    SOCKS5("socks5"),
    MTPROTO_EXPERIMENTAL("mtproto_experimental");

    companion object {
        val DEFAULT = MTPROTO_EXPERIMENTAL

        fun fromPref(value: String?): LocalProxyFrontendType {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return DEFAULT
            return entries.firstOrNull { it.prefValue == normalized } ?: DEFAULT
        }
    }
}

internal data class LocalProxyFrontendConfig(
    val host: String,
    val port: Int,
    val runtimeConfig: String,
    val poolSize: Int,
    val verbose: Int,
    val mtProtoConfig: MtProtoProxyConfig? = null,
)

internal enum class LocalProxyFrontendStatus {
    STOPPED,
    RUNNING,
    FAILED,
    UNSUPPORTED,
}

internal data class LocalProxyFrontendState(
    val type: LocalProxyFrontendType,
    val status: LocalProxyFrontendStatus,
)

internal data class LocalProxyFrontendStartResult(
    val state: LocalProxyFrontendState,
    val message: String = "",
    val errorCode: String? = null,
)

internal interface LocalProxyFrontend {
    val type: LocalProxyFrontendType

    fun start(config: LocalProxyFrontendConfig): LocalProxyFrontendStartResult

    fun stop(): String?

    fun getState(): LocalProxyFrontendState
}

internal fun localProxyFrontendFor(type: LocalProxyFrontendType): LocalProxyFrontend {
    return when (type) {
        LocalProxyFrontendType.SOCKS5 -> Socks5LocalProxyFrontend()
        LocalProxyFrontendType.MTPROTO_EXPERIMENTAL -> MtProtoLocalProxyFrontend()
    }
}
