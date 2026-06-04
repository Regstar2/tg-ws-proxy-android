package com.amurcanov.tgwsproxy

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
}
