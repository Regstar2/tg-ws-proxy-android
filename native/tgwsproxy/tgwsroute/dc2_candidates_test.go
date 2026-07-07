package tgwsroute

import "testing"

func TestIsDC2WorkerCandidate(t *testing.T) {
	if !IsDC2WorkerCandidate("149.154.167.41") {
		t.Fatal("expected .41 to be dc2 candidate")
	}
	if IsDC2WorkerCandidate("149.154.175.50") {
		t.Fatal("dc1 ip must not match dc2 candidates")
	}
}

func TestHasDC2ManualOverride(t *testing.T) {
	if HasDC2ManualOverride(nil) {
		t.Fatal("nil map should not be manual override")
	}
	if HasDC2ManualOverride(map[int]string{}) {
		t.Fatal("empty map should not be manual override")
	}
	if !HasDC2ManualOverride(map[int]string{2: "149.154.167.50"}) {
		t.Fatal("configured dc2 ip should count as manual override")
	}
}

func TestDC2WorkerCandidatesContainsExpectedIPs(t *testing.T) {
	want := map[string]bool{
		"149.154.167.41": true,
		DC2DemotedIPv4Candidate: true,
		DC2DefaultIPv4Candidate: true,
	}
	if len(DC2WorkerCandidates) != len(want) {
		t.Fatalf("candidate count=%d want=%d", len(DC2WorkerCandidates), len(want))
	}
	for _, ip := range DC2WorkerCandidates {
		if !want[ip] {
			t.Fatalf("unexpected candidate %q", ip)
		}
		delete(want, ip)
	}
	if len(want) != 0 {
		t.Fatalf("missing candidates: %v", want)
	}
}
