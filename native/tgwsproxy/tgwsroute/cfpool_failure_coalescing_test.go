package tgwsroute

import (
	"sync"
	"testing"
)

func TestCFDomainPool_CoalescesConcurrentServerFailureBurst(t *testing.T) {
	const (
		dc      = 2
		domain  = "burst.example"
		callers = 12
		now     = 100.0
	)
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetBuiltinDomains([]string{domain})

	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			pool.MarkFailure(dc, domain, CFFailureServer, 50)
		}()
	}
	close(start)
	wg.Wait()

	health := requireCFDomainHealth(t, pool, dc, domain)
	if health.FailureCount != 1 {
		t.Fatalf("FailureCount=%d, want one counted failure burst", health.FailureCount)
	}
	if health.ConsecutiveFailures != 1 {
		t.Fatalf("ConsecutiveFailures=%d, want 1", health.ConsecutiveFailures)
	}
	if health.LastFailureAt != now {
		t.Fatalf("LastFailureAt=%v, want %v", health.LastFailureAt, now)
	}
	if health.LastFailureReason != CFFailureServer {
		t.Fatalf("LastFailureReason=%q, want %q", health.LastFailureReason, CFFailureServer)
	}
	if health.CooldownUntil != now+120 {
		t.Fatalf("CooldownUntil=%v, want %v", health.CooldownUntil, now+120)
	}
	if health.LastLatencyMs != 50 {
		t.Fatalf("LastLatencyMs=%d, want 50", health.LastLatencyMs)
	}
}

func TestCFDomainPool_DuplicateDuringCooldownDoesNotMutateCountedFailure(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetBuiltinDomains([]string{"duplicate.example"})

	first := pool.MarkFailure(2, "duplicate.example", CFFailureServer, 40)
	now = 101
	duplicate := pool.MarkFailure(2, "duplicate.example", CFFailureForbidden, 900)

	if duplicate.FailureCount != first.FailureCount || duplicate.ConsecutiveFailures != first.ConsecutiveFailures {
		t.Fatalf("duplicate failure changed counters: first=%+v duplicate=%+v", first, duplicate)
	}
	if duplicate.LastFailureAt != first.LastFailureAt || duplicate.LastFailureReason != first.LastFailureReason {
		t.Fatalf("duplicate failure changed last counted failure: first=%+v duplicate=%+v", first, duplicate)
	}
	if duplicate.CooldownUntil != first.CooldownUntil {
		t.Fatalf("duplicate failure extended cooldown: first=%v duplicate=%v", first.CooldownUntil, duplicate.CooldownUntil)
	}
	if duplicate.LastLatencyMs != first.LastLatencyMs {
		t.Fatalf("duplicate failure changed latency: first=%d duplicate=%d", first.LastLatencyMs, duplicate.LastLatencyMs)
	}
}

func TestCFDomainPool_SequentialRetryAfterCooldownEscalatesBackoff(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetBuiltinDomains([]string{"retry.example"})

	first := pool.MarkFailure(2, "retry.example", CFFailureTimeout, 10)
	if got := first.CooldownUntil - now; got != 30 {
		t.Fatalf("first timeout cooldown=%v, want 30", got)
	}

	now = first.CooldownUntil + 1
	second := pool.MarkFailure(2, "retry.example", CFFailureTimeout, 20)
	if second.FailureCount != 2 || second.ConsecutiveFailures != 2 {
		t.Fatalf("second sequential failure counters=%+v, want FailureCount=2 ConsecutiveFailures=2", second)
	}
	if got := second.CooldownUntil - now; got != 120 {
		t.Fatalf("second timeout cooldown=%v, want 120", got)
	}
	if second.LastFailureAt != now || second.LastLatencyMs != 20 {
		t.Fatalf("second sequential failure did not become latest counted failure: %+v", second)
	}

	now = second.CooldownUntil + 1
	success := pool.MarkSuccess(2, "retry.example", 15)
	if success.SuccessCount != 1 || success.FailureCount != 2 || success.ConsecutiveFailures != 0 {
		t.Fatalf("success counters=%+v, want SuccessCount=1 FailureCount=2 ConsecutiveFailures=0", success)
	}
	if success.CooldownUntil != 0 {
		t.Fatalf("success CooldownUntil=%v, want 0", success.CooldownUntil)
	}
}

func TestCFDomainPool_FailureKindCooldownPolicy(t *testing.T) {
	tests := []struct {
		name string
		kind CFFailureKind
		want float64
	}{
		{name: "dns", kind: CFFailureDNS, want: 120},
		{name: "rate_limit", kind: CFFailureRateLimit, want: 300},
		{name: "forbidden", kind: CFFailureForbidden, want: 600},
		{name: "server", kind: CFFailureServer, want: 120},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			const now = 100.0
			pool := NewCFDomainPool(func() float64 { return now })
			pool.SetBuiltinDomains([]string{"policy.example"})
			health := pool.MarkFailure(2, "policy.example", tt.kind, 0)
			if got := health.CooldownUntil - now; got != tt.want {
				t.Fatalf("cooldown=%v, want %v", got, tt.want)
			}
		})
	}
}

func TestCFDomainPool_CachedUpstreamDNSKeepsSixHourCooldown(t *testing.T) {
	const now = 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetCachedUpstreamDomains([]string{"cached.example"})

	health := pool.MarkFailure(2, "cached.example", CFFailureDNS, 0)
	if got := health.CooldownUntil - now; got != cachedUpstreamDNSCooldownSeconds {
		t.Fatalf("cached DNS cooldown=%v, want %v", got, cachedUpstreamDNSCooldownSeconds)
	}
}

func requireCFDomainHealth(t *testing.T, pool *CFDomainPool, dc int, domain string) CFDomainHealth {
	t.Helper()
	for _, health := range pool.Snapshot() {
		if health.DC == dc && health.Domain == domain {
			return health
		}
	}
	t.Fatalf("health not found for DC%d %s", dc, domain)
	return CFDomainHealth{}
}
