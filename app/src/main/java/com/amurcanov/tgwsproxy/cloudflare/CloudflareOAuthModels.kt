package com.amurcanov.tgwsproxy.cloudflare

import com.amurcanov.tgwsproxy.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class CloudflareOAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && redirectUri.isNotBlank() && scopes.isNotEmpty()

    companion object {
        fun fromBuildConfig(): CloudflareOAuthConfig {
            return CloudflareOAuthConfig(
                clientId = BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID.trim(),
                redirectUri = BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI.trim(),
                scopes = BuildConfig.CLOUDFLARE_OAUTH_SCOPES
                    .split(Regex("\\s+"))
                    .map(String::trim)
                    .filter(String::isNotBlank),
            )
        }
    }
}

data class CloudflarePkcePair(
    val verifier: String,
    val challenge: String,
)

object CloudflarePkce {
    private const val VERIFIER_BYTES = 32

    fun create(random: SecureRandom = SecureRandom()): CloudflarePkcePair {
        val bytes = ByteArray(VERIFIER_BYTES)
        random.nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return CloudflarePkcePair(
            verifier = verifier,
            challenge = challenge(verifier),
        )
    }

    internal fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

data class CloudflareAuthorizedAccount(
    val id: String,
    val name: String,
)

data class CloudflareOAuthToken(
    val accessToken: String,
    val expiresAtEpochMs: Long?,
)

data class CloudflareOAuthSession(
    val id: String,
    val label: String,
    val accessToken: String,
    val expiresAtEpochMs: Long?,
    val accounts: List<CloudflareAuthorizedAccount>,
    val createdAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = expiresAtEpochMs ?: return false
        return nowEpochMs >= expiresAt - EXPIRY_SKEW_MS
    }

    companion object {
        private const val EXPIRY_SKEW_MS = 30_000L

        fun create(
            accessToken: String,
            expiresAtEpochMs: Long?,
            accounts: List<CloudflareAuthorizedAccount>,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): CloudflareOAuthSession {
            val label = when {
                accounts.isEmpty() -> "Cloudflare"
                accounts.size == 1 -> accounts.first().name
                else -> accounts.take(2).joinToString(" / ") { it.name }
            }
            return CloudflareOAuthSession(
                id = UUID.randomUUID().toString(),
                label = label,
                accessToken = accessToken,
                expiresAtEpochMs = expiresAtEpochMs,
                accounts = accounts,
                createdAtEpochMs = nowEpochMs,
            )
        }
    }
}

data class ActiveCloudflareAccountRef(
    val sessionId: String,
    val accountId: String,
)

object CloudflareWorkerName {
    private val INVALID_CHARS = Regex("[^a-z0-9-]+")
    private val REPEATED_DASH = Regex("-+")

    fun normalize(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(INVALID_CHARS, "-")
            .replace(REPEATED_DASH, "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trimEnd('-')
    }

    fun isValid(raw: String): Boolean {
        val normalized = normalize(raw)
        return normalized.isNotBlank() &&
            normalized.length <= MAX_LENGTH &&
            normalized == raw.trim().lowercase()
    }

    private const val MAX_LENGTH = 63
}
