package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	"strings"
	"sync/atomic"

	"tg-ws-proxy/tgwsroute"
)

var (
	lastMetricsRoute      atomic.Value // string — legacy alias of active route kind
	lastActiveRouteKind   atomic.Value // string
	lastMetricsTransport  atomic.Value // string: websocket | tcp
	lastMetricsLatency    atomic.Int64
	lastMetricsError      atomic.Value // string
)

func transportForRoute(route routeKind) string {
	if route == routeTCPFallback {
		return "tcp"
	}
	return "websocket"
}

func resetProxyRouteDisplayState() {
	lastActiveRouteKind.Store("")
	lastMetricsRoute.Store("")
	lastMetricsTransport.Store("")
	lastMetricsError.Store("")
}

func noteActiveRoute(route routeKind) {
	settings := getRuntimeSettings()
	if settings.PolicyPresent && !isRouteAllowedByPolicy(settings, route) {
		logInfo.Printf("Skip active route update routeKind=%s reason=disabled_by_policy generation=%d",
			route, settings.PolicyGen)
		return
	}
	kind := string(route)
	transport := transportForRoute(route)
	lastActiveRouteKind.Store(kind)
	lastMetricsRoute.Store(kind)
	lastMetricsTransport.Store(transport)
	logInfo.Printf("UI route state activeRouteKind=%s transport=%s strategy=%s generation=%d stale=false",
		kind, transport, settings.Mode, settings.PolicyGen)
}

func updateProxyMetrics(route routeKind, latencyMs int64, errReason string) {
	settings := getRuntimeSettings()
	if errReason != "" {
		if settings.PolicyPresent && !isRouteAllowedByPolicy(settings, route) {
			logInfo.Printf("Skip current route display update routeKind=%s reason=disabled_by_policy generation=%d",
				route, settings.PolicyGen)
			return
		}
		kind := tgwsroute.ClassifyFailureReason(errReason)
		if tgwsroute.IsNeutralFailure(kind) {
			logInfo.Printf("Skip current route display update routeKind=%s reason=%s", route, kind)
			return
		}
		if latencyMs > 0 {
			lastMetricsLatency.Store(latencyMs)
		}
		lastMetricsError.Store(errReason)
		return
	}
	if latencyMs > 0 {
		lastMetricsLatency.Store(latencyMs)
	}
	lastMetricsError.Store("")
	noteActiveRoute(route)
}

func exportProxyStatus() string {
	settings := getRuntimeSettings()
	running := 0
	globalMu.Lock()
	if globalCancel != nil {
		running = 1
	}
	globalMu.Unlock()

	activeRoute := ""
	if v := lastActiveRouteKind.Load(); v != nil {
		activeRoute = v.(string)
	}
	if activeRoute == "" {
		if v := lastMetricsRoute.Load(); v != nil {
			activeRoute = v.(string)
		}
	}

	transport := ""
	if v := lastMetricsTransport.Load(); v != nil {
		transport = v.(string)
	}
	if transport == "" && activeRoute != "" {
		transport = transportForRoute(routeKind(activeRoute))
	}

	lastErr := ""
	if v := lastMetricsError.Load(); v != nil {
		lastErr = v.(string)
	}

	allowed := ""
	preferred := ""
	policyGen := uint64(0)
	if settings.PolicyPresent {
		allowed = strings.Join(routeKindStrings(allowedRoutesList(settings)), "|")
		preferred = string(settings.Preferred)
		policyGen = settings.PolicyGen
	}

	active := stats.connectionsTotal.Load()
	if active < 0 {
		active = 0
	}

	return fmt.Sprintf(
		"running=%d;mode=%s;route=%s;active_route_kind=%s;transport_type=%s;policy_generation=%d;allowed_routes=%s;preferred_route=%s;active=%d;bytes_up=%d;bytes_down=%d;latency_ms=%d;last_error=%s;worker_pool_hits=%d;worker_pool_misses=%d;worker_pool_idle=%d;worker_pool_refill_errors=%d;worker_pool_err=%d;cf_pool_hits=%d;cf_pool_misses=%d;cf_pool_idle=%d;cf_pool_refill_errors=%d;cf_pool_err=%d",
		running,
		settings.Mode,
		activeRoute,
		activeRoute,
		transport,
		policyGen,
		escapeStatusField(allowed),
		escapeStatusField(preferred),
		active,
		stats.bytesUp.Load(),
		stats.bytesDown.Load(),
		lastMetricsLatency.Load(),
		escapeStatusField(lastErr),
		stats.workerPoolHits.Load(),
		stats.workerPoolMisses.Load(),
		workerPool.IdleCount(),
		stats.workerPoolRefillErrors.Load(),
		stats.workerPoolRefillErrors.Load(),
		stats.cfPoolHits.Load(),
		stats.cfPoolMisses.Load(),
		0,
		stats.cfPoolRefillErrors.Load(),
		stats.cfPoolRefillErrors.Load(),
	)
}

func escapeStatusField(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, ";", ","), "\n", " ")
}

//export GetProxyStatus
func GetProxyStatus() *C.char {
	return C.CString(exportProxyStatus())
}

func init() {
	lastMetricsRoute.Store("")
	lastActiveRouteKind.Store("")
	lastMetricsTransport.Store("")
	lastMetricsError.Store("")
}
