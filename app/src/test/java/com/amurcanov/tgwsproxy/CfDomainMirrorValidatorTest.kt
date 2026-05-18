package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfDomainMirrorValidatorTest {
    @Test
    fun validHttpsMirrorAccepted() {
        val result = CfDomainMirrorUrlValidator.validate(
            enabled = true,
            rawUrl = "https://example.com/cfproxy-domains.txt?raw=1",
        )
        assertTrue(result is CfDomainMirrorValidation.Valid)
        assertEquals(
            "https://example.com/cfproxy-domains.txt?raw=1",
            (result as CfDomainMirrorValidation.Valid).url,
        )
    }

    @Test
    fun httpMirrorRejected() {
        val result = CfDomainMirrorUrlValidator.validate(true, "http://example.com/list.txt")
        assertTrue(result is CfDomainMirrorValidation.Invalid)
    }

    @Test
    fun localhostMirrorRejected() {
        val result = CfDomainMirrorUrlValidator.validate(true, "https://localhost/list.txt")
        assertTrue(result is CfDomainMirrorValidation.Invalid)
    }

    @Test
    fun privateIpMirrorRejected() {
        val result = CfDomainMirrorUrlValidator.validate(true, "https://192.168.1.10/list.txt")
        assertTrue(result is CfDomainMirrorValidation.Invalid)
    }

    @Test
    fun credentialsMirrorRejected() {
        val result = CfDomainMirrorUrlValidator.validate(true, "https://user:pass@example.com/list.txt")
        assertTrue(result is CfDomainMirrorValidation.Invalid)
    }

    @Test
    fun emptyMirrorRejectedWhenEnabled() {
        val result = CfDomainMirrorUrlValidator.validate(true, "   ")
        assertTrue(result is CfDomainMirrorValidation.Invalid)
    }

    @Test
    fun disabledMirrorIgnored() {
        val result = CfDomainMirrorUrlValidator.validate(false, "http://localhost/list.txt")
        assertEquals(CfDomainMirrorValidation.Disabled, result)
    }
}
