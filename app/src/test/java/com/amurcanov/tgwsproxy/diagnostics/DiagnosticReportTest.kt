package com.amurcanov.tgwsproxy.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportSanitizerTest {
    @Test
    fun masksTokenQueryParam() {
        val out = DiagnosticReportSanitizer.sanitize("https://host/path?token=abc")
        assertTrue(out.contains("?***"))
        assertFalse(out.contains("token=abc"))
    }

    @Test
    fun masksAuthorizationBearer() {
        val out = DiagnosticReportSanitizer.sanitize("Authorization: Bearer secret123")
        assertTrue(out.contains("Authorization: ***"))
        assertFalse(out.contains("secret123"))
    }

    @Test
    fun masksSecretAssignment() {
        val out = DiagnosticReportSanitizer.sanitize("secret=my-value")
        assertTrue(out.contains("secret=***"))
        assertFalse(out.contains("my-value"))
    }

    @Test
    fun masksMtProtoSecretAssignment() {
        val secret = "0123456789abcdef0123456789abcdef"
        val out = DiagnosticReportSanitizer.sanitize("mtproto_secret=$secret")
        assertTrue(out.contains("mtproto_secret=***"))
        assertFalse(out.contains(secret))
    }
}
