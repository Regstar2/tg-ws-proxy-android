package tgwsroute

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

const maxExplanationReasons = 5

type RouteExplanationCode string

const (
	ExplainDirectCooldown302   RouteExplanationCode = "direct_cooldown_302"
	ExplainDirectCooldown      RouteExplanationCode = "direct_cooldown"
	ExplainWorkerLastGood      RouteExplanationCode = "worker_last_good"
	ExplainCFCooldown429       RouteExplanationCode = "cf_cooldown_429"
	ExplainCFCooldown          RouteExplanationCode = "cf_cooldown"
	ExplainTCPRecentTimeouts   RouteExplanationCode = "tcp_recent_timeouts"
	ExplainWorkerPreferred     RouteExplanationCode = "worker_strategy_bonus"
	ExplainDirectPreferred     RouteExplanationCode = "direct_strategy_bonus"
	ExplainCFPreferred         RouteExplanationCode = "cf_strategy_bonus"
	ExplainRouteUnavailable    RouteExplanationCode = "route_unavailable"
)

type RouteSelectionExplanation struct {
	SelectedRoute RouteKind
	Strategy      AutoStrategy
	Codes         []RouteExplanationCode
	DebugReasons  []string
}

func BuildRouteSelectionExplanation(
	sel AdaptiveSelection,
	store *AdaptiveStore,
	settings RouteSettings,
	strategy AutoStrategy,
	dc int,
	media bool,
) RouteSelectionExplanation {
	ex := RouteSelectionExplanation{
		Strategy: strategy,
	}
	if len(sel.Routes) > 0 {
		ex.SelectedRoute = sel.Routes[0]
	}
	now := store.Now()

	if store.IsInCooldown(RouteDirectWS, dc, media, now) {
		st := store.statFor(RouteDirectWS, dc, media)
		if strings.Contains(strings.ToLower(st.LastFailureReason), "302") {
			ex.Codes = append(ex.Codes, ExplainDirectCooldown302)
		} else {
			ex.Codes = append(ex.Codes, ExplainDirectCooldown)
		}
	}

	if lg, ok := store.LastGood(dc, media, now); ok && lg.Route == RouteCFWorkerWS {
		ex.Codes = append(ex.Codes, ExplainWorkerLastGood)
	}

	if store.IsInCooldown(RouteCFProxyWS, dc, media, now) {
		st := store.statFor(RouteCFProxyWS, dc, media)
		if strings.Contains(st.LastFailureReason, "429") {
			ex.Codes = append(ex.Codes, ExplainCFCooldown429)
		} else {
			ex.Codes = append(ex.Codes, ExplainCFCooldown)
		}
	}

	stTCP := store.statFor(RouteTCPFallback, dc, media)
	if stTCP.ConsecutiveFailures >= 2 || strings.Contains(strings.ToLower(stTCP.LastFailureReason), "tcp") {
		ex.Codes = append(ex.Codes, ExplainTCPRecentTimeouts)
	}

	switch strategy {
	case StrategyWorkerPreferred:
		ex.Codes = append(ex.Codes, ExplainWorkerPreferred)
	case StrategyDirectPreferred:
		ex.Codes = append(ex.Codes, ExplainDirectPreferred)
	case StrategyCFPreferred:
		ex.Codes = append(ex.Codes, ExplainCFPreferred)
	}

	if !routeAvailable(RouteCFWorkerWS, settings) {
		ex.Codes = append(ex.Codes, ExplainRouteUnavailable)
	}

	ex.DebugReasons = append(ex.DebugReasons, sel.Reasons...)
	ex.Codes = dedupeCodes(ex.Codes)
	if len(ex.Codes) > maxExplanationReasons {
		ex.Codes = ex.Codes[:maxExplanationReasons]
	}
	return ex
}

func dedupeCodes(codes []RouteExplanationCode) []RouteExplanationCode {
	seen := make(map[RouteExplanationCode]bool)
	out := make([]RouteExplanationCode, 0, len(codes))
	for _, c := range codes {
		if seen[c] {
			continue
		}
		seen[c] = true
		out = append(out, c)
	}
	return out
}

func FormatCooldownUntil(untilMs int64) string {
	if untilMs <= 0 {
		return ""
	}
	return time.UnixMilli(untilMs).Format("15:04")
}

func RouteDisplayName(route RouteKind) string {
	switch route {
	case RouteDirectWS:
		return "direct_ws"
	case RouteCFWorkerWS:
		return "cf_worker_ws"
	case RouteCFProxyWS:
		return "cf_proxy_ws"
	case RouteTCPFallback:
		return "tcp_fallback"
	default:
		return string(route)
	}
}

func SummarizeRouteStatsForExport(stats []RouteStat, profileID string) string {
	byRoute := map[RouteKind]*RouteStat{}
	for i := range stats {
		st := stats[i]
		if profileID != "" && st.Key.ProfileID != profileID {
			continue
		}
		prev, ok := byRoute[st.Key.Route]
		if !ok {
			copy := st
			byRoute[st.Key.Route] = &copy
			continue
		}
		prev.SuccessCount += st.SuccessCount
		prev.FailureCount += st.FailureCount
		if st.LastFailureAt > prev.LastFailureAt {
			prev.LastFailureAt = st.LastFailureAt
			prev.LastFailureReason = st.LastFailureReason
		}
		if st.CooldownUntil > prev.CooldownUntil {
			prev.CooldownUntil = st.CooldownUntil
		}
	}
	routes := make([]RouteKind, 0, len(byRoute))
	for r := range byRoute {
		routes = append(routes, r)
	}
	sort.Slice(routes, func(i, j int) bool {
		return routes[i] < routes[j]
	})
	var lines []string
	for _, r := range routes {
		st := byRoute[r]
		line := fmt.Sprintf("%s: ok=%d fail=%d", RouteDisplayName(r), st.SuccessCount, st.FailureCount)
		if st.CooldownUntil > 0 {
			line += fmt.Sprintf(" cooldown_until=%s", FormatCooldownUntil(st.CooldownUntil))
		}
		if st.LastFailureReason != "" {
			line += fmt.Sprintf(" last_error=%s", st.LastFailureReason)
		}
		lines = append(lines, line)
	}
	return strings.Join(lines, "\n")
}
