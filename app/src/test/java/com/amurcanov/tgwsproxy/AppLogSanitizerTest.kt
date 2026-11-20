package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogSanitizerTest {
    @Test
    fun masksTokenEquals() {
        val out = AppLogSanitizer.sanitizeText("token=abc")
        assertTrue(out.contains("token=***"))
        assertFalse(out.contains("token=abc"))
    }

    @Test
    fun masksAuthorizationBearer() {
        val out = AppLogSanitizer.sanitizeText("Authorization: Bearer abc")
        assertTrue(out.contains("Authorization: ***"))
        assertFalse(out.contains("Bearer abc"))
    }

    @Test
    fun masksUrlQueryParams() {
        val out = AppLogSanitizer.sanitizeText("https://host/path?secret=abc")
        assertTrue(out.contains("?***"))
        assertFalse(out.contains("secret=abc"))
    }

    @Test
    fun masksSensitiveDetailValuesUsingTheirKeys() {
        val detailSecret = "detail-secret-regression-test"
        val bearerSecret = "bearer-secret-regression-test"

        val safe = AppLogSanitizer.sanitizeEvent(
            AppLogEvent(
                level = AppLogLevel.ERROR,
                category = AppLogCategory.APP,
                message = "sanitizer regression test",
                details = mapOf(
                    "token" to detailSecret,
                    "Authorization" to "Bearer $bearerSecret",
                    "workerToken" to "worker-token-secret",
                    "APIKey" to "api-key-secret",
                    "ordinary" to "visible-value",
                ),
            ),
        )

        assertEquals("***", safe.details["token"])
        assertEquals("***", safe.details["Authorization"])
        assertEquals("***", safe.details["workerToken"])
        assertEquals("***", safe.details["APIKey"])
        assertEquals("visible-value", safe.details["ordinary"])
        assertFalse(safe.details.values.any {
            it.contains(detailSecret) || it.contains(bearerSecret)
        })
    }
}
