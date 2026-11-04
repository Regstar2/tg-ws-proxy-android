package main

import (
    "context"
    "errors"
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

func TestOrderedConnectorAttemptsActualBackendsInDeclaredOrder(t *testing.T) {
    resetWorkerRouteCooldowns()
    t.Cleanup(resetWorkerRouteCooldowns)
    _, settings, err := parseRuntimeConfig("@route_order=cf_proxy_ws|cf_worker_ws|direct_ws,@worker_enabled=1,@worker_domain=worker.example")
    if err != nil { t.Fatal(err) }
    withRuntimeSettings(t, func(runtimeSettings) runtimeSettings { return settings })

    cf := &fakeOutboundRouteConnector{backend: mtProtoCFProxyBackend, err: errors.New("CF unavailable")}
    worker := &fakeOutboundRouteConnector{backend: mtProtoWorkerBackend, conn: nopConn{}}
    direct := &fakeOutboundRouteConnector{backend: mtProtoDirectWSBackend, conn: nopConn{}}
    tcp := &fakeOutboundRouteConnector{backend: mtProtoDirectBackend, conn: nopConn{}}
    connector := &mtProtoRouteConnector{cfProxy: cf, worker: worker, directWS: direct, tcp: tcp}

    conn, truth := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{DCID: 2})
    if conn == nil || truth.Err != nil { t.Fatalf("ordered connect failed: %+v", truth) }
    if cf.calls != 1 || worker.calls != 1 || direct.calls != 0 || tcp.calls != 0 {
        t.Fatalf("unexpected route attempts cf=%d worker=%d direct=%d tcp=%d",
            cf.calls, worker.calls, direct.calls, tcp.calls)
    }
    if truth.SelectedBackend != mtProtoCFProxyBackend ||
        truth.ActualBackend != mtProtoWorkerBackend || !truth.FallbackUsed {
        t.Fatalf("backend evidence mismatch: %+v", truth)
    }
}

func TestOrderedConnectorStrictOnlyRejectsHiddenTransport(t *testing.T) {
    _, settings, err := parseRuntimeConfig("@route_order=awg_warp,@route_awg_warp=1,@connection_mode=direct_with_fallback")
    if err != nil { t.Fatal(err) }
    withRuntimeSettings(t, func(runtimeSettings) runtimeSettings { return settings })

    awg := &fakeOutboundRouteConnector{backend: mtProtoAWGWarpBackend, err: errors.New("missing AWG config")}
    direct := &fakeOutboundRouteConnector{backend: mtProtoDirectWSBackend, conn: nopConn{}}
    cf := &fakeOutboundRouteConnector{backend: mtProtoCFProxyBackend, conn: nopConn{}}
    tcp := &fakeOutboundRouteConnector{backend: mtProtoDirectBackend, conn: nopConn{}}
    connector := &mtProtoRouteConnector{awg: awg, directWS: direct, cfProxy: cf, tcp: tcp}

    conn, truth := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{DCID: 2})
    if conn != nil || truth.Err == nil { t.Fatalf("AWG-only unexpectedly connected: %+v", truth) }
    if awg.calls != 1 || direct.calls != 0 || cf.calls != 0 || tcp.calls != 0 {
        t.Fatalf("strict policy bypass awg=%d direct=%d cf=%d tcp=%d",
            awg.calls, direct.calls, cf.calls, tcp.calls)
    }
    if truth.ActualBackend != "" || truth.FallbackUsed {
        t.Fatalf("failed strict route reported a fallback/success: %+v", truth)
    }
}

// A settings switch must affect future MTProto sessions without changing
// already-created upstream connections or enabling a disabled fallback.
func TestOrderedPolicySwitchesBackendOnNewSessions(t *testing.T) {
    _, cfOnly, err := parseRuntimeConfig("@route_order=cf_proxy_ws,@route_cf_proxy_ws=1")
    if err != nil { t.Fatal(err) }
    _, workerOnly, err := parseRuntimeConfig(
        "@route_order=worker_ws,@route_worker_ws=1,@worker_enabled=1,@worker_domain=worker.example")
    if err != nil { t.Fatal(err) }
    withRuntimeSettings(t, func(runtimeSettings) runtimeSettings { return cfOnly })
    cf := &fakeOutboundRouteConnector{backend: mtProtoCFProxyBackend, conn: nopConn{}}
    worker := &fakeOutboundRouteConnector{backend: mtProtoWorkerBackend, conn: nopConn{}}
    direct := &fakeOutboundRouteConnector{backend: mtProtoDirectWSBackend, conn: nopConn{}}
    tcp := &fakeOutboundRouteConnector{backend: mtProtoDirectBackend, conn: nopConn{}}
    connector := &mtProtoRouteConnector{cfProxy: cf, worker: worker, directWS: direct, tcp: tcp}
    request := mtproxyfrontend.OutboundRequest{DCID: 2}
    verify := func(want string) {
        t.Helper()
        conn, result := connector.Connect(context.Background(), request)
        if conn == nil || result.Err != nil || result.SelectedBackend != want ||
            result.ActualBackend != want || result.FallbackUsed {
            t.Fatalf("route switch failed want=%s, actual=%+v", want, result)
        }
        _ = conn.Close()
    }
    verify(mtProtoCFProxyBackend)
    setRuntimeSettings(workerOnly)
    verify(mtProtoWorkerBackend)
    setRuntimeSettings(cfOnly)
    verify(mtProtoCFProxyBackend)
    if cf.calls != 2 || worker.calls != 1 || direct.calls != 0 || tcp.calls != 0 {
        t.Fatalf("unexpected route attempts after switching cf=%d worker=%d direct=%d tcp=%d",
            cf.calls, worker.calls, direct.calls, tcp.calls)
    }
}
