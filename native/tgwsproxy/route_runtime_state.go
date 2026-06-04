package main

import (
	"fmt"
	"strings"
	"sync/atomic"
	"time"
)

// Runtime route truth (v1.8.1): configured vs selected vs active, exported via GetProxyStatus.
var (
	rtConfiguredMode      atomic.Value // string
	rtSelectedRoute       atomic.Value // string
	rtActiveRoute         atomic.Value // string
	rtLastSuccessRoute    atomic.Value // string
	rtLastFailedRoute     atomic.Value // string
	rtFallbackReason      atomic.Value // string
	rtCurrentWorkerDomain atomic.Value // string
	rtNetworkType         atomic.Value // string
	rtLastUpdatedAtMs     atomic.Int64
)

func initRouteRuntimeState() {
	empty := ""
	rtConfiguredMode.Store(empty)
	rtSelectedRoute.Store(empty)
	rtActiveRoute.Store(empty)
	rtLastSuccessRoute.Store(empty)
	rtLastFailedRoute.Store(empty)
	rtFallbackReason.Store(empty)
	rtCurrentWorkerDomain.Store(empty)
	rtNetworkType.Store(empty)
	rtLastUpdatedAtMs.Store(0)
}

func syncRouteRuntimeFromSettings() {
	settings := getRuntimeSettings()
	rtConfiguredMode.Store(string(settings.Mode))
	rtCurrentWorkerDomain.Store(settings.Worker.Domain)
	net := settings.NetworkProfileType
	if net == "" {
		net = settings.NetworkProfileID
	}
	rtNetworkType.Store(net)
}

func touchRouteRuntimeUpdated() {
	rtLastUpdatedAtMs.Store(time.Now().UnixMilli())
}

func routeRuntimeString(v atomic.Value) string {
	if x := v.Load(); x != nil {
		if s, ok := x.(string); ok {
			return s
		}
	}
	return ""
}

func resetRouteRuntimeState() {
	initRouteRuntimeState()
	logInfo.Printf("Route runtime state reset")
}

func noteRouteSelected(route routeKind) {
	if route == "" {
		return
	}
	k := string(route)
	rtSelectedRoute.Store(k)
	touchRouteRuntimeUpdated()
	logInfo.Printf("Route selected: %s", k)
}

func noteRouteConnectStarted(route routeKind) {
	if route == "" {
		return
	}
	touchRouteRuntimeUpdated()
	logInfo.Printf("Route connect started: %s", route)
}

func noteRouteConnectSucceeded(route routeKind) {
	if route == "" {
		return
	}
	k := string(route)
	rtLastSuccessRoute.Store(k)
	rtFallbackReason.Store("")
	touchRouteRuntimeUpdated()
	logInfo.Printf("Route success: %s", k)
}

func noteRouteConnectFailed(route routeKind, reason string) {
	if route == "" {
		return
	}
	k := string(route)
	rtLastFailedRoute.Store(k)
	touchRouteRuntimeUpdated()
	logInfo.Printf("Route failed: %s, reason=%s", k, reason)
}

func noteFallbackActivated(toRoute routeKind, reason string) {
	reason = strings.TrimSpace(reason)
	if reason == "" {
		reason = "unknown"
	}
	rtFallbackReason.Store(reason)
	if toRoute != "" {
		rtSelectedRoute.Store(string(toRoute))
	}
	touchRouteRuntimeUpdated()
	logInfo.Printf("Fallback activated: %s, reason=%s", toRoute, reason)
}

func noteActiveRouteRuntime(route routeKind) {
	if route == "" {
		return
	}
	k := string(route)
	rtActiveRoute.Store(k)
	touchRouteRuntimeUpdated()
	logInfo.Printf("Active route changed: %s", k)
}

func noteConnectionClosed(route routeKind) {
	if route == "" {
		return
	}
	touchRouteRuntimeUpdated()
	logInfo.Printf("Connection closed: %s", route)
}

func appendRouteRuntimeStatusFields(parts []string) []string {
	syncRouteRuntimeFromSettings()
	active := routeRuntimeString(rtActiveRoute)
	if active == "" {
		active = routeRuntimeString(lastActiveRouteKind)
	}
	return append(parts,
		"configured_mode="+escapeStatusField(routeRuntimeString(rtConfiguredMode)),
		"selected_route="+escapeStatusField(routeRuntimeString(rtSelectedRoute)),
		"active_route_kind="+escapeStatusField(active),
		"last_success_route="+escapeStatusField(routeRuntimeString(rtLastSuccessRoute)),
		"last_failed_route="+escapeStatusField(routeRuntimeString(rtLastFailedRoute)),
		"fallback_reason="+escapeStatusField(routeRuntimeString(rtFallbackReason)),
		"current_worker_domain="+escapeStatusField(routeRuntimeString(rtCurrentWorkerDomain)),
		"network_type="+escapeStatusField(routeRuntimeString(rtNetworkType)),
		fmt.Sprintf("route_state_updated_at=%d", rtLastUpdatedAtMs.Load()),
	)
}
