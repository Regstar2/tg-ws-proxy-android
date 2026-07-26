package com.amurcanov.tgwsproxy

import java.net.URLEncoder
import java.util.Locale

internal object MtProtoTelegramLinkBuilder {
    private const val TRANSPORT_PREFIX_DD = "dd"
    private const val TRANSPORT_PREFIX_EE = "ee"

    fun httpsLink(config: MtProtoProxyConfig): String? {
        return build(config, "https://t.me/proxy")
    }

    fun tgUri(config: MtProtoProxyConfig): String? {
        return build(config, "tg://proxy")
    }

    fun linkSecret(config: MtProtoProxyConfig): String? {
        val normalized = config.normalized()
        if (!MtProtoProxyConfigValidator.validate(normalized).isValid) {
            return null
        }
        val secret = normalized.secret.lowercase(Locale.US)
        if (normalized.fakeTlsDomain.isBlank()) {
            return TRANSPORT_PREFIX_DD + secret
        }
        return TRANSPORT_PREFIX_EE + secret + normalized.fakeTlsDomain.toByteArray(Charsets.US_ASCII).toHex()
    }

    private fun build(config: MtProtoProxyConfig, base: String): String? {
        val normalized = config.normalized()
        val secret = linkSecret(normalized) ?: return null
        return "$base?server=${query(normalized.host)}&port=${normalized.port}&secret=${query(secret)}"
    }

    private fun query(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        val hex = "0123456789abcdef".toCharArray()
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            out[index * 2] = hex[value ushr 4]
            out[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(out)
    }
}

internal enum class MtProtoUiStatus {
    SOCKS5_WS_STABLE,
    MTPROTO_DISABLED,
    MTPROTO_EXPERIMENTAL,
    MTPROTO_LOCAL_LISTENING,
    MTPROTO_LOCAL_ONLY_LIMITED,
    MTPROTO_ROUTE_READY,
    MTPROTO_FAILED,
}

internal fun MtProtoUiStatus.labelRes(): Int {
    return when (this) {
        MtProtoUiStatus.SOCKS5_WS_STABLE -> R.string.mtproto_status_socks5_ws_stable
        MtProtoUiStatus.MTPROTO_DISABLED -> R.string.mtproto_status_disabled
        MtProtoUiStatus.MTPROTO_EXPERIMENTAL -> R.string.mtproto_status_experimental
        MtProtoUiStatus.MTPROTO_LOCAL_LISTENING -> R.string.mtproto_status_local_listening
        MtProtoUiStatus.MTPROTO_LOCAL_ONLY_LIMITED -> R.string.mtproto_status_local_only_limited
        MtProtoUiStatus.MTPROTO_ROUTE_READY -> R.string.mtproto_status_direct_ready
        MtProtoUiStatus.MTPROTO_FAILED -> R.string.mtproto_status_failed
    }
}

internal object MtProtoUiStatusResolver {
    fun resolve(
        frontendType: LocalProxyFrontendType,
        config: MtProtoProxyConfig,
        serviceRunning: Boolean,
        runtimeState: MtProtoRuntimeState,
    ): MtProtoUiStatus {
        if (frontendType == LocalProxyFrontendType.SOCKS5) {
            return MtProtoUiStatus.SOCKS5_WS_STABLE
        }
        if (!config.enabled) {
            return MtProtoUiStatus.MTPROTO_DISABLED
        }
        if (!serviceRunning) {
            return MtProtoUiStatus.MTPROTO_EXPERIMENTAL
        }
        return when (runtimeState) {
            MtProtoRuntimeState.LISTENING_LOCAL_ONLY -> MtProtoUiStatus.MTPROTO_LOCAL_ONLY_LIMITED
            MtProtoRuntimeState.RUNNING -> MtProtoUiStatus.MTPROTO_ROUTE_READY
            MtProtoRuntimeState.STARTING -> MtProtoUiStatus.MTPROTO_EXPERIMENTAL
            MtProtoRuntimeState.STOPPED,
            MtProtoRuntimeState.FAILED,
            MtProtoRuntimeState.UNSUPPORTED -> MtProtoUiStatus.MTPROTO_FAILED
        }
    }
}
