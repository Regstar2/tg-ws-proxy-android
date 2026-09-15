package tgwsroute

import "testing"

func TestCFDomainPool_ManualRemainsAbsolutePriority(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetManualDomain("manual.example")
	pool.SetCachedUpstreamDomains([]string{"cached.example"})
	pool.SetBuiltinDomains([]string{"builtin.example"})
	pool.MarkSuccess(2, "builtin.example", 20)

	for selectionNumber := 1; selectionNumber <= cfDomainExplorationInterval+1; selectionNumber++ {
		selection := pool.SelectionForDC(2)
		if len(selection.Candidates) == 0 || selection.Candidates[0].Domain != "manual.example" {
			t.Fatalf("selection %d first candidate = %v, want manual.example", selectionNumber, selection.Candidates)
		}
	}
}

func TestCFDomainPool_HealthyBuiltInBeatsDegradedCachedUpstream(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetCachedUpstreamDomains([]string{"cached.example"})
	pool.SetBuiltinDomains([]string{"builtin.example"})

	for attempt := 0; attempt < 3; attempt++ {
		health := pool.MarkFailure(2, "cached.example", CFFailureServer, 80)
		now = health.CooldownUntil + 1
	}
	pool.MarkSuccess(2, "builtin.example", 30)

	selection := pool.SelectionForDC(2)
	if len(selection.Candidates) != 2 {
		t.Fatalf("candidates = %v, want cached and builtin", selection.Candidates)
	}
	if selection.Candidates[0].Domain != "builtin.example" {
		t.Fatalf("first candidate = %s, want healthy builtin.example; candidates=%v", selection.Candidates[0].Domain, selection.Candidates)
	}
	if selection.Candidates[0].Score <= selection.Candidates[1].Score {
		t.Fatalf("healthy builtin score=%d should exceed degraded cached score=%d", selection.Candidates[0].Score, selection.Candidates[1].Score)
	}
}

func TestCFDomainPool_RecentSuccessBoostDecays(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetCachedUpstreamDomains([]string{"cached.example"})
	pool.SetBuiltinDomains([]string{"builtin.example"})

	initial := pool.SelectionForDC(2)
	if initial.Candidates[0].Domain != "cached.example" {
		t.Fatalf("neutral pool should preserve cached source preference, got %v", initial.Candidates)
	}

	pool.MarkSuccess(2, "builtin.example", 20)
	recent := pool.SelectionForDC(2)
	if recent.Candidates[0].Domain != "builtin.example" {
		t.Fatalf("recently successful builtin should lead, got %v", recent.Candidates)
	}

	now += 3 * 60 * 60
	aged := pool.SelectionForDC(2)
	if aged.Candidates[0].Domain != "cached.example" {
		t.Fatalf("after recent-success bonus decays, cached source bonus should regain neutral lead, got %v", aged.Candidates)
	}
}

func TestCFDomainPool_OldFailurePenaltyDecays(t *testing.T) {
	now := 100.0
	pool := NewCFDomainPool(func() float64 { return now })
	pool.SetCachedUpstreamDomains([]string{"cached.example"})
	pool.SetBuiltinDomains([]string{"builtin.example"})

	health := pool.MarkFailure(2, "cached.example", CFFailureServer, 50)
	now = health.CooldownUntil + 1
	degraded := pool.SelectionForDC(2)
	if degraded.Candidates[0].Domain != "builtin.example" {
		t.Fatalf("recent cached failure should let builtin lead, got %v", degraded.Candidates)
	}

	now = health.LastFailureAt + 7*60*60
	recovered := pool.SelectionForDC(2)
	if recovered.Candidates[0].Domain != "cached.example" {
		t.Fatalf("old failure should stop permanently suppressing cached endpoint, got %v", recovered.Candidates)
	}
}

func TestCFDomainPool_ExplorationPreventsBuiltInStarvation(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetCachedUpstreamDomains([]string{"cached.example"})
	pool.SetBuiltinDomains([]string{"builtin.example"})

	for selectionNumber := 1; selectionNumber < cfDomainExplorationInterval; selectionNumber++ {
		selection := pool.SelectionForDC(2)
		if selection.Candidates[0].Domain != "cached.example" {
			t.Fatalf("selection %d first candidate = %s, want cached.example before exploration", selectionNumber, selection.Candidates[0].Domain)
		}
	}

	exploration := pool.SelectionForDC(2)
	if exploration.Candidates[0].Domain != "builtin.example" {
		t.Fatalf("exploration selection should probe builtin instead of starving it, got %v", exploration.Candidates)
	}

	next := pool.SelectionForDC(2)
	if next.Candidates[0].Domain != "cached.example" {
		t.Fatalf("exploration should be bounded; next selection got %v", next.Candidates)
	}
}
