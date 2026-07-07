package main

import "testing"

func TestWorkerSessionResult(t *testing.T) {
	if got := workerSessionResult(0, 0); got != "no_payload" {
		t.Fatalf("got=%q want=no_payload", got)
	}
	if got := workerSessionResult(524288, 0); got != "zero_down" {
		t.Fatalf("got=%q want=zero_down", got)
	}
	if got := workerSessionResult(1024, 512); got != "bidirectional" {
		t.Fatalf("got=%q want=bidirectional", got)
	}
}

func TestNewWorkerSessionID_UniqueMonotonic(t *testing.T) {
	a := newWorkerSessionID()
	b := newWorkerSessionID()
	if a == "" || b == "" {
		t.Fatal("expected non-empty session ids")
	}
	if a == b {
		t.Fatalf("expected unique ids, got %q", a)
	}
}
