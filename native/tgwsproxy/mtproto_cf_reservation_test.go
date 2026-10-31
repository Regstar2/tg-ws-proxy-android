package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
	"tg-ws-proxy/tgwsroute"
)

func TestMtProtoCFProxyConcurrentRequestsReserveEndpoint(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.PolicyPresent = false
		return settings
	})
	withTestCFPool(t, []string{"pool.example"})

	started := make(chan struct{})
	release := make(chan struct{})
	done := make(chan struct{})
	var dials atomic.Int32
	connector := &mtProtoCFProxyConnector{dial: func(_, _, _ string) (mtProtoFrameSocket, error) {
		dials.Add(1)
		close(started)
		<-release
		return nil, errors.New("test failure")
	}}
	request := mtproxyfrontend.OutboundRequest{DCID: 2}
	go func() {
		defer close(done)
		connector.Connect(context.Background(), request)
	}()
	<-started

	var wg sync.WaitGroup
	var sampled atomic.Int32
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, result := connector.Connect(context.Background(), request)
			if result.Reason != "cfproxy_unavailable" {
				t.Errorf("reason=%s, want cfproxy_unavailable", result.Reason)
			}
			if result.Err == nil || !strings.Contains(result.Err.Error(), "in_flight=1") {
				t.Errorf("in-flight availability missing: %v", result.Err)
			}
			if mtproxyfrontend.LogCFPoolMiss(result.CFPoolMissCount) {
				sampled.Add(1)
			}
		}()
	}
	wg.Wait()
	close(release)
	<-done
	if got := dials.Load(); got != 1 {
		t.Fatalf("dials=%d, want 1", got)
	}
	if got := sampled.Load(); got > 8 {
		t.Fatalf("sampled %d of 100 pool misses", got)
	}
}

func TestMtProtoCFProxyFallbackKeepsReservationDuringReconnectBurst(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.PolicyPresent = false
		return settings
	})
	withTestCFPool(t, []string{"pool.example"})
	started, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var dials atomic.Int32
	connector := &mtProtoCFProxyConnector{dial: func(_, _, _ string) (mtProtoFrameSocket, error) {
		dialer := cfProxyTLSDialer{dial: func(_, _, _ string, _ float64, curves []tls.CurveID) (*RawWebSocket, error) {
			dials.Add(1)
			if curves != nil {
				close(started)
				<-release
			}
			return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("test outage")}
		}}
		return dialer.connect("target", "pool.example", "/apiws", 1)
	}}
	request := mtproxyfrontend.OutboundRequest{DCID: 2}
	go func() { defer close(done); connector.Connect(context.Background(), request) }()
	<-started
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, result := connector.Connect(context.Background(), request); result.Reason != "cfproxy_unavailable" {
				t.Errorf("unexpected result: %+v", result)
			}
		}()
	}
	wg.Wait()
	close(release)
	<-done
	if got := dials.Load(); got != 2 {
		t.Fatalf("dials=%d, want one primary and one fallback", got)
	}
}

func TestCFPoolMissSamplesArePerDCAndReset(t *testing.T) {
	now := time.Unix(100, 0)
	for _, tc := range []struct {
		dc   int
		at   time.Time
		want int64
	}{
		{9901, now, 1}, {9901, now, 2}, {9902, now, 1},
		{9901, now.Add(30 * time.Second), 1},
	} {
		if got := countCFPoolMiss(tc.dc, tc.at); got != tc.want {
			t.Fatalf("dc %d count=%d want=%d", tc.dc, got, tc.want)
		}
	}
}

func TestMtProtoCFProxyExhaustionAndRecoveryByDC(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.PolicyPresent = false
		return settings
	})
	previous := cfPool
	now := 1000.0
	cfPool = tgwsroute.NewCFDomainPool(func() float64 { return now })
	t.Cleanup(func() { cfPool = previous })

	var dials int
	connector := &mtProtoCFProxyConnector{dial: func(_, _, _ string) (mtProtoFrameSocket, error) {
		dials++
		return &fakeMtProtoFrameSocket{}, nil
	}}
	request := mtproxyfrontend.OutboundRequest{DCID: 2, RelayInit: buildTestInitWithSignedDC(t, 2)}
	assertUnavailable := func() {
		t.Helper()
		conn, result := connector.Connect(context.Background(), request)
		if conn != nil || result.Reason != "cfproxy_unavailable" || result.Err == nil {
			t.Fatalf("unexpected exhaustion result: %+v", result)
		}
	}
	assertUnavailable() // An empty pool must not dial.
	cfPool.SetBuiltinDomains([]string{"pool.example"})
	cfPool.MarkFailure(2, "pool.example", tgwsroute.CFFailureServer, 0)
	_, detail := connector.Connect(context.Background(), request)
	for _, field := range []string{"dc=2", "eligible=0", "cooldown=1", "in_flight=0", "retry_after_seconds=120.0"} {
		if detail.Err == nil || !strings.Contains(detail.Err.Error(), field) {
			t.Fatalf("missing %s in exhaustion detail: %v", field, detail.Err)
		}
	}
	for i := 0; i < 100; i++ {
		assertUnavailable()
	}
	if dials != 0 {
		t.Fatalf("exhausted pool made %d dials", dials)
	}
	if len(cfPool.SelectionForDC(1).Candidates) != 1 {
		t.Fatal("DC2 cooldown affected DC1")
	}
	now += 301
	conn, result := connector.Connect(context.Background(), request)
	if conn == nil || result.Err != nil || result.ActualBackend != mtProtoCFProxyBackend {
		t.Fatalf("recovery failed: %+v", result)
	}
	conn.Close()
	if dials != 1 {
		t.Fatalf("recovery dials=%d, want 1", dials)
	}
}

func TestMtProtoCFPoolExhaustionRespectsFallbackPolicy(t *testing.T) {
	for _, fallback := range []bool{false, true} {
		t.Run(fmt.Sprintf("fallback_%t", fallback), func(t *testing.T) {
			withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
				settings.Mode = modeCFFirst
				if !fallback {
					settings.Mode = modeCFOnly
				}
				settings.CF.Enabled = true
				settings.PolicyPresent = true
				settings.AllowCFProxy = true
				settings.AllowDirect = fallback
				settings.AllowTCP = fallback
				settings.AllowFallback = fallback
				return settings
			})
			withTestCFPool(t, nil)
			direct := &fakeOutboundRouteConnector{backend: mtProtoDirectWSBackend, conn: nopConn{}}
			tcp := &fakeOutboundRouteConnector{backend: mtProtoDirectBackend, conn: nopConn{}}
			connector := &mtProtoRouteConnector{cfProxy: newMtProtoCFProxyConnector(), directWS: direct, tcp: tcp}
			conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{DCID: 2})
			if fallback {
				if conn == nil || result.Err != nil || !result.FallbackUsed || result.ActualBackend != mtProtoDirectWSBackend || direct.calls != 1 {
					t.Fatalf("fallback result=%+v calls=%d", result, direct.calls)
				}
				conn.Close()
			} else if conn != nil || result.Reason != "cfproxy_unavailable" || result.FallbackUsed || direct.calls != 0 || tcp.calls != 0 {
				t.Fatalf("CF-only result=%+v direct=%d tcp=%d", result, direct.calls, tcp.calls)
			}
		})
	}
}
