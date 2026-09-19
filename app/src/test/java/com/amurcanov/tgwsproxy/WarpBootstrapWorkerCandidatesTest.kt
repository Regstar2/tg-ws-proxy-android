package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpBootstrapWorkerCandidatesTest {
    @Test
    fun customWorkersComeBeforeBuiltInWorkers() {
        val settings = WarpProvisioningBootstrapSettings(
            useBuiltInWorkers = true,
            customWorkers = listOf(
                worker("custom-2", "https://second.example.workers.dev", order = 2),
                worker("custom-1", "https://first.example.workers.dev", order = 1),
            ),
        )

        val result = WarpBootstrapWorkerCandidates.resolve(
            settings = settings,
            builtInUrls = listOf(
                "https://builtin-a.example.workers.dev",
                "https://builtin-b.example.workers.dev",
            ),
        )

        assertEquals(
            listOf(
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.CUSTOM,
                    1,
                    "https://first.example.workers.dev",
                ),
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.CUSTOM,
                    2,
                    "https://second.example.workers.dev",
                ),
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.BUILT_IN,
                    1,
                    "https://builtin-a.example.workers.dev",
                ),
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.BUILT_IN,
                    2,
                    "https://builtin-b.example.workers.dev",
                ),
            ),
            result,
        )
    }

    @Test
    fun disabledCustomAndBuiltInToggleAreRespected() {
        val settings = WarpProvisioningBootstrapSettings(
            useBuiltInWorkers = false,
            customWorkers = listOf(
                worker("enabled", "https://enabled.example.workers.dev", order = 0),
                worker(
                    "disabled",
                    "https://disabled.example.workers.dev",
                    order = 1,
                    enabled = false,
                ),
            ),
        )

        val result = WarpBootstrapWorkerCandidates.resolve(
            settings = settings,
            builtInUrls = listOf("https://builtin.example.workers.dev"),
        )

        assertEquals(
            listOf(
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.CUSTOM,
                    1,
                    "https://enabled.example.workers.dev",
                ),
            ),
            result,
        )
    }

    @Test
    fun duplicateBuiltInWorkerDoesNotOverrideCustomSource() {
        val settings = WarpProvisioningBootstrapSettings(
            useBuiltInWorkers = true,
            customWorkers = listOf(
                worker("custom", "https://same.example.workers.dev/", order = 0),
            ),
        )

        val result = WarpBootstrapWorkerCandidates.resolve(
            settings = settings,
            builtInUrls = listOf("https://same.example.workers.dev"),
        )

        assertEquals(
            listOf(
                WarpBootstrapWorkerCandidate(
                    WarpBootstrapWorkerSource.CUSTOM,
                    1,
                    "https://same.example.workers.dev",
                ),
            ),
            result,
        )
    }

    private fun worker(
        id: String,
        url: String,
        order: Int,
        enabled: Boolean = true,
    ) = ProvisioningWorkerEndpoint(
        id = id,
        url = url,
        enabled = enabled,
        order = order,
        lastHealthStatus = ProvisioningWorkerHealthStatus.UNCHECKED,
        lastCheckedAtMs = null,
        lastErrorCode = null,
    )
}
