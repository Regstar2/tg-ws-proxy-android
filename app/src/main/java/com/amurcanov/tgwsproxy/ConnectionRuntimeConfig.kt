package com.amurcanov.tgwsproxy

object ConnectionRuntimeConfig {
    private val defaultDcIps = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    fun buildRuntimeTokens(
        dcEntries: List<String>,
        mode: ConnectionMode,
        cfProxyEnabled: Boolean,
        cfProxyPriority: Boolean,
        cfProxyOnly: Boolean,
        cfDomain: String,
        workerEnabled: Boolean,
        workerDomain: String,
    ): String {
        val effectiveMode = when {
            mode != ConnectionMode.DirectWithFallback -> mode
            else -> ConnectionMode.fromLegacy(cfProxyEnabled, cfProxyPriority, cfProxyOnly)
        }

        val cfEnabled = when (effectiveMode) {
            ConnectionMode.DirectOnly -> false
            else -> true
        }
        val cfPriority = effectiveMode == ConnectionMode.CFFirst
        val cfOnly = effectiveMode == ConnectionMode.CFOnly

        val tokens = buildList {
            addAll(dcEntries)
            add("@connection_mode=${effectiveMode.prefValue}")
            add("@cfproxy=${if (cfEnabled) 1 else 0}")
            add("@cfproxy_priority=${if (cfPriority) 1 else 0}")
            add("@cfproxy_only=${if (cfOnly) 1 else 0}")
            val domain = CfDomain.normalize(cfDomain)
            if (domain.isNotBlank()) {
                add("@cfproxy_domain=$domain")
                add("@cf_use_manual_domain=1")
            }
            val normalizedWorker = WorkerDomain.normalize(workerDomain)
            val workerOn = workerEnabled || normalizedWorker.isNotBlank() ||
                effectiveMode == ConnectionMode.WorkerFirst ||
                effectiveMode == ConnectionMode.WorkerOnly
            add("@worker_enabled=${if (workerOn) 1 else 0}")
            if (normalizedWorker.isNotBlank()) {
                add("@worker_domain=$normalizedWorker")
            }
        }
        return tokens.joinToString(",")
    }

    fun dcIpForTest(dc: Int): String = defaultDcIps[dc] ?: defaultDcIps[2]!!
}
