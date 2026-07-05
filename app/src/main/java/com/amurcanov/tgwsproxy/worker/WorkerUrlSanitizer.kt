package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.DomainMasking
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticReportSanitizer

object WorkerUrlSanitizer {
    fun maskForDisplay(raw: String, maskDomains: Boolean = true): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val domain = com.amurcanov.tgwsproxy.WorkerDomain.normalize(withoutQuery)
        if (domain.isBlank()) {
            return DiagnosticReportSanitizer.sanitize(trimmed.substringBefore('?') + if (trimmed.contains('?')) "?***" else "")
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
}
