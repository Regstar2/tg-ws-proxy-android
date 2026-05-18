package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfDomainTest {
    @Test
    fun normalize_urlsAndWhitespace() {
        assertEquals("virkgj.com", CfDomain.normalize("https://virkgj.com/"))
        assertEquals("virkgj.com", CfDomain.normalize("http://virkgj.com/apiws"))
        assertEquals("virkgj.com", CfDomain.normalize(" virkgj.com "))
    }

    @Test
    fun normalize_rejectsInvalidDomains() {
        assertNull(CfDomain.normalizeOrNull("domain with spaces.com"))
        assertNull(CfDomain.normalizeOrNull("example.com:443"))
        assertNull(CfDomain.normalizeOrNull("example.com/apiws"))
        assertNull(CfDomain.normalizeOrNull("wss://example.com"))
        assertNull(CfDomain.normalizeOrNull("localhost"))
        assertNull(CfDomain.normalizeOrNull("127.0.0.1"))
        assertNull(CfDomain.normalizeOrNull("*.example.com"))
    }

    @Test
    fun builtInPool_isValidAndUnique() {
        assertTrue(CfDomain.builtInDomains.isNotEmpty())
        assertEquals(CfDomain.builtInDomains.distinct(), CfDomain.builtInDomains)
        assertTrue(CfDomain.builtInDomains.all { CfDomain.normalizeOrNull(it) == it })
    }

    @Test
    fun manualList_parsesMultipleDomainsAndKeepsInvalidEntriesVisible() {
        val parsed = CfManualDomainList.parse(
            """
            Manual-A.example
            https://manual-b.example/apiws
            invalid domain.example
            manual-a.example
            """.trimIndent()
        )

        assertEquals(listOf("manual-a.example", "manual-b.example"), parsed.domains)
        assertEquals(listOf("invalid domain.example"), parsed.invalidEntries)
    }

    @Test
    fun diagnostics_resetCooldownClearsState() {
        val result = RouteProbeResult(
            route = "cf_pool",
            dc = 2,
            success = false,
            stage = "ws_429",
            elapsedMs = 180,
            detail = "HTTP/1.1 429 Too Many Requests",
        )
        val row = CfDomainDiagnosticsState.markProbe("manual.example", CfDomainSource.MANUAL, result, 1000)
        assertEquals(CfDomainStatus.COOLDOWN, row.status(1001))

        CfDomainDiagnosticsState.resetCooldowns()
        val reset = CfDomainDiagnosticsState.snapshot("manual.example").first { it.domain == "manual.example" }
        assertFalse(reset.status(1001) == CfDomainStatus.COOLDOWN)
    }
}
