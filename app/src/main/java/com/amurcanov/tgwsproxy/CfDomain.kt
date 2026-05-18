package com.amurcanov.tgwsproxy

import java.net.URI
import kotlin.math.max

enum class CfDomainSource {
    MANUAL,
    BUILT_IN,
    CACHED_UPSTREAM,
}

enum class CfDomainStatus {
    OK,
    FAILED,
    COOLDOWN,
    UNCHECKED,
}

data class CfDomainHealth(
    val domain: String,
    val source: CfDomainSource,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val lastFailureReason: String? = null,
    val cooldownUntilMs: Long? = null,
    val lastLatencyMs: Long? = null,
) {
    fun status(nowMs: Long = System.currentTimeMillis()): CfDomainStatus {
        return when {
            cooldownUntilMs != null && cooldownUntilMs > nowMs -> CfDomainStatus.COOLDOWN
            lastSuccessAtMs != null && (lastFailureAtMs == null || lastSuccessAtMs >= lastFailureAtMs) -> CfDomainStatus.OK
            lastFailureAtMs != null -> CfDomainStatus.FAILED
            else -> CfDomainStatus.UNCHECKED
        }
    }
}

data class CfDomainProbeSummary(
    val manualResult: CfDomainHealth?,
    val availableBuiltIn: Int,
    val failedBuiltIn: Int,
    val uncheckedBuiltIn: Int,
    val bestDomain: CfDomainHealth?,
)

data class CfDomainProbeReport(
    val routeReport: ConnectionProbeReport,
    val domains: List<CfDomainHealth>,
    val summary: CfDomainProbeSummary,
    val checkedAtMs: Long,
)

object CfDomain {
    private val upstreamEncoded = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
    )

    val builtInDomains: List<String> = upstreamEncoded
        .map(::decodeFlowseal)
        .mapNotNull { normalizeOrNull(it) }
        .distinct()

    fun normalize(raw: String): String = normalizeOrNull(raw).orEmpty()

    fun normalizeOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val host = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                runCatching {
                    val uri = URI(trimmed)
                    if (!uri.userInfo.isNullOrBlank() || uri.port != -1) return null
                    uri.host?.lowercase()
                }.getOrNull()
            }
            "://" in lower -> null
            trimmed.any { it == ' ' || it == '/' || it == '?' || it == '#' || it == ':' } -> null
            else -> trimmed.lowercase()
        } ?: return null

        return host.takeIf(::isValidHostname)
    }

    fun validationWarning(domainRaw: String): Int? {
        if (domainRaw.isBlank()) return null
        return if (normalizeOrNull(domainRaw) == null) R.string.cf_domain_invalid else null
    }

    fun decodeFlowseal(raw: String): String {
        if (!raw.lowercase().endsWith(".com")) return raw.trim()
        val prefix = raw.trim().dropLast(4)
        val shift = prefix.count { it.isLetter() }
        val decoded = buildString {
            prefix.forEach { ch ->
                append(
                    when {
                        ch in 'a'..'z' -> ('a'.code + ((ch.code - 'a'.code - shift).mod(26))).toChar()
                        ch in 'A'..'Z' -> ('A'.code + ((ch.code - 'A'.code - shift).mod(26))).toChar()
                        else -> ch
                    }
                )
            }
        }
        return "$decoded.co.uk"
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.length !in 1..253 || "." !in host || ".." in host) return false
        return host.split('.').all { label ->
            label.length in 1..63 &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLowerCase() || it.isDigit() || it == '-' }
        }
    }
}

object CfDomainDiagnosticsState {
    private val health = linkedMapOf<String, CfDomainHealth>()
    var lastCheckAtMs: Long? = null
        private set

    fun snapshot(manualDomainRaw: String): List<CfDomainHealth> {
        ensureKnownDomains(manualDomainRaw)
        val activeDomains = buildList {
            CfDomain.normalizeOrNull(manualDomainRaw)?.let(::add)
            addAll(CfDomain.builtInDomains)
        }.distinct()
        return activeDomains
            .mapNotNull { health[it] }
            .sortedWith(compareBy<CfDomainHealth>({ it.source.ordinal }, { it.domain }))
    }

