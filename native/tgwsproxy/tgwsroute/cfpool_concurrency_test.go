package tgwsroute

import (
	"sync"
	"testing"
)

func TestCFDomainPool_ConcurrentReservationDeduplicatesEndpoint(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetBuiltinDomains([]string{"shared.example"})

	start := make(chan struct{})
	results := make(chan string, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			results <- reserveFirstAvailable(pool, 2)
		}()
	}

	close(start)
	wg.Wait()
	close(results)

	reserved := 0
	for domain := range results {
		if domain != "" {
			reserved++
			pool.ReleaseReservation(2, domain)
		}
	}
	if reserved != 1 {
		t.Fatalf("reserved=%d, want exactly one reservation for the shared endpoint", reserved)
	}
}

func TestCFDomainPool_ConcurrentReservationsUseDifferentCandidates(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetBuiltinDomains([]string{"a.example", "b.example"})

	start := make(chan struct{})
	release := make(chan struct{})
	results := make(chan string, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			domain := reserveFirstAvailable(pool, 2)
			results <- domain
			<-release
			if domain != "" {
				pool.ReleaseReservation(2, domain)
			}
		}()
	}

	close(start)
	first := <-results
	second := <-results
	close(release)
	wg.Wait()

	if first == "" || second == "" {
		t.Fatalf("expected both callers to reserve an endpoint, got %q and %q", first, second)
	}
	if first == second {
		t.Fatalf("parallel callers reserved the same endpoint %q", first)
	}
}

func TestCFDomainPool_ReservationIsScopedByDC(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetBuiltinDomains([]string{"shared.example"})

	if !pool.TryReserve(2, "shared.example") {
		t.Fatal("expected DC2 reservation")
	}
	defer pool.ReleaseReservation(2, "shared.example")

	if !pool.TryReserve(1, "shared.example") {
		t.Fatal("DC2 reservation must not block the same domain for DC1")
	}
	pool.ReleaseReservation(1, "shared.example")
}

func TestCFDomainPool_SelectionSkipsInFlightAndKeepsAlternative(t *testing.T) {
	pool := NewCFDomainPool(func() float64 { return 100 })
	pool.SetBuiltinDomains([]string{"a.example", "b.example"})
	if !pool.TryReserve(2, "a.example") {
		t.Fatal("expected reservation")
	}
	defer pool.ReleaseReservation(2, "a.example")

	selection := pool.SelectionForDC(2)
	assertCandidateSet(t, selection.Candidates, []string{"b.example"})
	if len(selection.SkippedInFlight) != 1 || selection.SkippedInFlight[0].Domain != "a.example" {
		t.Fatalf("SkippedInFlight=%v, want a.example", selection.SkippedInFlight)
	}
}

func TestCFDomainPool_ReservationReleasedBySuccessFailureAndExplicitRelease(t *testing.T) {
	t.Run("success", func(t *testing.T) {
		pool := NewCFDomainPool(func() float64 { return 100 })
		pool.SetBuiltinDomains([]string{"success.example"})
		if !pool.TryReserve(2, "success.example") {
			t.Fatal("expected initial reservation")
		}
		pool.MarkSuccess(2, "success.example", 20)
		if !pool.TryReserve(2, "success.example") {
			t.Fatal("success should release reservation")
		}
		pool.ReleaseReservation(2, "success.example")
	})

	t.Run("failure", func(t *testing.T) {
		now := 100.0
		pool := NewCFDomainPool(func() float64 { return now })
		pool.SetBuiltinDomains([]string{"failure.example"})
		if !pool.TryReserve(2, "failure.example") {
			t.Fatal("expected initial reservation")
		}
		health := pool.MarkFailure(2, "failure.example", CFFailureTimeout, 20)
		now = health.CooldownUntil + 1
		if !pool.TryReserve(2, "failure.example") {
			t.Fatal("failure should release reservation; only cooldown may delay reuse")
		}
		pool.ReleaseReservation(2, "failure.example")
	})

	t.Run("explicit release", func(t *testing.T) {
		pool := NewCFDomainPool(func() float64 { return 100 })
		pool.SetBuiltinDomains([]string{"cancel.example"})
		if !pool.TryReserve(2, "cancel.example") {
			t.Fatal("expected initial reservation")
		}
		pool.ReleaseReservation(2, "cancel.example")
		if !pool.TryReserve(2, "cancel.example") {
			t.Fatal("explicit release should make endpoint reservable again")
		}
		pool.ReleaseReservation(2, "cancel.example")
	})
}

func reserveFirstAvailable(pool *CFDomainPool, dc int) string {
	for _, candidate := range pool.SelectionForDC(dc).Candidates {
		if pool.TryReserve(dc, candidate.Domain) {
			return candidate.Domain
		}
	}
	return ""
}
