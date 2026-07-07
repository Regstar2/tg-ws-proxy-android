package main

import "testing"

func TestParseWorkerFailoverCandidates(t *testing.T) {
	raw := "id1:one.user.workers.dev|id2:two.user.workers.dev"
	got := parseWorkerFailoverCandidates(raw)
	if len(got) != 2 {
		t.Fatalf("len=%d want 2", len(got))
	}
	if got[0].ID != "id1" || got[0].Domain != "one.user.workers.dev" {
		t.Fatalf("first=%+v", got[0])
	}
	if got[1].ID != "id2" || got[1].Domain != "two.user.workers.dev" {
		t.Fatalf("second=%+v", got[1])
	}
}

func TestWorkerFailoverMaxAttempts(t *testing.T) {
	settings := workerFailoverSettings{MaxAttempts: 0}
	if got := settings.maxAttemptsFor(5); got != defaultMaxWorkerFailoverAttempts {
		t.Fatalf("default max got=%d want=%d", got, defaultMaxWorkerFailoverAttempts)
	}
	settings.MaxAttempts = 2
	if got := settings.maxAttemptsFor(5); got != 2 {
		t.Fatalf("capped max got=%d want=2", got)
	}
}

func TestEffectiveCandidatesFallback(t *testing.T) {
	settings := workerFailoverSettings{Enabled: false}
	got := settings.effectiveCandidates("legacy.user.workers.dev")
	if len(got) != 1 || got[0].Domain != "legacy.user.workers.dev" {
		t.Fatalf("legacy fallback=%+v", got)
	}
}
