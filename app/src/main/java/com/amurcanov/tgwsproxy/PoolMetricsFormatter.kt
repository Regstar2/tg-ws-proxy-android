package com.amurcanov.tgwsproxy

object PoolMetricsFormatter {
    fun formatMarkdown(metrics: ProxyRuntimeMetrics): String {
        return buildString {
            appendLine("## Route Pool Metrics")
            appendLine()
            appendLine("- Worker endpoint pool hits: ${metrics.workerEndpointPoolHits}")
            appendLine("- Worker endpoint pool misses: ${metrics.workerEndpointPoolMisses}")
            if (metrics.workerWsPreconnectEnabled) {
                appendLine("- Worker WS preconnect hits: ${metrics.workerWsPreconnectHits}")
                appendLine("- Worker WS preconnect misses: ${metrics.workerWsPreconnectMisses}")
                appendLine("- Worker WS preconnect idle: ${metrics.workerWsPreconnectIdle}")
                appendLine("- Worker WS preconnect refill errors: ${metrics.workerWsPreconnectErrors}")
            } else {
                appendLine("- Worker WS preconnect: disabled (cf_worker_ws requires first payload)")
            }
            appendLine("- CF pool hits: ${metrics.cfPoolHits}")
            appendLine("- CF pool misses: ${metrics.cfPoolMisses}")
            appendLine("- CF pool idle: ${metrics.cfPoolIdle}")
            appendLine("- CF pool refill errors: ${metrics.cfPoolErrors}")
            if (metrics.destinationModeStats.isNotEmpty()) {
                appendLine()
                appendLine("## Worker destination mode A/B")
                metrics.destinationModeStats.forEach { entry ->
                    appendLine(
                        "- ${entry.destinationMode}: total=${entry.sessionsTotal}, " +
                            "bidirectional=${entry.sessionsBidirectional}, zero_down=${entry.sessionsZeroDown}, " +
                            "up_bytes=${entry.upBytesTotal}, down_bytes=${entry.downBytesTotal}, " +
                            "avg_ms=${entry.avgDurationMs}, media_fix=${entry.mediaFixApplied}",
                    )
                    if (entry.originalParsedDst.isNotBlank() || entry.workerDst.isNotBlank()) {
                        appendLine(
                            "  dst: orig=${entry.originalParsedDst}, worker=${entry.workerDst}, " +
                                "dc=${entry.mappedDc}, is_media=${entry.isMedia}, fix_applied=${entry.flowsealMediaFixApplied}",
                        )
                    }
                    if (entry.closeReason.isNotBlank()) {
                        appendLine("  close_reason: ${entry.closeReason}")
                    }
                }
            }
        }
    }

    fun hasAnyPoolMetric(metrics: ProxyRuntimeMetrics): Boolean {
        return metrics.workerEndpointPoolHits > 0 ||
            metrics.workerEndpointPoolMisses > 0 ||
            (metrics.workerWsPreconnectEnabled && (
                metrics.workerWsPreconnectHits > 0 ||
                    metrics.workerWsPreconnectMisses > 0 ||
                    metrics.workerWsPreconnectIdle > 0 ||
                    metrics.workerWsPreconnectErrors > 0
                )) ||
            metrics.cfPoolHits > 0 ||
            metrics.cfPoolMisses > 0 ||
            metrics.cfPoolIdle > 0 ||
            metrics.cfPoolErrors > 0 ||
            metrics.destinationModeStats.isNotEmpty()
    }
}
