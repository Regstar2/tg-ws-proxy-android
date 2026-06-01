package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolMetricsFormatterTest {
    @Test
    fun formatter_includesPoolMetricsInMarkdown() {
        val markdown = PoolMetricsFormatter.formatMarkdown(
            ProxyRuntimeMetrics(
                workerPoolHits = 3,
                workerPoolMisses = 5,
                workerPoolIdle = 2,
                workerPoolErrors = 1,
                cfPoolHits = 4,
                cfPoolMisses = 2,
                cfPoolIdle = 1,
                cfPoolErrors = 0,
            ),
        )

        assertTrue(markdown.contains("Worker pool hits: 3"))
        assertTrue(markdown.contains("Worker pool misses: 5"))
        assertTrue(markdown.contains("CF pool hits: 4"))
        assertTrue(markdown.contains("CF pool idle: 1"))
    }

    @Test
    fun formatter_doesNotIncludeDomainsOrRuntimeTokens() {
        val markdown = PoolMetricsFormatter.formatMarkdown(ProxyRuntimeMetrics(workerPoolHits = 1))

        assertFalse(markdown.contains("example.workers.dev"))
        assertFalse(markdown.contains("@connection_mode"))
        assertFalse(markdown.contains("@worker_domain"))
    }
}
