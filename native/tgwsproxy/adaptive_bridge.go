package main

import (
	"fmt"
	"strings"
	"sync"

	"tg-ws-proxy/tgwsroute"
)

var (
	adaptiveStore   = tgwsroute.NewAdaptiveStore(func() int64 { return monoNowMillis() })
	adaptiveStoreMu sync.RWMutex
	lastAdaptiveSel tgwsroute.AdaptiveSelection
	lastAdaptiveMu  sync.RWMutex
)

func monoNowMillis() int64 {
	return int64(monoNow() * 1000)
}

func applyAdaptiveProfile(settings runtimeSettings) {
	adaptiveStoreMu.Lock()
	defer adaptiveStoreMu.Unlock()
	adaptiveStore.SetNowFn(monoNowMillis)
	profile := tgwsroute.NetworkProfile{
		ID:    settings.NetworkProfileID,
		Type:  tgwsroute.ParseNetworkProfileType(settings.NetworkProfileType),
		Label: settings.NetworkProfileLabel,
	}
	if profile.ID == "" {
		profile.ID = "unknown"
	}
	adaptiveStore.SetProfile(profile)
	if settings.AdaptiveRouteStats != "" {
		adaptiveStore.LoadEncodedStats(settings.AdaptiveRouteStats)
	}
	adaptiveStore.SetStrategy(tgwsroute.ParseAutoStrategy(settings.AutoStrategy))
}

func adaptiveRoutesForMode(mode connectionMode, settings runtimeSettings, skipDirect bool, dc int, isMedia bool) []routeKind {
	base := filterRoutesByPolicy(settings, tgwsroute.RoutesForMode(mode, toRouteSettings(settings), skipDirect), "adaptive_base")
	base = filterWorkerRouteCooldown(base, dc, isMedia)
	if mode != modeAuto && mode != modeDirectWithFallback {
		return base
	}
	adaptiveStoreMu.RLock()
	store := adaptiveStore
	adaptiveStoreMu.RUnlock()

	strategy := tgwsroute.ParseAutoStrategy(settings.AutoStrategy)
	sel := tgwsroute.AdaptiveOrderRoutes(base, store, toRouteSettings(settings), strategy, dc, isMedia, skipDirect)
	// Absolute policy filter: even if adaptive logic returns something stale, drop it.
	filtered := filterRoutesByPolicy(settings, sel.Routes, "adaptive_selection")
	if len(filtered) == 0 {
		// No allowed candidates scored: choose safe fallback from allowed routes only.
		filtered = chooseFallbackAllowed(settings, base)
	}
	sel.Routes = filtered
	lastAdaptiveMu.Lock()
	lastAdaptiveSel = sel
	lastAdaptiveMu.Unlock()

	netLabel := string(store.Profile.Type)
	if store.Profile.Label != "" {
		netLabel = store.Profile.Label
	}
	logInfo.Printf("Auto route scoring network=%s dc=%d media=%t", netLabel, dc, isMedia)
	for route, score := range sel.Scores {
		if !isRouteAllowedByPolicy(settings, route) {
			continue
		}
		logDebug.Printf("Auto candidate %s score=%.1f", route, score)
	}
	if len(sel.Routes) > 0 {
		selected := sel.Routes[0]
		noteRouteSelected(selected)
		logInfo.Printf("Route selected network=%s strategy=%s routeKind=%s transport=%s policyGeneration=%d allowed=%s preferred=%s reason=%s",
			netLabel,
			settings.Mode,
			selected,
			transportForRoute(selected),
			settings.PolicyGen,
			strings.Join(routeKindStrings(allowedRoutesList(settings)), "|"),
			settings.Preferred,
			tgwsroute.FormatAdaptiveSelectionReason(sel, dc, isMedia, netLabel),
		)
	}
	return sel.Routes
}

func chooseFallbackAllowed(settings runtimeSettings, base []routeKind) []routeKind {
	// Fallback rules (policy-only):
	// 1) preferred (if allowed)
	// 2) cf_proxy_ws
	// 3) direct_ws
	// 4) tcp_fallback
	// 5) cf_worker_ws only if explicitly allowed
	candidates := allowedRoutesList(settings)
	if len(candidates) == 0 {
		logWarn.Printf("No allowed routes in policy (generation=%d)", settings.PolicyGen)
		return nil
	}
	preferred := settings.Preferred
	if preferred != "" && isRouteAllowedByPolicy(settings, preferred) {
		return []routeKind{preferred}
	}
	for _, r := range []routeKind{routeCFProxyWS, routeDirectWS, routeTCPFallback, routeCFWorkerWS} {
		if isRouteAllowedByPolicy(settings, r) {
			return []routeKind{r}
		}
	}
	// As last resort, return the first allowed.
	return []routeKind{candidates[0]}
}

func recordAdaptiveSessionSuccess(route routeKind, dc int, isMedia bool, latencyMs int64, closeReason string, settings runtimeSettings) {
	if closeReason == "" {
		closeReason = "session_end"
	}
	recordAdaptiveSuccessIfNotCancelled(route, dc, isMedia, latencyMs, closeReason, settings.PolicyGen)
}

func recordAdaptiveSuccess(route routeKind, dc int, isMedia bool, latencyMs int64) {
	adaptiveStoreMu.Lock()
	defer adaptiveStoreMu.Unlock()
	adaptiveStore.RecordSuccess(route, dc, isMedia, latencyMs)
	updateProxyMetrics(route, latencyMs, "")
	logInfo.Printf("Auto updated stats route=%s success latency_ms=%d dc=%d media=%t", route, latencyMs, dc, isMedia)
	logInfo.Printf("Auto last-good updated route=%s dc=%d media=%t", route, dc, isMedia)
}

