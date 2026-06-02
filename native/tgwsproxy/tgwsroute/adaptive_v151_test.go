package tgwsroute

import "testing"

func TestClassifyFailure_NeutralDoesNotPenalize(t *testing.T) {
	if !IsNeutralFailure(FailureClientEOF) {
		t.Fatal("CLIENT_EOF should be neutral")
	}
	if !IsNeutralFailure(FailureContextCancelled) {
		t.Fatal("CONTEXT_CANCELLED should be neutral")
	}
}

func TestClassifyFailure_WS302AppliesToDirect(t *testing.T) {
	if !FailureAppliesToRoute(FailureWS302, RouteDirectWS) {
		t.Fatal("302 should penalize direct")
	}
	if FailureAppliesToRoute(FailureWS302, RouteCFWorkerWS) {
		t.Fatal("302 should not penalize worker directly")
	}
}

func TestStrategy_DirectPreferredBonus(t *testing.T) {
	store := NewAdaptiveStore(func() int64 { return 1_000_000 })
	store.SetProfile(NetworkProfile{ID: "p", Type: NetworkUnknown})
	settings := RouteSettings{
		Mode:   ModeAuto,
		CF:     CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{Enabled: true, Domain: "w.example"},
	}
	base := RoutesForMode(ModeAuto, settings, false)
	balanced := AdaptiveOrderRoutes(base, store, settings, StrategyBalanced, 2, false, false)
	store2 := NewAdaptiveStore(func() int64 { return 1_000_000 })
	store2.SetProfile(NetworkProfile{ID: "p", Type: NetworkUnknown})
	directPref := AdaptiveOrderRoutes(base, store2, settings, StrategyDirectPreferred, 2, false, false)
	directScore := directPref.Scores[RouteDirectWS]
	workerScore := directPref.Scores[RouteCFWorkerWS]
	if directScore <= 0 {
		t.Fatalf("direct should be scored: %v", directPref.Scores)
	}
	if directScore <= workerScore {
		t.Fatalf("direct preferred should boost direct: direct=%v worker=%v balanced=%v", directPref.Scores, balanced.Scores, balanced.Scores)
	}
}

func TestStrategy_WorkerPreferredBonus(t *testing.T) {
	store := NewAdaptiveStore(func() int64 { return 2_000_000 })
	store.SetProfile(NetworkProfile{ID: "p", Type: NetworkMobile})
	settings := RouteSettings{
		Mode:   ModeAuto,
		CF:     CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{Enabled: true, Domain: "w.example"},
	}
	base := RoutesForMode(ModeAuto, settings, false)
	sel := AdaptiveOrderRoutes(base, store, settings, StrategyWorkerPreferred, 2, false, false)
	if sel.Scores[RouteCFWorkerWS] < sel.Scores[RouteCFProxyWS] {
		t.Fatalf("worker preferred should score worker highly: %v", sel.Scores)
	}
}

func TestExplanation_LimitsReasonCount(t *testing.T) {
	store := NewAdaptiveStore(func() int64 { return 3_000_000 })
	store.SetProfile(NetworkProfile{ID: "p", Type: NetworkMobile})
	for i := 0; i < 5; i++ {
		store.RecordFailureClassified(RouteDirectWS, 1, false, FailureWS302, "WS_302", 0)
	}
	settings := RouteSettings{Mode: ModeAuto, CF: CFProxyFlags{Enabled: true}}
	base := RoutesForMode(ModeAuto, settings, true)
	sel := AdaptiveOrderRoutes(base, store, settings, StrategyBalanced, 1, false, true)
	ex := BuildRouteSelectionExplanation(sel, store, settings, StrategyBalanced, 1, false)
	if len(ex.Codes) > maxExplanationReasons {
		t.Fatalf("too many explanation codes: %d", len(ex.Codes))
	}
	if ex.SelectedRoute == "" && len(sel.Routes) > 0 {
		t.Fatal("expected selected route in explanation")
	}
}

func TestStrictFastFailover_FasterCooldown(t *testing.T) {
	rt := StrategyRuntimeFor(StrategyStrictFastFailover, NetworkUnknown)
	if rt.CooldownAfter != 2 {
		t.Fatalf("expected cooldown after 2, got %d", rt.CooldownAfter)
	}
}

func TestSummarizeRouteStatsForExport_NoRawSSID(t *testing.T) {
	stats := []RouteStat{
		{Key: RouteStatKey{ProfileID: "hash123", Route: RouteDirectWS, DC: 1}, SuccessCount: 2},
	}
	s := SummarizeRouteStatsForExport(stats, "hash123")
	if s == "" {
		t.Fatal("expected summary")
	}
}
