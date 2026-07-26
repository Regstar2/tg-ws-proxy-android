package com.amurcanov.tgwsproxy.diagnostics

import com.amurcanov.tgwsproxy.AppLogSanitizer

/**
 * Sanitizes diagnostic report text and log lines before copy/share/persist.
 */
object DiagnosticReportSanitizer {
    private val bearerPattern = Regex("""(?i)\bAuthorization\s*:\s*Bearer\s+\S+""")
    private val bearerInline = Regex("""(?i)\bBearer\s+\S+""")
    private val urlQueryPattern = Regex("""(\?)([^#\s"]+)""")
    private val secretPatterns = listOf(
        Regex("""(?i)\b(mtproto_secret|mtprotoSecret|secret_key|secretKey)\s*=\s*[^\s&"']+"""),
        Regex("""(?i)\b(mtproto_secret|mtprotoSecret|secret_key|secretKey)\s*:\s*[^\s,}]+"""),
        Regex("""(?i)\b(token|secret|password|auth_key|api_key|cookie)\s*=\s*[^\s&"']+"""),
        Regex("""(?i)\b(token|secret|password|auth_key|api_key|cookie)\s*:\s*[^\s,}]+"""),
    )

    fun sanitize(text: String): String {
        var out = AppLogSanitizer.sanitizeText(text)
        out = bearerPattern.replace(out, "Authorization: ***")
        out = bearerInline.replace(out, "Bearer ***")
        secretPatterns.forEach { re ->
            out = re.replace(out) { m ->
                val key = m.groupValues[1]
                "$key=***"
            }
        }
        out = urlQueryPattern.replace(out) { m ->
            "${m.groupValues[1]}***"
        }
        return out
    }

    fun maskWorkerHost(raw: String, maskDomains: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "none"
        if (!maskDomains) return sanitize(trimmed)
        return sanitize(com.amurcanov.tgwsproxy.DomainMasking.mask(trimmed))
    }

    fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
