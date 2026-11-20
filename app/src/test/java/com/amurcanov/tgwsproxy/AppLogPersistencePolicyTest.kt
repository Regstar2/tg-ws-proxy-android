package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogPersistencePolicyTest {
    private fun event() = AppLogEvent(
        timestampMs = 1_700_000_000_000L,
        level = AppLogLevel.ERROR,
        category = AppLogCategory.APP,
        message = "Runtime operation failed",
        details = mapOf("request_id" to "request-123"),
    )

    @Test
    fun debugBuildPersistsSanitizedStackTrace() {
        val original = event()
        val secret = "stacktrace-secret-123"
        val bearer = "bearer-secret-456"
        val throwable = IllegalStateException(
            "token=$secret Authorization: Bearer $bearer",
        )

        val result = AppLogPersistencePolicy.withStackTrace(
            event = original,
            throwable = throwable,
            debugBuild = true,
        )

        val stackTrace = result.details["stacktrace"]
        assertTrue("Debug stack trace must be persisted", !stackTrace.isNullOrBlank())
        assertTrue(stackTrace!!.contains("token=***"))
        assertTrue(stackTrace.contains("Authorization: ***"))
        assertFalse(stackTrace.contains(secret))
        assertFalse(stackTrace.contains(bearer))
        assertEquals("request-123", result.details["request_id"])

        val formatted = AppLogFormatter.formatForFile(result)
        assertTrue(formatted.contains("stacktrace="))
        assertTrue(formatted.contains("token=***"))

        assertFalse(original.details.containsKey("stacktrace"))
    }

    @Test
    fun releaseBuildDoesNotPersistThrowableStackTrace() {
        val original = event()

        val result = AppLogPersistencePolicy.withStackTrace(
            event = original,
            throwable = IllegalStateException("test failure"),
            debugBuild = false,
        )

        assertSame(original, result)
        assertFalse(result.details.containsKey("stacktrace"))
    }

    @Test
    fun debugEventWithoutThrowableDoesNotCreateStackTrace() {
        val original = event()

        val result = AppLogPersistencePolicy.withStackTrace(
            event = original,
            throwable = null,
            debugBuild = true,
        )

        assertSame(original, result)
        assertFalse(result.details.containsKey("stacktrace"))
    }

    @Test
    fun stackTraceIsLimitedTo16384Characters() {
        val throwable = IllegalStateException(
            "x".repeat(AppLogPersistencePolicy.MAX_STACK_TRACE_CHARS * 2),
        )

        val result = AppLogPersistencePolicy.withStackTrace(
            event = event(),
            throwable = throwable,
            debugBuild = true,
        )

        val stackTrace = result.details["stacktrace"]
        assertNotSame(null, stackTrace)
        assertEquals(AppLogPersistencePolicy.MAX_STACK_TRACE_CHARS, stackTrace!!.length)
    }
}
