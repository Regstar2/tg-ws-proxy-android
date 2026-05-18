package tgwsroute

import "testing"

func TestNormalizeWorkerDomain(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"example.username.workers.dev", "example.username.workers.dev"},
		{"https://example.username.workers.dev/", "example.username.workers.dev"},
		{"http://example.username.workers.dev/apiws?dst=1.1.1.1", "example.username.workers.dev"},
		{"  example.username.workers.dev  ", "example.username.workers.dev"},
	}
	for _, tc := range cases {
		got := NormalizeWorkerDomain(tc.in)
		if got != tc.want {
			t.Fatalf("NormalizeWorkerDomain(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestBuildWorkerWSURL(t *testing.T) {
	got := BuildWorkerWSURL("example.username.workers.dev", 2, "149.154.167.50", false)
	want := "wss://example.username.workers.dev/apiws?dst=149.154.167.50&dc=2&media=0"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	gotMedia := BuildWorkerWSURL("example.username.workers.dev", 2, "149.154.167.50", true)
	wantMedia := "wss://example.username.workers.dev/apiws?dst=149.154.167.50&dc=2&media=1"
	if gotMedia != wantMedia {
		t.Fatalf("media got %q want %q", gotMedia, wantMedia)
	}
}

func TestRoutesForMode_Order(t *testing.T) {
	settings := RouteSettings{
		Mode: ModeDirectWithFallback,
		CF:   CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{
			Enabled: true,
			Domain:  "example.username.workers.dev",
		},
	}
	assertOrder(t, RoutesForMode(ModeAuto, settings, false),
		[]RouteKind{RouteDirectWS, RouteCFWorkerWS, RouteCFProxyWS, RouteTCPFallback})
	assertOrder(t, RoutesForMode(ModeDirectWithFallback, settings, false),
		[]RouteKind{RouteDirectWS, RouteCFWorkerWS, RouteCFProxyWS, RouteTCPFallback})
	assertOrder(t, RoutesForMode(ModeWorkerFirst, settings, false),
		[]RouteKind{RouteCFWorkerWS, RouteCFProxyWS, RouteDirectWS, RouteTCPFallback})
	assertOrder(t, RoutesForMode(ModeCFFirst, settings, false),
		[]RouteKind{RouteCFProxyWS, RouteCFWorkerWS, RouteDirectWS, RouteTCPFallback})
	assertOrder(t, RoutesForMode(ModeWorkerOnly, settings, false), []RouteKind{RouteCFWorkerWS})
	assertOrder(t, RoutesForMode(ModeCFOnly, settings, false), []RouteKind{RouteCFProxyWS})
	assertOrder(t, RoutesForMode(ModeDirectOnly, settings, false), []RouteKind{RouteDirectWS})
}

func TestRoutesForMode_EmptyWorkerSkipped(t *testing.T) {
	settings := RouteSettings{
		Mode:   ModeWorkerFirst,
		CF:     CFProxyFlags{Enabled: true},
		Worker: WorkerSettings{Enabled: true, Domain: ""},
	}
	for _, r := range RoutesForMode(ModeWorkerFirst, settings, false) {
		if r == RouteCFWorkerWS {
			t.Fatal("worker route should be omitted")
		}
	}
}

func TestRoutesForMode_RestrictedModesDoNotInventFallbacks(t *testing.T) {
	if got := RoutesForMode(ModeWorkerOnly, RouteSettings{Mode: ModeWorkerOnly}, false); len(got) != 0 {
		t.Fatalf("worker_only without Worker config should have no silent fallbacks, got %v", got)
	}
	if got := RoutesForMode(ModeCFOnly, RouteSettings{Mode: ModeCFOnly}, false); len(got) != 0 {
		t.Fatalf("cf_only without CF config should have no silent fallbacks, got %v", got)
	}
}

func TestLegacyModeFromCF(t *testing.T) {
	if LegacyModeFromCF(CFProxyFlags{Only: true}) != ModeCFOnly {
		t.Fatal("expected cf only")
	}
	if LegacyModeFromCF(CFProxyFlags{Enabled: true, Priority: true}) != ModeCFFirst {
		t.Fatal("expected cf first")
	}
}

func TestLookupTelegramDC_EdgeCaseIPv4(t *testing.T) {
	info, ok := LookupTelegramDC("149.154.175.55")
	if !ok {
		t.Fatal("expected 149.154.175.55 to map to a Telegram DC")
	}
	if info.DC != 1 || info.Media {
		t.Fatalf("LookupTelegramDC(149.154.175.55) = %+v, want DC1 non-media", info)
	}
}

func TestBlocksDirectPassthrough_RestrictedModes(t *testing.T) {
	for _, mode := range []ConnectionMode{ModeWorkerOnly, ModeCFOnly} {
		if !BlocksDirectPassthrough(mode, "149.154.175.250", false) {
			t.Fatalf("%s should block unknown Telegram-like IPv4 passthrough", mode)
		}
		if !BlocksDirectPassthrough(mode, "2a0a:f280:203:a:5000::100", false) {
			t.Fatalf("%s should block Telegram-like IPv6 passthrough", mode)
		}
	}
}

func TestBlocksDirectPassthrough_NormalTrafficPreserved(t *testing.T) {
	if BlocksDirectPassthrough(ModeAuto, "203.0.113.10", false) {
		t.Fatal("normal IPv4 traffic in auto mode should preserve passthrough behavior")
	}
	if BlocksDirectPassthrough(ModeAuto, "2001:db8::10", false) {
		t.Fatal("normal IPv6 traffic in auto mode should preserve passthrough behavior")
	}
	if BlocksDirectPassthrough(ModeWorkerOnly, "149.154.175.55", true) {
		t.Fatal("mapped Telegram destinations should use the route chain instead of unknown-destination blocking")
	}
}

func TestCFDomainPool_ImmediateCooldownFailures(t *testing.T) {
	for _, kind := range []CFFailureKind{CFFailureRateLimit, CFFailureForbidden, CFFailureServer} {
		now := 100.0
		pool := NewCFDomainPool(func() float64 { return now })
		pool.SetBuiltinDomains([]string{"pool.example"})
		health := pool.MarkFailure("pool.example", kind, 90)
		if !pool.IsCoolingDown("pool.example") {
			t.Fatalf("%s should mark the domain unhealthy", kind)
		}
		if health.CooldownUntil <= now {
			t.Fatalf("%s should set cooldown until the future", kind)
		}
	}
}

func TestCFDomainPool_ProgressiveCooldown(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	first := pool.MarkFailure("pool.example", CFFailureTimeout, 100)
	if first.CooldownUntil-now != 30 {
		t.Fatalf("first timeout cooldown = %.0f, want 30", first.CooldownUntil-now)
	}
	now = first.CooldownUntil + 1
	second := pool.MarkFailure("pool.example", CFFailureTimeout, 100)
	if second.CooldownUntil-now != 120 {
		t.Fatalf("second timeout cooldown = %.0f, want 120", second.CooldownUntil-now)
	}
	now = second.CooldownUntil + 1
	third := pool.MarkFailure("pool.example", CFFailureTimeout, 100)
	if third.CooldownUntil-now != 300 {
		t.Fatalf("third timeout cooldown = %.0f, want 300", third.CooldownUntil-now)
	}
}

func TestCFDomainPool_ManualCooldownFallsBackToPool(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetManualDomain("manual.example")
	pool.SetBuiltinDomains([]string{"pool-a.example", "pool-b.example"})

	pool.MarkFailure("manual.example", CFFailureRateLimit, 90)
	assertCandidateSet(t, pool.SelectionForDC(2).Candidates, []string{"pool-a.example", "pool-b.example"})

	now += 301
	candidates := pool.SelectionForDC(2).Candidates
	if len(candidates) == 0 || candidates[0].Domain != "manual.example" {
		t.Fatalf("manual domain should lead after cooldown, got %v", candidates)
	}
	assertCandidateSet(t, candidates[1:], []string{"pool-a.example", "pool-b.example"})
}

func TestCFDomainPool_AllDomainsUnhealthy(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetManualDomain("manual.example")
	pool.SetBuiltinDomains([]string{"pool.example"})
	pool.MarkFailure("manual.example", CFFailureRateLimit, 90)
	pool.MarkFailure("pool.example", CFFailureForbidden, 90)

	assertCandidates(t, pool.SelectionForDC(2).Candidates, nil)
}

func TestNormalizeCFDomain(t *testing.T) {
	cases := []struct {
		in   string
		want string
		ok   bool
	}{
		{"https://virkgj.com/", "virkgj.com", true},
		{"http://virkgj.com/apiws", "virkgj.com", true},
		{" virkgj.com ", "virkgj.com", true},
		{"domain with spaces.com", "", false},
		{"example.com:443", "", false},
		{"example.com/apiws", "", false},
		{"wss://example.com", "", false},
		{"https://", "", false},
		{"/path-only", "", false},
	}
	for _, tc := range cases {
		got, ok := NormalizeCFDomain(tc.in)
		if got != tc.want || ok != tc.ok {
			t.Fatalf("NormalizeCFDomain(%q) = (%q, %t), want (%q, %t)", tc.in, got, ok, tc.want, tc.ok)
		}
	}
}

func TestBuiltInCFDomains(t *testing.T) {
	domains := BuiltInCFDomains()
	if len(domains) == 0 {
		t.Fatal("built-in pool should not be empty")
	}
	seen := make(map[string]struct{}, len(domains))
	for _, domain := range domains {
		if !IsValidCFDomain(domain) {
			t.Fatalf("invalid built-in domain %q", domain)
		}
		if _, exists := seen[domain]; exists {
			t.Fatalf("duplicate built-in domain %q", domain)
		}
		seen[domain] = struct{}{}
	}
}

func TestCFDomainPool_ManualSelectedBeforeBuiltIn(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetManualDomain("manual.example")
	pool.SetBuiltinDomains([]string{"pool.example"})
	selection := pool.SelectionForDC(2)
	assertCandidates(t, selection.Candidates, []string{"manual.example", "pool.example"})
	if selection.Candidates[0].Source != CFDomainSourceManual {
		t.Fatalf("first source = %s, want manual", selection.Candidates[0].Source)
	}
}

func TestCFDomainPool_BuiltIn429MovesToNextDomain(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetBuiltinDomains([]string{"pool-a.example", "pool-b.example"})
	pool.MarkFailure("pool-a.example", CFFailureRateLimit, 100)
	assertCandidates(t, pool.SelectionForDC(2).Candidates, []string{"pool-b.example"})
}

func TestCFDomainPool_ResetCooldowns(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetManualDomain("manual.example")
	pool.MarkFailure("manual.example", CFFailureForbidden, 100)
	if !pool.IsCoolingDown("manual.example") {
		t.Fatal("manual domain should be cooling down before reset")
	}
	pool.ResetCooldowns()
	if pool.IsCoolingDown("manual.example") {
		t.Fatal("manual domain cooldown should be cleared")
	}
}

func assertOrder(t *testing.T, got, want []RouteKind) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("len %d != %d: %v", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("[%d]=%s want %s", i, got[i], want[i])
		}
	}
}

func assertCandidates(t *testing.T, got []CFDomainCandidate, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("len %d != %d: %v", len(got), len(want), got)
	}
	for i := range want {
		if got[i].Domain != want[i] {
			t.Fatalf("[%d]=%s want %s", i, got[i].Domain, want[i])
		}
	}
}

func assertCandidateSet(t *testing.T, got []CFDomainCandidate, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("len %d != %d: %v", len(got), len(want), got)
	}
	seen := make(map[string]struct{}, len(got))
	for _, candidate := range got {
		seen[candidate.Domain] = struct{}{}
	}
	for _, domain := range want {
		if _, ok := seen[domain]; !ok {
			t.Fatalf("missing %s in %v", domain, got)
		}
	}
}
