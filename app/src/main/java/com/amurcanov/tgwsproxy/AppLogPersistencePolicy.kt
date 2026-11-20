package com.amurcanov.tgwsproxy

internal object AppLogPersistencePolicy {
    const val MAX_STACK_TRACE_CHARS = 16_384

    fun withStackTrace(
        event: AppLogEvent,
        throwable: Throwable?,
        debugBuild: Boolean,
    ): AppLogEvent {
        if (!debugBuild || throwable == null) return event

        val stackTrace = AppLogSanitizer
            .sanitizeText(throwable.stackTraceToString())
            .take(MAX_STACK_TRACE_CHARS)

        return event.copy(
            details = event.details + ("stacktrace" to stackTrace),
        )
    }
}
