package com.amurcanov.tgwsproxy

/**
 * Best-effort sanitizer to reduce risk of persisting sensitive values.
 * This is intentionally conservative (simple pattern-based redaction).
 */
object AppLogSanitizer {
    // key=value (or key:"value") redaction. Keep key, redact value.
    private val patterns = listOf(
        Regex("""(?i)\b(token|auth|auth_key|key|password|authorization|cookie)\s*=\s*("[^"]*"|[^\s]+)"""),
        Regex("""(?i)\b(token|auth|auth_key|key|password|authorization|cookie)\s*:\s*("[^"]*"|[^\s,}]+)"""),
        Regex("""(?i)\b(payload|raw)\s*=\s*("[^"]*"|[^\s]+)"""),
        Regex("""(?i)\b(payload|raw)\s*:\s*("[^"]*"|[^\s,}]+)"""),
    )

    fun sanitizeText(text: String): String {
        var out = text
        patterns.forEach { re ->
            out = out.replace(re) { m ->
                val key = m.groupValues[1]
                "$key=<redacted>"
            }
        }
        return out
    }

    fun sanitizeEvent(event: AppLogEvent): AppLogEvent {
        val safeMessage = sanitizeText(event.message)
        val safeDetails = event.details.mapValues { (_, v) -> sanitizeText(v) }
        return event.copy(message = safeMessage, details = safeDetails)
    }
}

