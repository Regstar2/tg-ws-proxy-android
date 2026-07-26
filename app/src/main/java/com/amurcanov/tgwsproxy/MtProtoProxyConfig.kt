package com.amurcanov.tgwsproxy

import android.content.SharedPreferences
import java.net.IDN
import java.security.SecureRandom
import java.util.Locale

internal data class MtProtoProxyConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val secret: String = MtProtoSecretGenerator.generate(),
    val fakeTlsDomain: String = "",
    val fakeTlsPassthrough: Boolean = false,
    val enabled: Boolean = DEFAULT_ENABLED,
    val experimentalAcknowledged: Boolean = DEFAULT_EXPERIMENTAL_ACKNOWLEDGED,
) {
    fun normalized(): MtProtoProxyConfig {
        val normalizedFakeTlsDomain = MtProtoFakeTlsDomain.normalize(fakeTlsDomain)
        return copy(
            host = host.trim(),
            secret = secret.trim().lowercase(Locale.US),
            fakeTlsDomain = normalizedFakeTlsDomain,
            fakeTlsPassthrough = fakeTlsPassthrough &&
                MtProtoFakeTlsDomain.isValid(normalizedFakeTlsDomain),
        )
    }

    companion object {
        const val DEFAULT_HOST = DEFAULT_LOCAL_PROXY_HOST
        const val DEFAULT_PORT = DEFAULT_LOCAL_PROXY_PORT
        const val DEFAULT_ENABLED = true
        const val DEFAULT_EXPERIMENTAL_ACKNOWLEDGED = true

        fun default(secretGenerator: () -> String = MtProtoSecretGenerator::generate): MtProtoProxyConfig {
            return MtProtoProxyConfig(secret = secretGenerator())
        }
    }
}

internal enum class MtProtoProxyConfigValidationError {
    EMPTY_HOST,
    INVALID_PORT,
    INVALID_SECRET,
    INVALID_FAKE_TLS_DOMAIN,
}

internal data class MtProtoProxyConfigValidationResult(
    val errors: Set<MtProtoProxyConfigValidationError>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

internal object MtProtoProxyConfigValidator {
    private val rawDdSecretPattern = Regex("^[0-9a-f]{32}$")

    fun validate(config: MtProtoProxyConfig): MtProtoProxyConfigValidationResult {
        val normalized = config.normalized()
        val errors = buildSet {
            if (normalized.host.isBlank()) {
                add(MtProtoProxyConfigValidationError.EMPTY_HOST)
            }
            if (normalized.port !in 1..65535) {
                add(MtProtoProxyConfigValidationError.INVALID_PORT)
            }
            if (!isValidRawSecret(normalized.secret)) {
                add(MtProtoProxyConfigValidationError.INVALID_SECRET)
            }
            if (
                config.fakeTlsDomain.isNotBlank() &&
                !MtProtoFakeTlsDomain.isValid(normalized.fakeTlsDomain)
            ) {
                add(MtProtoProxyConfigValidationError.INVALID_FAKE_TLS_DOMAIN)
            }
        }
        return MtProtoProxyConfigValidationResult(errors)
    }

    fun isValidRawSecret(secret: String): Boolean {
        return rawDdSecretPattern.matches(secret.trim().lowercase(Locale.US))
    }
}

internal object MtProtoSecretGenerator {
    private val random = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    fun generate(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            out[index * 2] = hex[value ushr 4]
            out[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(out)
    }
}

internal object MtProtoFakeTlsDomain {
    private val labelPattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.isBlank()) return ""
        value = when {
            value.lowercase(Locale.US).startsWith("https://") -> value.drop("https://".length)
            value.lowercase(Locale.US).startsWith("http://") -> value.drop("http://".length)
            else -> value
        }
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (value.startsWith("[") && value.contains("]")) {
            return ""
        }
        val colon = value.lastIndexOf(':')
        if (colon >= 0) {
            value = value.substring(0, colon)
        }
        value = value.trim().trim('.')
        return runCatching {
            IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
                .lowercase(Locale.US)
                .trim('.')
        }.getOrDefault(value.lowercase(Locale.US))
    }

    fun isValid(domain: String): Boolean {
        val value = domain.trim().lowercase(Locale.US)
        if (value.isBlank() || value.length > 253 || !value.contains('.')) {
            return false
        }
        if (value.any { it == ',' || it == ';' || it == '/' || it == '\\' || it.isWhitespace() }) {
            return false
        }
        return value.split('.').all { label -> labelPattern.matches(label) }
    }
}

internal object MtProtoSecretMasking {
    const val MASKED = "***"

    fun mask(secret: String): String {
        return if (secret.isBlank()) "none" else MASKED
    }
}

internal object MtProtoProxyConfigDiagnostics {
    fun reportLines(config: MtProtoProxyConfig): List<String> {
        val normalized = config.normalized()
        val validation = MtProtoProxyConfigValidator.validate(normalized)
        return listOf(
            "MTProto enabled: ${DiagnosticReportValue.yesNo(normalized.enabled)}",
            "MTProto host: ${normalized.host.ifBlank { "UNKNOWN" }}",
            "MTProto port: ${normalized.port}",
            "MTProto secret: ${MtProtoSecretMasking.mask(normalized.secret)}",
            "MTProto Fake TLS: ${DiagnosticReportValue.yesNo(normalized.fakeTlsDomain.isNotBlank())}",
            "MTProto Fake TLS passthrough: ${DiagnosticReportValue.yesNo(normalized.fakeTlsPassthrough)}",
            "MTProto notice accepted: ${DiagnosticReportValue.yesNo(normalized.experimentalAcknowledged)}",
            "MTProto config valid: ${DiagnosticReportValue.yesNo(validation.isValid)}",
        )
    }
}

