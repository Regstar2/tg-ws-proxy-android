package com.amurcanov.tgwsproxy

import java.net.URI

object WorkerDomain {
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        val lower = s.lowercase()
        if (lower.startsWith("https://")) {
            s = s.substring(8)
        } else if (lower.startsWith("http://")) {
            s = s.substring(7)
        }
        s = s.trim()

        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) {
            s = s.substring(0, cut)
        }
        s = s.trimEnd('/').trim()

        return try {
            val host = URI("https://$s").host
            host?.trim().orEmpty()
        } catch (_: Exception) {
            s
        }
    }

    fun validationWarning(domain: String): Int? {
        if (domain.isBlank()) return R.string.worker_domain_empty
        if (domain.contains(' ')) return R.string.worker_domain_invalid
        if (domain.contains("/apiws", ignoreCase = true)) return R.string.worker_domain_no_path
        if (!domain.contains('.')) return R.string.worker_domain_invalid
        if (!domain.endsWith(".workers.dev", ignoreCase = true)) {
            return R.string.worker_domain_not_workers_dev
        }
        return null
    }

    fun buildTestUrl(domain: String, dc: Int, dcIp: String, media: Boolean): String {
        val mediaVal = if (media) 1 else 0
        return "wss://$domain/apiws?dst=$dcIp&dc=$dc&media=$mediaVal"
    }
}
