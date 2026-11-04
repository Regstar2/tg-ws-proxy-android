package main

import (
    "reflect"
    "testing"

    "tg-ws-proxy/mtproxyfrontend"
)

func TestOrderedPolicyRestoresExactMTProtoSequence(t *testing.T) {
    _, settings, err := parseRuntimeConfig("@connection_mode=auto,@route_order=awg_warp|cf_worker_ws|direct_ws|tcp_fallback,@route_awg_warp=1")
    if err != nil { t.Fatal(err) }
    expected := []routeKind{routeAWGWarp, routeCFWorkerWS, routeDirectWS, routeTCPFallback}
    got := mtProtoRoutesForRequest(settings, mtproxyfrontend.OutboundRequest{DCID:2})
    if !reflect.DeepEqual(got, expected) {
        t.Fatalf("MTProto order=%v, want %v", got, expected)
    }
}

func TestStrictOnlyNoHiddenDirectOrTCPForTestDC(t *testing.T) {
    _, settings, err := parseRuntimeConfig("@connection_mode=direct_with_fallback,@route_order=cf_proxy_ws,@route_cf_proxy_ws=1")
    if err != nil { t.Fatal(err) }
    got := mtProtoRoutesForRequest(settings, mtproxyfrontend.OutboundRequest{DCID:2, IsTestDC:true})
    if !reflect.DeepEqual(got, []routeKind{routeCFProxyWS}) {
        t.Fatalf("test DC order=%v, want strictly CF only", got)
    }
}

func TestOrderedPolicyRejectsEmptyAndUnknownRoutes(t *testing.T) {
    for _, raw := range []string{"@route_order=", "@route_order=unrecognized"} {
        if _, _, err := parseRuntimeConfig(raw); err == nil {
            t.Errorf("missing error for %q", raw)
        }
    }
}

func TestOrderedPolicyDeduplicatesAndIgnoresLegacyScores(t *testing.T) {
    _, settings, err := parseRuntimeConfig("@route_order=cf_worker_ws|cf_worker_ws|awg_warp|direct_ws,@connection_mode=cf_only,@route_worker_ws=1")
    if err != nil { t.Fatal(err) }
    got := mtProtoRoutesForRequest(settings, mtproxyfrontend.OutboundRequest{DCID:4,IsMedia:true})
    expected := []routeKind{routeCFWorkerWS, routeAWGWarp, routeDirectWS}
    if !reflect.DeepEqual(got, expected) {
        t.Fatalf("ordered policy was changed: %v", got)
    }
    if isRouteAllowedByPolicy(settings, routeTCPFallback) {
        t.Fatal("disabled TCP route may not be used as fallback")
    }
}
