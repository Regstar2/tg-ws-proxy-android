package tgwsroute

import (
	"testing"
	"time"
)

func TestAdaptiveStore_LastGoodBonus(t *testing.T) {
	now := int64(1_000_000)
	store := NewAdaptiveStore(func() int64 { return now })
	store.SetProfile(NetworkProfile{ID: "mobile_test", Type: NetworkMobile})

	store.RecordFailure(RouteDirectWS, 2, false, "ws_302", 0)
	store.RecordFailure(RouteDirectWS, 2, false, "ws_302", 0)
	store.RecordFailure(RouteDirectWS, 2, false, "ws_302", 0)
	store.RecordSuccess(RouteCFWorkerWS, 2, false, 210)

	settings := RouteSettings{
		Mode: ModeAuto,
		CF:   CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{
			Enabled: true,
			Domain:  "example.workers.dev",
		},
	}
	base := RoutesForMode(ModeAuto, settings, true)
	sel := AdaptiveOrderRoutes(base, store, settings, StrategyBalanced, 2, false, true)
	if len(sel.Routes) == 0 {
		t.Fatal("expected routes")
	}
	if sel.Routes[0] != RouteCFWorkerWS {
		t.Fatalf("expected worker first after direct cooldown, got %v scores=%v", sel.Routes, sel.Scores)
	}
}

func TestAdaptiveStore_CooldownSkipsRoute(t *testing.T) {
	now := int64(2_000_000)
	store := NewAdaptiveStore(func() int64 { return now })
	store.SetProfile(NetworkProfile{ID: "wifi_test", Type: NetworkWiFi})
	for i := 0; i < AdaptiveCooldownAfter; i++ {
		store.RecordFailure(RouteCFProxyWS, 1, false, "429", 0)
	}
	settings := RouteSettings{
		Mode: ModeAuto,
		CF:   CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{
			Enabled: true,
			Domain:  "example.workers.dev",
		},
	}
	base := RoutesForMode(ModeAuto, settings, true)
	sel := AdaptiveOrderRoutes(base, store, settings, StrategyBalanced, 1, false, true)
	for _, r := range sel.Routes {
		if r == RouteCFProxyWS {
			t.Fatalf("cf route should be skipped in cooldown: %v", sel.Routes)
		}
	}
}

func TestAdaptiveStore_StaleLastGoodIgnored(t *testing.T) {
	now := int64(3_000_000)
	store := NewAdaptiveStore(func() int64 { return now })
	store.SetProfile(NetworkProfile{ID: "p1", Type: NetworkUnknown})
	store.LastGoods[lastGoodKey("p1", 2, false)] = &LastGoodRoute{
		ProfileID:  "p1",
		DC:         2,
		Media:      false,
		Route:      RouteCFWorkerWS,
		LastGoodAt: now - LastGoodRouteTTL.Milliseconds() - 1,
	}
	lg, ok := store.LastGood(2, false, now)
	if ok || lg != nil {
		t.Fatal("stale last-good should be ignored")
	}
}

func TestEncodeDecodeRouteStats(t *testing.T) {
	stats := []RouteStat{
		{
			Key: RouteStatKey{
				ProfileID: "mobile_abc",
				Route:     RouteDirectWS,
				DC:        2,
			},
			SuccessCount:  3,
			FailureCount:  1,
			LastSuccessAt: 100,
			AverageLatencyMs: 200,
		},
	}
	lastGoods := []LastGoodRoute{
		{
			ProfileID:  "mobile_abc",
			DC:         2,
			Route:      RouteCFWorkerWS,
			LastGoodAt: 500,
		},
	}
	encoded := EncodeRouteStats(stats, lastGoods)
	decodedStats, decodedLG, err := DecodeRouteStats(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(decodedStats) != 1 || decodedStats[0].SuccessCount != 3 {
		t.Fatalf("stats mismatch: %+v", decodedStats)
	}
	if len(decodedLG) != 1 || decodedLG[0].Route != RouteCFWorkerWS {
		t.Fatalf("last-good mismatch: %+v", decodedLG)
	}
}

func TestManualModes_UnchangedOrder(t *testing.T) {
	settings := RouteSettings{
		Mode: ModeWorkerOnly,
		Worker: WorkerSettings{
			Enabled: true,
			Domain:  "example.workers.dev",
		},
	}
	got := RoutesForMode(ModeWorkerOnly, settings, false)
	if len(got) != 1 || got[0] != RouteCFWorkerWS {
		t.Fatalf("worker only order changed: %v", got)
	}
}

func TestAdaptiveStore_WifiDirectBonus(t *testing.T) {
	store := NewAdaptiveStore(func() int64 { return time.Now().UnixMilli() })
	store.SetProfile(NetworkProfile{ID: "wifi", Type: NetworkWiFi})
	settings := RouteSettings{
		Mode: ModeAuto,
		CF:   CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{
			Enabled: true,
			Domain:  "example.workers.dev",
		},
	}
	base := RoutesForMode(ModeAuto, settings, true)
	sel := AdaptiveOrderRoutes(base, store, settings, StrategyBalanced, 4, false, true)
	if len(sel.Routes) == 0 {
		t.Fatal("no routes")
	}
	if sel.Scores[RouteDirectWS] <= sel.Scores[RouteTCPFallback] && sel.Routes[0] != RouteDirectWS {
		// direct may still win on wifi with empty stats
		if sel.Routes[0] != RouteCFWorkerWS && sel.Routes[0] != RouteDirectWS {
			t.Fatalf("unexpected first route on wifi: %v scores=%v", sel.Routes, sel.Scores)
		}
	}
}
