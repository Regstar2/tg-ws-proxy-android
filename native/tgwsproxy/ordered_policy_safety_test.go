package main

import "testing"

func TestOrderedRouteRejectsUnconfiguredSingleCandidate(t *testing.T) {
    for _, raw := range []string{
        "@route_order=awg_warp,@route_awg_warp=1",
        "@route_order=worker_ws,@route_worker_ws=1",
        "@route_order=awg_warp|worker_ws,@route_awg_warp=1,@route_worker_ws=1",
    } {
        _, settings, err := parseRuntimeConfig(raw)
        if err != nil { t.Fatal(err) }
        if err := validateMtProtoRequestedRoutes(settings); err == nil {
            t.Errorf("accepted policy without a configured outbound route: %q", raw)
        }
    }
}

func TestOrderedRouteAllowsOnlyExplicitConfiguredCandidates(t *testing.T) {
    for _, raw := range []string{
        "@route_order=cf_proxy_ws,@route_cf_proxy_ws=1",
        "@route_order=direct_ws|tcp_fallback,@route_direct_ws=1,@route_tcp_fallback=1",
        "@route_order=awg_warp|cf_proxy_ws,@route_awg_warp=1,@route_cf_proxy_ws=1",
        "@route_order=awg_warp,@route_awg_warp=1,@awg_warp_config_path=/private/test.conf",
        "@route_order=worker_ws,@route_worker_ws=1,@worker_domain=worker.example",
    } {
        _, settings, err := parseRuntimeConfig(raw)
        if err != nil { t.Fatal(err) }
        if err := validateMtProtoRequestedRoutes(settings); err != nil {
            t.Errorf("rejected configured route %q: %v", raw, err)
        }
    }
}
