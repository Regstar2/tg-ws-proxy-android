package com.amurcanov.tgwsproxy

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionRuntimeConfigTest {
    @Test
    fun buildRuntimeTokens_workerFirst() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerFirst,
            cfProxyEnabled = true,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "pclead.co.uk",
            workerEnabled = true,
            workerDomain = "example.username.workers.dev",
        )
        assertTrue(raw.contains("@connection_mode=worker_first"))
        assertTrue(raw.contains("@worker_domain=example.username.workers.dev"))
        assertTrue(raw.contains("@worker_enabled=1"))
    }

    @Test
    fun buildRuntimeTokens_workerOnlyWithoutDomainStillEnablesFlag() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.WorkerOnly,
            cfProxyEnabled = false,
            cfProxyPriority = false,
            cfProxyOnly = false,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
        )
        assertTrue(raw.contains("@connection_mode=worker_only"))
        assertTrue(raw.contains("@worker_enabled=1"))
    }

    @Test
    fun buildRuntimeTokens_normalizesManualCfDomain() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "https://virkgj.com/apiws",
            workerEnabled = false,
            workerDomain = "",
        )
        assertTrue(raw.contains("@cfproxy_domain=virkgj.com"))
    }

    @Test
    fun buildRuntimeTokens_rejectsInvalidManualCfDomain() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "domain with spaces.com",
            workerEnabled = false,
            workerDomain = "",
        )
        assertFalse(raw.contains("@cfproxy_domain="))
    }

    @Test
    fun buildRuntimeTokens_includesCachedCfDomains() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "",
            workerEnabled = false,
            workerDomain = "",
            cachedCfDomains = listOf("cached-a.example", "https://cached-b.example/apiws"),
        )

        assertTrue(raw.contains("@cf_cached_domains=cached-a.example|cached-b.example"))
    }

    @Test
    fun buildRuntimeTokens_includesMultipleManualCfDomains() {
        val raw = ConnectionRuntimeConfig.buildRuntimeTokens(
            dcEntries = listOf("2:149.154.167.220"),
            mode = ConnectionMode.CFOnly,
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyOnly = true,
            cfDomain = "",
            manualCfDomains = listOf("manual-a.example", "https://manual-b.example/apiws"),
            workerEnabled = false,
            workerDomain = "",
        )

        assertTrue(raw.contains("@cf_manual_domains=manual-a.example|manual-b.example"))
        assertTrue(raw.contains("@cfproxy_domain=manual-a.example"))
    }
}
