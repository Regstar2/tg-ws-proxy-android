package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.DomainMasking
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticReportSanitizer
import java.net.URI

object WorkerUrlSanitizer {
    fun maskForDisplay(raw: String, maskDomains: Boolean = true): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        val parsed = parseWorkerUri(trimmed)
        if (parsed != null && !parsed.host.isNullOrBlank()) {
            val domain = parsed.host.trim()
            val host = if (maskDomains) DomainMasking.mask(domain) else domain
            val path = parsed.rawPath
                ?.takeIf { it.isNotBlank() && it != "/" }
                .orEmpty()
            val query = if (!parsed.rawQuery.isNullOrBlank()) "?***" else ""
            return DiagnosticReportSanitizer.sanitize("https://$host$path$query")
        }

        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val domain = com.amurcanov.tgwsproxy.WorkerDomain.normalize(withoutQuery)
        if (domain.isBlank()) {
            val query = if (trimmed.contains('?')) "?***" else ""
            return DiagnosticReportSanitizer.sanitize(withoutQuery.substringAfterLast('@').ifBlank { "unknown" } + query)
        }
        val host = if (maskDomains) DomainMasking.mask(domain) else domain
        val path = withoutQuery.substringAfter(domain, "")
            .trimStart('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()
        val masked = "https://$host$path"
        return if (trimmed.contains('?')) {
            DiagnosticReportSanitizer.sanitize("$masked?***")
        } else {
            DiagnosticReportSanitizer.sanitize(masked)
        }
    }

    fun maskForLog(raw: String): String = maskForDisplay(raw, maskDomains = true)

    private fun parseWorkerUri(raw: String): URI? {
        val lower = raw.lowercase()
        val hasScheme = lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            lower.startsWith("wss://") ||
            lower.startsWith("ws://")
        val candidate = if (hasScheme) raw else "https://$raw"
        return try {
            URI(candidate)
        } catch (_: Exception) {
            null
        }
    }
}
