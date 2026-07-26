package com.amurcanov.tgwsproxy

/**
 * Best-effort sanitizer to reduce risk of persisting sensitive values.
 * This is intentionally conservative (simple pattern-based redaction).
 */
object AppLogSanitizer {
    // key=value (or key:"value") redaction. Keep key, redact value.
    private val patterns = listOf(
        Regex("""(?i)\b(mtproto_secret|mtprotoSecret|secret_key|secretKey)\s*=\s*("[^"]*"|[^\s&]+)"""),
        Regex("""(?i)\b(mtproto_secret|mtprotoSecret|secret_key|secretKey)\s*:\s*("[^"]*"|[^\s,}]+)"""),
        Regex("""(?i)\b(token|auth|auth_key|key|password|cookie|secret)\s*=\s*("[^"]*"|[^\s&]+)"""),
        Regex("""(?i)\b(token|auth|auth_key|key|password|cookie|secret)\s*:\s*("[^"]*"|[^\s,}]+)"""),
        Regex("""(?i)\b(payload|raw)\s*=\s*("[^"]*"|[^\s]+)"""),
        Regex("""(?i)\b(payload|raw)\s*:\s*("[^"]*"|[^\s,}]+)"""),
    )
    private val bearerPattern = Regex("""(?i)\bAuthorization\s*:\s*Bearer\s+\S+""")
    private val urlQueryPattern = Regex("""(\?)([^#\s"]+)""")

    fun sanitizeText(text: String): String {
        var out = text
        out = bearerPattern.replace(out, "Authorization: ***")
        patterns.forEach { re ->
            out = out.replace(re) { m ->
                val key = m.groupValues[1]
                "$key=***"
            }
        }
        out = urlQueryPattern.replace(out) { m ->
            "${m.groupValues[1]}***"
        }
        return out
    }

    fun sanitizeEvent(event: AppLogEvent): AppLogEvent {
        val safeMessage = sanitizeText(event.message)
        val safeDetails = event.details.mapValues { (_, v) -> sanitizeText(v) }
        return event.copy(message = safeMessage, details = safeDetails)
    }
}

