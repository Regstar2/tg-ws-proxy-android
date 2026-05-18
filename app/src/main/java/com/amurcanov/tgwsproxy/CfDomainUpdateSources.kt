package com.amurcanov.tgwsproxy

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

object CfDomainUpdateConfig {
    const val PRIMARY_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
}

enum class CfDomainUpdateSourceType {
    PRIMARY_GITHUB,
    USER_MIRROR,
    ;

    val logName: String
        get() = when (this) {
            PRIMARY_GITHUB -> "primary"
            USER_MIRROR -> "mirror"
        }
}

enum class CfDomainUpdateStage {
    DNS,
    TCP,
    TLS,
    HTTP,
    READ,
    PARSE,
    VALIDATION,
    TIMEOUT,
    CANCELLED,
    UNKNOWN,
    ;

    val logName: String
        get() = name.lowercase()
}

data class CfDomainSourceStatus(
    val sourceType: CfDomainUpdateSourceType,
    val url: String = "",
    val enabled: Boolean = true,
    val lastAttemptAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
    val lastError: String? = null,
    val lastHttpStatus: Int? = null,
    val lastLatencyMs: Long? = null,
    val lastStage: CfDomainUpdateStage? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

sealed interface CfDomainMirrorValidation {
    data class Valid(val url: String) : CfDomainMirrorValidation
    data class Invalid(val message: String) : CfDomainMirrorValidation
    object Disabled : CfDomainMirrorValidation
}

object CfDomainMirrorUrlValidator {
    fun validate(enabled: Boolean, rawUrl: String): CfDomainMirrorValidation {
        if (!enabled) {
            return CfDomainMirrorValidation.Disabled
        }
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return CfDomainMirrorValidation.Invalid("empty_url")
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return CfDomainMirrorValidation.Invalid("invalid_url")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return CfDomainMirrorValidation.Invalid("https_required")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return CfDomainMirrorValidation.Invalid("credentials_not_allowed")
        }
        val host = uri.host?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) {
            return CfDomainMirrorValidation.Invalid("invalid_host")
        }
        if (isBlockedHost(host)) {
            return CfDomainMirrorValidation.Invalid("blocked_host")
        }
        if (isPrivateOrLoopbackHost(host)) {
            return CfDomainMirrorValidation.Invalid("private_host")
        }
        return CfDomainMirrorValidation.Valid(trimmed)
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) {
            return true
        }
        val blockedSchemes = setOf("file", "content", "javascript", "data")
        return blockedSchemes.any { host.startsWith("$it:") }
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        if (host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1") {
            return true
        }
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.any { address ->
                address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    isPrivateIpv4(address.hostAddress.orEmpty())
            }
        } catch (_: UnknownHostException) {
            false
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) {
            return false
        }
        return when (parts[0]) {
            10 -> true
            127 -> true
            0 -> true
            169 -> parts[1] == 254
            172 -> parts[1] in 16..31
            192 -> parts[1] == 168
            else -> false
        }
    }
}

fun CfDomainUpdateStage.userMessageKey(): Int = when (this) {
    CfDomainUpdateStage.DNS -> R.string.cf_update_error_dns
    CfDomainUpdateStage.TCP -> R.string.cf_update_error_tcp
    CfDomainUpdateStage.TLS -> R.string.cf_update_error_tls
    CfDomainUpdateStage.HTTP -> R.string.cf_update_error_http
    CfDomainUpdateStage.READ -> R.string.cf_update_error_read
    CfDomainUpdateStage.PARSE -> R.string.cf_update_error_parse
    CfDomainUpdateStage.VALIDATION -> R.string.cf_update_error_parse
    CfDomainUpdateStage.TIMEOUT -> R.string.cf_update_error_tcp
    CfDomainUpdateStage.CANCELLED -> R.string.cf_update_error_unavailable
    CfDomainUpdateStage.UNKNOWN -> R.string.cf_update_error_unavailable
}

fun CfDomainUpdateSourceType.labelKey(): Int = when (this) {
    CfDomainUpdateSourceType.PRIMARY_GITHUB -> R.string.cf_update_source_primary
    CfDomainUpdateSourceType.USER_MIRROR -> R.string.cf_update_source_mirror
}

fun safeUrlForLog(url: String): String {
    return try {
        val uri = URI(url)
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty().ifBlank { "/" }
        "$host$path"
    } catch (_: Exception) {
        "invalid-url"
    }
}
