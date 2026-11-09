package main

import (
	"context"
	"errors"
	"reflect"
	"sync"
	"testing"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestDirectWSCandidatesKeepExistingTargetAndScope(t *testing.T) {
	cases := []struct {
		dc     int
		target string
		allow  bool
		want   []string
	}{
		{2, "149.154.167.51", true, []string{"149.154.167.51", "149.154.167.220"}},
		{4, "149.154.167.91", true, []string{"149.154.167.91", "149.154.167.220"}},
		{1, "149.154.175.50", true, []string{"149.154.175.50"}},
		{2, "149.154.167.41", true, []string{"149.154.167.41"}},
		{2, "149.154.167.51", false, []string{"149.154.167.51"}},
	}
	for _, tc := range cases {
		got := directWSTargetCandidates(tc.dc, tc.target, tc.allow)
		if !reflect.DeepEqual(got, tc.want) {
			t.Errorf("DC%d target=%s allow=%t got=%v want=%v",
				tc.dc, tc.target, tc.allow, got, tc.want)
		}
	}
}

func TestDirectWSCircuitBoundsInFlightAndRecovers(t *testing.T) {
	resetDirectWSHealthForTests()
	t.Cleanup(resetDirectWSHealthForTests)
	const generation = uint64(209)
	now := time.Now()
	releaseA, reason := beginDirectWSAttempt(2, "192.0.2.42", generation, now)
	if reason != "" || releaseA == nil {
		t.Fatalf("first acquisition: %q", reason)
	}
	releaseB, reason := beginDirectWSAttempt(2, "192.0.2.42", generation, now)
	if reason != "" || releaseB == nil {
		t.Fatalf("second acquisition: %q", reason)
	}
	if _, reason = beginDirectWSAttempt(2, "192.0.2.42", generation, now); reason != "direct_ws_dial_busy" {
		t.Fatalf("third acquisition reason=%s", reason)
	}
	releaseA()
	releaseB()

	markDirectWSFailure(2, "192.0.2.42", generation, directTCPFailureTimeout, now)
	if _, reason := beginDirectWSAttempt(2, "192.0.2.42", generation, now.Add(time.Second)); reason != "direct_ws_ip_cooldown" {
		t.Fatalf("cooldown reason=%s", reason)
	}
	afterBackoff := now.Add(directWSMaxBackoff + time.Second)
	release, reason := beginDirectWSAttempt(2, "192.0.2.42", generation, afterBackoff)
	if reason != "" || release == nil {
		t.Fatalf("retry after backoff reason=%s", reason)
	}
	markDirectWSSuccess(2, "192.0.2.42", generation)
	release()
	if next, reason := beginDirectWSAttempt(2, "192.0.2.42", generation, afterBackoff); reason != "" {
		t.Fatalf("after success reason=%s", reason)
	} else {
		next()
	}
	// A fresh runtime policy generation clears stale cooldown without
	// affecting connections already in flight.
	markDirectWSFailure(2, "192.0.2.42", generation, directTCPFailureTimeout, afterBackoff)
	release, reason = beginDirectWSAttempt(2, "192.0.2.42", generation+1, afterBackoff)
	if reason != "" || release == nil {
		t.Fatalf("new policy generation reason=%s", reason)
	}
	release()
}

func TestDirectWSCircuitConcurrentAttempts(t *testing.T) {
	resetDirectWSHealthForTests()
	t.Cleanup(resetDirectWSHealthForTests)
	const n = 32
	start := make(chan struct{})
	releaseAll := make(chan struct{})
	var wg sync.WaitGroup
	var mu sync.Mutex
	accepted := 0
	rejected := 0
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			release, reason := beginDirectWSAttempt(
				2, "192.0.2.123", 45, time.Now())
			if reason != "" {
				if reason != "direct_ws_dial_busy" {
					t.Errorf("unexpected rejection %s", reason)
				}
				mu.Lock()
				rejected++
				mu.Unlock()
				return
			}
			mu.Lock()
			accepted++
			mu.Unlock()
			<-releaseAll
			release()
		}()
	}
	close(start)
	// Keep accepted attempts in flight while all others begin.
	time.Sleep(100 * time.Millisecond)
	close(releaseAll)
	wg.Wait()
	if accepted > directWSMaxConcurrentDials || accepted == 0 || accepted+rejected != n {
		t.Fatalf("accepted=%d rejected=%d", accepted, rejected)
	}
}

func TestDirectWSFailureClassificationOnlyTCP(t *testing.T) {
	tcp := &wsStageError{Stage: "tcp_dial", Err: timeoutErr{}}
	tls := &wsStageError{Stage: "tls_handshake", Err: timeoutErr{}}
	if got := directWSTCPFailureReason(tcp); got != directTCPFailureTimeout {
		t.Fatalf("TCP classification=%q", got)
	}
	if got := directWSTCPFailureReason(tls); got != "" {
		t.Fatalf("TLS timeout must not trigger TCP cooldown: %q", got)
	}
}

func TestDirectWSConnectorUsesFlowsealAlternativeOnlyAfterPrimaryFailure(t *testing.T) {
	resetDirectWSHealthForTests()
	clearDirectIPCooldowns()
	t.Cleanup(resetDirectWSHealthForTests)
	t.Cleanup(clearDirectIPCooldowns)

	sock := &fakeMtProtoFrameSocket{}
	var targets []string
	connector := newMtProtoDirectWSConnector()
	connector.resolveTarget = func(int) (string, int, bool) {
		return "149.154.167.51", 443, true
	}
	connector.dialContext = func(ctx context.Context, target, domain, path string, timeout float64) (mtProtoFrameSocket, error) {
		targets = append(targets, target)
		if target == "149.154.167.51" {
			return nil, &wsStageError{Stage: "tcp_dial", Err: timeoutErr{}}
		}
		if target != "149.154.167.220" || domain != "kws2.web.telegram.org" || path != "/apiws" {
			t.Fatalf("unexpected alternate %s %s %s", target, domain, path)
		}
		return sock, nil
	}
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID: 2, RelayInit: buildTestInitWithSignedDC(t, 2),
	})
	if result.Err != nil || result.ActualBackend != mtProtoDirectWSBackend || conn == nil {
		t.Fatalf("unexpected result: %+v", result)
	}
	defer conn.Close()
	if !reflect.DeepEqual(targets, []string{
		"149.154.167.51", "149.154.167.51", "149.154.167.220",
	}) {
		t.Fatalf("unexpected dial order: %v", targets)
	}
}

func TestDirectWSConnectorCancellationStopsDial(t *testing.T) {
	resetDirectWSHealthForTests()
	t.Cleanup(resetDirectWSHealthForTests)
	ctx, cancel := context.WithCancel(context.Background())
	start := make(chan struct{})
	connector := newMtProtoDirectWSConnector()
	connector.enableAlternates = false
	connector.resolveTarget = func(int) (string, int, bool) {
		return "192.0.2.88", 443, true
	}
	connector.dialContext = func(ctx context.Context, target, domain, path string, timeout float64) (mtProtoFrameSocket, error) {
		close(start)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	done := make(chan mtproxyfrontend.OutboundResult, 1)
	go func() {
		_, result := connector.Connect(ctx, mtproxyfrontend.OutboundRequest{DCID: 3})
		done <- result
	}()
	select {
	case <-start:
	case <-time.After(time.Second):
		t.Fatal("dial not started")
	}
	cancel()
	select {
	case result := <-done:
		if !errors.Is(result.Err, context.Canceled) || result.Reason != "cancelled" {
			t.Fatalf("cancel result=%+v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("direct dial did not respond to cancellation")
	}
}
