package com.amurcanov.tgwsproxy

/**
 * Best-effort sanitizer to reduce risk of persisting sensitive values.
 * This is intentionally conservative (simple pattern-based redaction).
 */
object AppLogSanitizer {
    private val sensitiveDetailKeyPattern = Regex("""(?:^|_)(?:token|authorization|auth|bearer|api_key|key|password|cookie|secret|payload|raw)(?:_|$)""")
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
        val safeDetails = event.details.mapValues { (key, value) ->
            val acronymNormalizedKey = Regex("([A-Z]+)([A-Z][a-z])").replace(key) { match ->
                "${match.groupValues[1]}_${match.groupValues[2]}"
            }
            val normalizedKey = Regex("([a-z0-9])([A-Z])").replace(acronymNormalizedKey) { match ->
                "${match.groupValues[1]}_${match.groupValues[2]}"
            }.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            if (sensitiveDetailKeyPattern.containsMatchIn(normalizedKey)) "***" else sanitizeText(value)
        }
        return event.copy(message = safeMessage, details = safeDetails)
    }
}