private object DiagnosticReportValue {
    fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}

internal class MtProtoProxyConfigRepository(
    private val prefs: SharedPreferences,
    private val secretGenerator: () -> String = MtProtoSecretGenerator::generate,
) {
    fun load(): MtProtoProxyConfig {
        return loadInternal(persistSafeConfig = true)
    }

    fun loadForDiagnostics(): MtProtoProxyConfig {
        return loadInternal(persistSafeConfig = false)
    }

    fun save(config: MtProtoProxyConfig): MtProtoProxyConfig {
        val normalized = config.normalized()
        val safe = if (MtProtoProxyConfigValidator.validate(normalized).isValid) {
            normalized
        } else {
            MtProtoProxyConfig.default(secretGenerator)
        }
        persist(safe)
        return safe
    }

    private fun loadInternal(persistSafeConfig: Boolean): MtProtoProxyConfig {
        val all = prefs.all
        val hasAnyConfig = CONFIG_KEYS.any { all.containsKey(it) }
        val hasCorruptedTypes = hasCorruptedTypes(all)

        val candidate = MtProtoProxyConfig(
            host = (all[KEY_HOST] as? String) ?: MtProtoProxyConfig.DEFAULT_HOST,
            port = (all[KEY_PORT] as? Int) ?: MtProtoProxyConfig.DEFAULT_PORT,
            secret = (all[KEY_SECRET] as? String) ?: secretGenerator(),
            fakeTlsDomain = (all[KEY_FAKE_TLS_DOMAIN] as? String).orEmpty(),
            fakeTlsPassthrough = (all[KEY_FAKE_TLS_PASSTHROUGH] as? Boolean) ?: false,
            enabled = (all[KEY_ENABLED] as? Boolean) ?: MtProtoProxyConfig.DEFAULT_ENABLED,
            experimentalAcknowledged = (all[KEY_EXPERIMENTAL_ACKNOWLEDGED] as? Boolean)
                ?: MtProtoProxyConfig.DEFAULT_EXPERIMENTAL_ACKNOWLEDGED,
        ).normalized()

        val isValid = MtProtoProxyConfigValidator.validate(candidate).isValid
        val safe = if (!hasCorruptedTypes && isValid) {
            candidate
        } else {
            MtProtoProxyConfig.default(secretGenerator)
        }

        if (persistSafeConfig && (!hasAnyConfig || hasCorruptedTypes || !isValid || safe != candidate)) {
            persist(safe)
        }
        return safe
    }

    private fun persist(config: MtProtoProxyConfig) {
        val normalized = config.normalized()
        prefs.edit()
            .putString(KEY_HOST, normalized.host)
            .putInt(KEY_PORT, normalized.port)
            .putString(KEY_SECRET, normalized.secret)
            .putString(KEY_FAKE_TLS_DOMAIN, normalized.fakeTlsDomain)
            .putBoolean(KEY_FAKE_TLS_PASSTHROUGH, normalized.fakeTlsPassthrough)
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putBoolean(KEY_EXPERIMENTAL_ACKNOWLEDGED, normalized.experimentalAcknowledged)
            .apply()
    }

    private fun hasCorruptedTypes(all: Map<String, *>): Boolean {
        return (all.containsKey(KEY_HOST) && all[KEY_HOST] !is String) ||
            (all.containsKey(KEY_PORT) && all[KEY_PORT] !is Int) ||
            (all.containsKey(KEY_SECRET) && all[KEY_SECRET] !is String) ||
            (all.containsKey(KEY_FAKE_TLS_DOMAIN) && all[KEY_FAKE_TLS_DOMAIN] !is String) ||
            (all.containsKey(KEY_FAKE_TLS_PASSTHROUGH) && all[KEY_FAKE_TLS_PASSTHROUGH] !is Boolean) ||
            (all.containsKey(KEY_ENABLED) && all[KEY_ENABLED] !is Boolean) ||
            (
                all.containsKey(KEY_EXPERIMENTAL_ACKNOWLEDGED) &&
                    all[KEY_EXPERIMENTAL_ACKNOWLEDGED] !is Boolean
                )
    }

    companion object {
        const val KEY_HOST = "mtproto_host_v1"
        const val KEY_PORT = "mtproto_port_v1"
        const val KEY_SECRET = "mtproto_secret_v1"
        const val KEY_FAKE_TLS_DOMAIN = "mtproto_fake_tls_domain_v1"
        const val KEY_FAKE_TLS_PASSTHROUGH = "mtproto_fake_tls_passthrough_v1"
        const val KEY_ENABLED = "mtproto_enabled_v1"
        const val KEY_EXPERIMENTAL_ACKNOWLEDGED = "mtproto_experimental_acknowledged_v1"

        private val CONFIG_KEYS = setOf(
            KEY_HOST,
            KEY_PORT,
            KEY_SECRET,
            KEY_FAKE_TLS_DOMAIN,
            KEY_FAKE_TLS_PASSTHROUGH,
            KEY_ENABLED,
            KEY_EXPERIMENTAL_ACKNOWLEDGED,
        )
    }
}
