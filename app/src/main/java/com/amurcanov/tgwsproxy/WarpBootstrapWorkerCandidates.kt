package com.amurcanov.tgwsproxy

import android.content.Context

internal enum class WarpBootstrapWorkerSource(val wireValue: String) {
    CUSTOM("custom"),
    BUILT_IN("built_in"),
}

internal data class WarpBootstrapWorkerCandidate(
    val source: WarpBootstrapWorkerSource,
    val sourceIndex: Int,
    val url: String,
)

internal object WarpBootstrapWorkerCandidates {
    fun load(context: Context): List<WarpBootstrapWorkerCandidate> {
        val settings = WarpProvisioningBootstrapSettingsRepository(context.applicationContext).load()
        return resolve(
            settings = settings,
            builtInUrls = BuiltInWarpProvisioningWorkers.endpoints,
        )
    }

    internal fun resolve(
        settings: WarpProvisioningBootstrapSettings,
        builtInUrls: List<String>,
    ): List<WarpBootstrapWorkerCandidate> {
        val candidates = mutableListOf<WarpBootstrapWorkerCandidate>()
        val seen = linkedSetOf<String>()

        settings.customWorkers
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.order }
            .forEachIndexed { index, worker ->
                val normalized = runCatching {
                    ProvisioningWorkerUrlValidator.normalize(worker.url)
                }.getOrNull() ?: return@forEachIndexed
                if (seen.add(normalized.lowercase())) {
                    candidates += WarpBootstrapWorkerCandidate(
                        source = WarpBootstrapWorkerSource.CUSTOM,
                        sourceIndex = index + 1,
                        url = normalized,
                    )
                }
            }

        if (settings.useBuiltInWorkers) {
            builtInUrls.forEachIndexed { index, rawUrl ->
                val normalized = runCatching {
                    ProvisioningWorkerUrlValidator.normalize(rawUrl)
                }.getOrNull() ?: return@forEachIndexed
                if (seen.add(normalized.lowercase())) {
                    candidates += WarpBootstrapWorkerCandidate(
                        source = WarpBootstrapWorkerSource.BUILT_IN,
                        sourceIndex = index + 1,
                        url = normalized,
                    )
                }
            }
        }

        return candidates
    }
}
