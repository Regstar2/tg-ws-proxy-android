package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerDomainTest {
    @Test
    fun normalize_plainDomain() {
        assertEquals("example.username.workers.dev", WorkerDomain.normalize("example.username.workers.dev"))
    }

    @Test
    fun normalize_httpsTrailingSlash() {
        assertEquals(
            "example.username.workers.dev",
            WorkerDomain.normalize("https://example.username.workers.dev/")
        )
    }

    @Test
    fun normalize_stripsPathAndQuery() {
        assertEquals(
            "example.username.workers.dev",
            WorkerDomain.normalize("http://example.username.workers.dev/apiws?dst=1.1.1.1")
        )
    }

    @Test
    fun normalize_trimsWhitespace() {
        assertEquals("example.username.workers.dev", WorkerDomain.normalize("  example.username.workers.dev  "))
    }

    @Test
    fun buildTestUrl_nonMedia() {
        val url = WorkerDomain.buildTestUrl("example.username.workers.dev", 2, "149.154.167.50", false)
        assertEquals(
            "wss://example.username.workers.dev/apiws?dst=149.154.167.50&dc=2&media=0",
            url
        )
    }

    @Test
    fun buildTestUrl_media() {
        val url = WorkerDomain.buildTestUrl("example.username.workers.dev", 2, "149.154.167.50", true)
        assertEquals(
            "wss://example.username.workers.dev/apiws?dst=149.154.167.50&dc=2&media=1",
            url
        )
    }

    @Test
    fun validationWarning_empty() {
        assertEquals(R.string.worker_domain_empty, WorkerDomain.validationWarning(""))
    }

    @Test
    fun validationWarning_validWorkersDev() {
        assertNull(WorkerDomain.validationWarning("example.username.workers.dev"))
    }
}
