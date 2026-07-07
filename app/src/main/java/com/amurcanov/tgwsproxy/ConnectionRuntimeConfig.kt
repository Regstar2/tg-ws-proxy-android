package com.amurcanov.tgwsproxy

import com.amurcanov.tgwsproxy.worker.WorkerDestinationMode
import com.amurcanov.tgwsproxy.worker.WorkerFailoverPayload

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
        manualCfDomains: List<String> = emptyList(),
        workerEnabled: Boolean,
        workerDomain: String,
        cachedCfDomains: List<String> = emptyList(),
        networkProfile: NetworkProfile? = null,
        adaptiveRouteStats: String = "",
        autoStrategy: AutoStrategy = AutoStrategy.BALANCED,
        routePolicy: NetworkRoutePolicy? = null,
        workerFailover: WorkerFailoverPayload? = null,
        workerDestinationMode: WorkerDestinationMode = WorkerDestinationMode.PRESERVE_ORIGINAL_DST,
        flowsealMediaFixEnabled: Boolean = false,
        flowsealMediaFixDc: Int = WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC,
        flowsealMediaFixIp: String = WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP,
    ): String {
        val effectiveMode = if (routePolicy != null) {
            NetworkRoutePolicyMapper.toLegacyConnectionMode(routePolicy)
        } else {
            when {
                mode != ConnectionMode.DirectWithFallback -> mode
                else -> ConnectionMode.fromLegacy(cfProxyEnabled, cfProxyPriority, cfProxyOnly)
            }
        }

        val cfEnabled = if (routePolicy != null) {
            RouteKind.CF_PROXY_WS in routePolicy.enabledRoutes
        } else {
            when (effectiveMode) {
                ConnectionMode.DirectOnly -> false
                else -> true
            }
        }
        val cfPriority = if (routePolicy != null) {
            routePolicy.preferredRoute == RouteKind.CF_PROXY_WS && routePolicy.allowFallback
        } else {
            effectiveMode == ConnectionMode.CFFirst
        }
        val cfOnly = if (routePolicy != null) {
            routePolicy.enabledRoutes == setOf(RouteKind.CF_PROXY_WS) && !routePolicy.allowFallback
        } else {
            effectiveMode == ConnectionMode.CFOnly
        }

        val tokens = buildList {
            addAll(dcEntries)
            add("@connection_mode=${effectiveMode.prefValue}")
            add("@cfproxy=${if (cfEnabled) 1 else 0}")
            add("@cfproxy_priority=${if (cfPriority) 1 else 0}")
            add("@cfproxy_only=${if (cfOnly) 1 else 0}")
            routePolicy?.let { policy ->
                add("@route_direct_ws=${if (RouteKind.DIRECT_WS in policy.enabledRoutes) 1 else 0}")
                add("@route_worker_ws=${if (RouteKind.WORKER_WS in policy.enabledRoutes) 1 else 0}")
                add("@route_cf_proxy_ws=${if (RouteKind.CF_PROXY_WS in policy.enabledRoutes) 1 else 0}")
                add("@route_tcp_fallback=${if (RouteKind.TCP_FALLBACK in policy.enabledRoutes) 1 else 0}")
                add("@preferred_route=${policy.preferredRoute?.prefValue.orEmpty()}")
                add("@route_fallback=${if (policy.allowFallback) 1 else 0}")
            }
            val manualDomains = CfManualDomainList.normalize(
                if (manualCfDomains.isNotEmpty()) manualCfDomains else listOf(cfDomain)
            )
            if (manualDomains.isNotEmpty()) {
                add("@cf_manual_domains=${manualDomains.joinToString("|")}")
                add("@cfproxy_domain=${manualDomains.first()}")
                add("@cf_use_manual_domain=1")
            }
            val cachedDomains = cachedCfDomains
                .mapNotNull(CfDomain::normalizeOrNull)
                .distinct()
            if (cachedDomains.isNotEmpty()) {
                add("@cf_cached_domains=${cachedDomains.joinToString("|")}")
            }
            val normalizedWorker = workerFailover?.primaryDomain?.takeIf { it.isNotBlank() }
                ?: WorkerDomain.normalize(workerDomain)
            val workerOn = if (routePolicy != null) {
                RouteKind.WORKER_WS in routePolicy.enabledRoutes
            } else {
                workerEnabled || normalizedWorker.isNotBlank() ||
                    effectiveMode == ConnectionMode.WorkerFirst ||
                    effectiveMode == ConnectionMode.WorkerOnly
            }
            add("@worker_enabled=${if (workerOn) 1 else 0}")
            if (normalizedWorker.isNotBlank()) {
                add("@worker_domain=$normalizedWorker")
            }
            workerFailover?.takeIf { it.enabled && it.candidates.isNotEmpty() }?.let { payload ->
                add("@worker_failover_enabled=1")
                if (payload.selectedWorkerId.isNotBlank()) {
                    add("@worker_selected_id=${payload.selectedWorkerId}")
                }
                add("@worker_failover_max_attempts=${payload.maxAttempts}")
                add("@worker_failover_candidates=${payload.encodeCandidatesToken()}")
                add("@worker_selection_strategy=${payload.selectionStrategy.prefValue}")
                add("@worker_selection_reason=${payload.selectionReason.wireValue}")
                add("@worker_candidate_count=${payload.candidateCount}")
                payload.roundRobinCursor?.takeIf { it.isNotBlank() }?.let { cursor ->
                    add("@worker_round_robin_cursor=$cursor")
                }
                if (payload.skippedBackoffCount > 0) {
                    add("@worker_failover_skipped_backoff=${payload.skippedBackoffCount}")
                }
            }
            add("@worker_destination_mode=${workerDestinationMode.prefValue}")
            add("@flowseal_media_fix_enabled=${if (flowsealMediaFixEnabled) 1 else 0}")
            add("@flowseal_media_fix_dc=${flowsealMediaFixDc.coerceAtLeast(1)}")
            val mediaFixIp = flowsealMediaFixIp.trim().ifBlank { WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP }
            add("@flowseal_media_fix_ip=$mediaFixIp")
            networkProfile?.let { profile ->
                add("@network_profile_id=${profile.id}")
                add("@network_profile_type=${profile.type.prefValue}")
                if (profile.label.isNotBlank()) {
                    add("@network_profile_label=${profile.label}")
                }
            }
            if (adaptiveRouteStats.isNotBlank()) {
                add("@adaptive_route_stats=$adaptiveRouteStats")
            }
            add("@auto_strategy=${(routePolicy?.autoStrategy ?: autoStrategy).prefValue}")
        }
        return tokens.joinToString(",")
    }

    fun dcIpForTest(dc: Int): String = defaultDcIps[dc] ?: defaultDcIps[2]!!

    /** Matches Go [effectiveWSHostDC]: DC203 probes use kws2.* hostnames. */
    fun effectiveWsHostDc(dc: Int): Int = when (dc) {
        203 -> 2
        else -> dc
    }
}