    fun markProbe(domain: String, source: CfDomainSource, result: RouteProbeResult, nowMs: Long): CfDomainHealth {
        ensureKnownDomain(domain, source)
        val current = health.getValue(domain)
        val next = if (result.success) {
            current.copy(
                successCount = current.successCount + 1,
                consecutiveFailures = 0,
                lastSuccessAtMs = nowMs,
                cooldownUntilMs = null,
                lastLatencyMs = result.elapsedMs,
            )
        } else {
            val reason = classifyFailure(result)
            val consecutive = current.consecutiveFailures + 1
            current.copy(
                failureCount = current.failureCount + 1,
                consecutiveFailures = consecutive,
                lastFailureAtMs = nowMs,
                lastFailureReason = reason,
                cooldownUntilMs = nowMs + cooldownMs(reason, consecutive),
                lastLatencyMs = result.elapsedMs,
            )
        }
        health[domain] = next
        lastCheckAtMs = nowMs
        return next
    }

    fun resetCooldowns() {
        health.replaceAll { _, current ->
            current.copy(
                consecutiveFailures = 0,
                cooldownUntilMs = null,
            )
        }
    }

    fun buildSummary(manualDomainRaw: String): CfDomainProbeSummary {
        val rows = snapshot(manualDomainRaw)
        val now = System.currentTimeMillis()
        val manual = rows.firstOrNull { it.source == CfDomainSource.MANUAL }
        val builtIns = rows.filter { it.source == CfDomainSource.BUILT_IN }
        return CfDomainProbeSummary(
            manualResult = manual,
            availableBuiltIn = builtIns.count { it.status(now) == CfDomainStatus.OK },
            failedBuiltIn = builtIns.count { it.status(now) == CfDomainStatus.FAILED || it.status(now) == CfDomainStatus.COOLDOWN },
            uncheckedBuiltIn = builtIns.count { it.status(now) == CfDomainStatus.UNCHECKED },
            bestDomain = rows
                .filter { it.status(now) == CfDomainStatus.OK }
                .minByOrNull { it.lastLatencyMs ?: Long.MAX_VALUE },
        )
    }

    private fun ensureKnownDomains(manualDomainRaw: String) {
        val manual = CfDomain.normalizeOrNull(manualDomainRaw)
        manual?.let { ensureKnownDomain(it, CfDomainSource.MANUAL) }
        CfDomain.builtInDomains.forEach { domain ->
            ensureKnownDomain(domain, CfDomainSource.BUILT_IN)
            if (domain != manual) {
                health[domain]?.takeIf { it.source == CfDomainSource.MANUAL }?.let {
                    health[domain] = it.copy(source = CfDomainSource.BUILT_IN)
                }
            }
        }
    }

    private fun ensureKnownDomain(domain: String, source: CfDomainSource) {
        val current = health[domain]
        if (current == null) {
            health[domain] = CfDomainHealth(domain = domain, source = source)
        } else if (current.source != CfDomainSource.MANUAL && source == CfDomainSource.MANUAL) {
            health[domain] = current.copy(source = CfDomainSource.MANUAL)
        }
    }

    private fun classifyFailure(result: RouteProbeResult): String {
        return when {
            result.stage == "ws_429" -> "http_429"
            result.stage == "ws_403" -> "http_403"
            result.stage.startsWith("ws_5") -> "http_5xx"
            result.detail.contains("timed out", ignoreCase = true) -> "timeout"
            result.detail.contains("handshake", ignoreCase = true) -> "tls"
            else -> "websocket"
        }
    }

    private fun cooldownMs(reason: String, consecutiveFailures: Int): Long {
        val progressive = when {
            consecutiveFailures >= 3 -> 300_000L
            consecutiveFailures == 2 -> 120_000L
            else -> 30_000L
        }
        return when (reason) {
            "http_429" -> max(progressive, 300_000L)
            "http_403" -> max(progressive, 600_000L)
            "http_5xx" -> max(progressive, 120_000L)
            else -> progressive
        }
    }
}
