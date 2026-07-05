package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.WorkerDomain
import java.net.URI

enum class WorkerValidationError {
    EMPTY_NAME,
    EMPTY_URL,
    INVALID_URL,
}

object WorkerEndpointValidator {
    fun validate(name: String, url: String): WorkerValidationError? {
        if (name.trim().isEmpty()) {
            return WorkerValidationError.EMPTY_NAME
        }
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            return WorkerValidationError.EMPTY_URL
        }
        if (!isSupportedUrl(trimmedUrl)) {
            return WorkerValidationError.INVALID_URL
        }
        val normalized = WorkerDomain.normalize(trimmedUrl)
        if (normalized.isBlank() || !normalized.contains('.')) {
            return WorkerValidationError.INVALID_URL
        }
        return null
    }

    private fun isSupportedUrl(raw: String): Boolean {
        val lower = raw.lowercase()
        val hasScheme = lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            lower.startsWith("wss://") ||
            lower.startsWith("ws://")
        return try {
            val uri = if (hasScheme) {
                URI(raw.trim())
            } else {
                URI("https://${raw.trim()}")
            }
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
