package main

import "testing"

func TestMtProtoAttemptTruthCFProxyDirectWSDirectTCP(t *testing.T) {
	assertMtProtoAttemptTruthChain(t, []routeKind{
		routeCFProxyWS,
		routeDirectWS,
		routeTCPFallback,
	}, []mtProtoAttemptTruth{
		{SelectedBackend: mtProtoCFProxyBackend, AttemptBackend: mtProtoCFProxyBackend, FallbackUsed: false},
		{SelectedBackend: mtProtoCFProxyBackend, AttemptBackend: mtProtoDirectWSBackend, FallbackUsed: true},
		{SelectedBackend: mtProtoCFProxyBackend, AttemptBackend: mtProtoDirectBackend, FallbackUsed: true},
	})
}

func TestMtProtoAttemptTruthWorkerCFProxyDirect(t *testing.T) {
	assertMtProtoAttemptTruthChain(t, []routeKind{
		routeCFWorkerWS,
		routeCFProxyWS,
		routeTCPFallback,
	}, []mtProtoAttemptTruth{
		{SelectedBackend: mtProtoWorkerBackend, AttemptBackend: mtProtoWorkerBackend, FallbackUsed: false},
		{SelectedBackend: mtProtoWorkerBackend, AttemptBackend: mtProtoCFProxyBackend, FallbackUsed: true},
		{SelectedBackend: mtProtoWorkerBackend, AttemptBackend: mtProtoDirectBackend, FallbackUsed: true},
	})
}

func assertMtProtoAttemptTruthChain(
	t *testing.T,
	routes []routeKind,
	want []mtProtoAttemptTruth,
) {
	t.Helper()
	if len(routes) != len(want) {
		t.Fatalf("routes=%d want=%d", len(routes), len(want))
	}
	for i, route := range routes {
		got := mtProtoAttemptTruthForRoutes(routes, route)
		if got != want[i] {
			t.Fatalf("attempt[%d] route=%s got=%+v want=%+v", i, route, got, want[i])
		}
	}
}
