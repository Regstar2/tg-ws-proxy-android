package com.amurcanov.tgwsproxy

object PoolMetricsFormatter {
    fun formatMarkdown(metrics: ProxyRuntimeMetrics): String {
        return buildString {
            appendLine("## Route Pool Metrics")
            appendLine()
            appendLine("- Worker pool hits: ${metrics.workerPoolHits}")
            appendLine("- Worker pool misses: ${metrics.workerPoolMisses}")
            appendLine("- Worker pool idle: ${metrics.workerPoolIdle}")
            appendLine("- Worker pool refill errors: ${metrics.workerPoolErrors}")
            appendLine("- CF pool hits: ${metrics.cfPoolHits}")
            appendLine("- CF pool misses: ${metrics.cfPoolMisses}")
            appendLine("- CF pool idle: ${metrics.cfPoolIdle}")
            appendLine("- CF pool refill errors: ${metrics.cfPoolErrors}")
        }
    }

    fun hasAnyPoolMetric(metrics: ProxyRuntimeMetrics): Boolean {
        return metrics.workerPoolHits > 0 ||
            metrics.workerPoolMisses > 0 ||
            metrics.workerPoolIdle > 0 ||
            metrics.workerPoolErrors > 0 ||
            metrics.cfPoolHits > 0 ||
            metrics.cfPoolMisses > 0 ||
            metrics.cfPoolIdle > 0 ||
            metrics.cfPoolErrors > 0
    }
}
