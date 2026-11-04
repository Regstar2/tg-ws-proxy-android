package main

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestIssue135RuntimeOrderOverridesLegacyFlags(t *testing.T) {
	_, settings, err := parseRuntimeConfig(
		"@connection_mode=direct_only,@route_fallback=0,@route_order=cf_proxy_ws|awg_warp|cf_worker_ws|direct_ws|tcp_fallback",
	)
	if err != nil {
		t.Fatal(err)
	}
	want := []routeKind{routeCFProxyWS, routeAWGWarp, routeCFWorkerWS, routeDirectWS, routeTCPFallback}
	if got := mtProtoRoutesForRequest(settings, mtproxyfrontend.OutboundRequest{DCID: 2}); !reflect.DeepEqual(got, want) {
		t.Fatalf("ordered routes=%v want=%v", got, want)
	}
	if !settings.AllowFallback || !settings.AllowAWG || settings.Preferred != routeCFProxyWS {
		t.Fatalf("explicit order must override legacy flags: %+v", settings)
	}
	if got := mtProtoRoutesForRequest(settings, mtproxyfrontend.OutboundRequest{DCID: 2, IsTestDC: true}); !reflect.DeepEqual(got, want) {
		t.Fatalf("test DC bypassed strict policy: %v", got)
	}
}

func TestIssue135RejectEmptyExplicitOrder(t *testing.T) {
	if _, _, err := parseRuntimeConfig("@connection_mode=auto,@route_order=bogus"); err == nil {
		t.Fatal("unknown-only order must fail closed")
	}
}

func TestIssue135OrderedMtProtoBackendFallback(t *testing.T) {
	order := []routeKind{routeCFProxyWS, routeAWGWarp, routeCFWorkerWS, routeDirectWS, routeTCPFallback}
	expected := []string{
		mtProtoCFProxyBackend,
		mtProtoAWGWarpBackend,
		mtProtoWorkerBackend,
		mtProtoDirectWSBackend,
		mtProtoDirectBackend,
	}
	for failCount := 0; failCount < len(order); failCount++ {
		t.Run(expected[failCount], func(t *testing.T) {
			withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
				settings.Mode = modeAuto
				settings.PolicyPresent = true
				settings.RouteOrderPresent = true
				settings.RouteOrder = append([]routeKind(nil), order...)
				settings.AllowCFProxy = true
				settings.AllowAWG = true
				settings.AllowWorker = true
				settings.AllowDirect = true
				settings.AllowTCP = true
				settings.AllowFallback = true
				return settings
			})
			routes := make([]*fakeOutboundRouteConnector, 5)
			for i, backend := range expected {
				routes[i] = &fakeOutboundRouteConnector{backend: backend, conn: nopConn{}}
				if i < failCount {
					routes[i].err = errors.New("deliberate failure")
				}
			}
			c := &mtProtoRouteConnector{
				cfProxy: routes[0],
				awg: routes[1],
				worker: routes[2],
				directWS: routes[3],
				tcp: routes[4],
			}
			conn, result := c.Connect(context.Background(), mtproxyfrontend.OutboundRequest{DCID: 2})
			if conn == nil || result.Err != nil {
				t.Fatalf("route should succeed: %+v", result)
			}
			_ = conn.Close()
			if result.SelectedBackend != expected[0] || result.ActualBackend != expected[failCount] || result.FallbackUsed != (failCount > 0) {
				t.Fatalf("backend evidence incorrect: %+v", result)
			}
			for i, route := range routes {
				wantCalls := 0
				if i <= failCount { wantCalls = 1 }
				if route.calls != wantCalls {
					t.Fatalf("attempts[%d]=%d want=%d", i, route.calls, wantCalls)
				}
			}
		})
	}
}

func TestIssue135StrictOnlyDoesNotTryDisabledRoutes(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.PolicyPresent = true
		settings.RouteOrderPresent = true
		settings.RouteOrder = []routeKind{routeAWGWarp}
		settings.AllowAWG = true
		settings.AllowDirect = false
		settings.AllowCFProxy = false
		settings.AllowWorker = false
		settings.AllowTCP = false
		settings.AllowFallback = false
		return settings
	})
	awg := &fakeOutboundRouteConnector{backend: mtProtoAWGWarpBackend, err: errors.New("no AWG config")}
	direct := &fakeOutboundRouteConnector{backend: mtProtoDirectBackend, conn: nopConn{}}
	worker := &fakeOutboundRouteConnector{backend: mtProtoWorkerBackend, conn: nopConn{}}
	c := &mtProtoRouteConnector{awg: awg, tcp: direct, worker: worker}
	conn, result := c.Connect(context.Background(), mtproxyfrontend.OutboundRequest{DCID: 2})
	if conn != nil || result.Err == nil {
		t.Fatalf("strict-only must fail without a hidden fallback: %+v", result)
	}
	if awg.calls != 1 || direct.calls != 0 || worker.calls != 0 {
		t.Fatalf("attempt counts awg=%d direct=%d worker=%d", awg.calls, direct.calls, worker.calls)
	}
}

func TestIssue135NewSessionsUseUpdatedOrder(t *testing.T) {
	_, original, err := parseRuntimeConfig("@route_order=cf_proxy_ws|direct_ws")
	if err != nil { t.Fatal(err) }
	_, updated, err := parseRuntimeConfig("@route_order=direct_ws|cf_proxy_ws")
	if err != nil { t.Fatal(err) }
	request := mtproxyfrontend.OutboundRequest{DCID: 2}
	oldRoutes := mtProtoRoutesForRequest(original, request)
	newRoutes := mtProtoRoutesForRequest(updated, request)
	if oldRoutes[0] != routeCFProxyWS || newRoutes[0] != routeDirectWS {
		t.Fatalf("new sessions must select new top priority old=%v new=%v", oldRoutes, newRoutes)
	}
}