func recordAdaptiveSuccessIfNotCancelled(route routeKind, dc int, isMedia bool, latencyMs int64, closeReason string, sessionGen uint64) {
	settings := getRuntimeSettings()
	if sessionGen != settings.PolicyGen {
		logInfo.Printf("Skip current stats update for stale session route=%s sessionGeneration=%d currentGeneration=%d", route, sessionGen, settings.PolicyGen)
		return
	}
	kind := tgwsroute.ClassifyFailureReason(closeReason)
	if tgwsroute.IsNeutralFailure(kind) {
		logInfo.Printf("Skip last-good update: close reason=%s route=%s", closeReason, route)
		return
	}
	if settings.PolicyPresent && !isRouteAllowedByPolicy(settings, route) {
		logInfo.Printf("Skip last-good update: route disabled by current policy route=%s allowed=%s",
			route, strings.Join(routeKindStrings(allowedRoutesList(settings)), "|"))
		return
	}
	recordAdaptiveSuccess(route, dc, isMedia, latencyMs)
}

func recordAdaptiveFailure(route routeKind, dc int, isMedia bool, reason string, latencyMs int64) {
	settings := getRuntimeSettings()
	kind := tgwsroute.ClassifyFailureReason(reason)
	adaptiveStoreMu.Lock()
	adaptiveStore.RecordFailureClassified(route, dc, isMedia, kind, reason, latencyMs)
	adaptiveStoreMu.Unlock()
	if settings.PolicyPresent && !isRouteAllowedByPolicy(settings, route) {
		logInfo.Printf("Skip current stats update route=%s reason=disabled_by_policy generation=%d", route, settings.PolicyGen)
		return
	}
	if tgwsroute.IsNeutralFailure(kind) {
		logInfo.Printf("Skip current route display update routeKind=%s reason=%s", route, kind)
		return
	}
	updateProxyMetrics(route, latencyMs, string(kind))
	logInfo.Printf("Auto updated stats route=%s failure kind=%s dc=%d media=%t", route, kind, dc, isMedia)
}

func shouldSkipDirectWSAdaptive(dcKey [2]int, mode connectionMode) bool {
	if shouldSkipDirectWS(dcKey, mode) {
		return true
	}
	if mode != modeAuto && mode != modeDirectWithFallback {
		return false
	}
	now := monoNowMillis()
	adaptiveStoreMu.RLock()
	defer adaptiveStoreMu.RUnlock()
	if adaptiveStore.IsInCooldown(routeDirectWS, dcKey[0], dcKey[1] != 0, now) {
		logInfo.Printf("Auto skipped route=direct_ws reason=cooldown dc=%d media=%t", dcKey[0], dcKey[1] != 0)
		return true
	}
	return false
}

func exportAdaptiveRouteStats() string {
	adaptiveStoreMu.RLock()
	defer adaptiveStoreMu.RUnlock()
	stats, lastGoods := adaptiveStore.Snapshot()
	return tgwsroute.EncodeRouteStats(stats, lastGoods)
}

func resetAdaptiveRouteStats(all bool, profileID string) {
	adaptiveStoreMu.Lock()
	defer adaptiveStoreMu.Unlock()
	if all {
		adaptiveStore.ResetAll()
		logInfo.Printf("Auto route stats reset all")
		return
	}
	if profileID != "" {
		prev := adaptiveStore.Profile.ID
		adaptiveStore.Profile.ID = profileID
		adaptiveStore.ResetCurrentProfile()
		adaptiveStore.Profile.ID = prev
	} else {
		adaptiveStore.ResetCurrentProfile()
	}
	logInfo.Printf("Auto route stats reset network=%s", profileID)
}

func exportAdaptiveDiagnosticsSection() string {
	adaptiveStoreMu.RLock()
	defer adaptiveStoreMu.RUnlock()
	stats, lastGoods := adaptiveStore.Snapshot()
	ex := adaptiveStore.LastExplanation
	var b strings.Builder
	b.WriteString("Adaptive Routing Diagnostics\n")
	b.WriteString(fmt.Sprintf("strategy=%s\n", adaptiveStore.Strategy))
	b.WriteString(fmt.Sprintf("network_type=%s\n", adaptiveStore.Profile.Type))
	b.WriteString(fmt.Sprintf("network_profile_id=%s\n", adaptiveStore.Profile.ID))
	if ex.SelectedRoute != "" {
		b.WriteString(fmt.Sprintf("last_selected_route=%s\n", ex.SelectedRoute))
	}
	for _, code := range ex.Codes {
		b.WriteString(fmt.Sprintf("reason_code=%s\n", code))
	}
	b.WriteString("route_stats:\n")
	b.WriteString(tgwsroute.SummarizeRouteStatsForExport(stats, adaptiveStore.Profile.ID))
	if len(lastGoods) > 0 {
		b.WriteString("\nlast_good_routes:\n")
		for _, lg := range lastGoods {
			b.WriteString(fmt.Sprintf("dc=%d media=%t route=%s\n", lg.DC, lg.Media, lg.Route))
		}
	}
	return b.String()
}

func lastAdaptiveSelectionSummary() string {
	lastAdaptiveMu.RLock()
	defer lastAdaptiveMu.RUnlock()
	if len(lastAdaptiveSel.Routes) == 0 {
		return ""
	}
	return fmt.Sprintf("route=%s; %s", lastAdaptiveSel.Routes[0], strings.Join(lastAdaptiveSel.Reasons, ", "))
}
