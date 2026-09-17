package main

import (
	"context"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestMtProtoAWGWarpConnectorAllowsConcurrentDialEstablishment(t *testing.T) {
	var active atomic.Int32
	var maxActive atomic.Int32

	connector := &mtProtoAWGWarpConnector{
		dialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			current := active.Add(1)
			defer active.Add(-1)
			for {
				observed := maxActive.Load()
				if current <= observed || maxActive.CompareAndSwap(observed, current) {
					break
				}
			}
			select {
			case <-time.After(20 * time.Millisecond):
				return &awgRouteTestConn{}, nil
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		},
		connectTimeout:   time.Second,
		relayInitTimeout: time.Second,
	}

	request := mtproxyfrontend.OutboundRequest{
		DCID:      2,
		SignedDC:  2,
		RelayInit: make([]byte, 64),
	}

	const count = 4
	start := make(chan struct{})
	results := make(chan mtproxyfrontend.OutboundResult, count)
	var wg sync.WaitGroup
	wg.Add(count)
	for i := 0; i < count; i++ {
		go func() {
			defer wg.Done()
			<-start
			conn, result := connector.Connect(context.Background(), request)
			if conn != nil {
				_ = conn.Close()
			}
			results <- result
		}()
	}
	close(start)
	wg.Wait()
	close(results)

	for result := range results {
		if result.Err != nil {
			t.Fatalf("connect failed: %v", result.Err)
		}
		if result.ActualBackend != mtProtoAWGWarpBackend || result.Reason != "connected" {
			t.Fatalf("unexpected result: backend=%q reason=%q", result.ActualBackend, result.Reason)
		}
	}
	if got := maxActive.Load(); got < 2 {
		t.Fatalf("concurrent dial attempts = %d, want at least 2", got)
	}
}

func TestMtProtoAWGWarpConnectorBoundsHungDial(t *testing.T) {
	connector := &mtProtoAWGWarpConnector{
		dialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		},
		connectTimeout:   25 * time.Millisecond,
		relayInitTimeout: time.Second,
	}

	started := time.Now()
	_, result := connector.Connect(
		context.Background(),
		mtproxyfrontend.OutboundRequest{DCID: 2, SignedDC: 2, RelayInit: make([]byte, 64)},
	)
	elapsed := time.Since(started)

	if result.Err == nil {
		t.Fatal("expected timed out AWG/WARP dial")
	}
	if result.Reason != "awg_warp_connect_failed" {
		t.Fatalf("reason = %q, want awg_warp_connect_failed", result.Reason)
	}
	if !strings.Contains(result.Err.Error(), "context deadline exceeded") {
		t.Fatalf("unexpected error: %v", result.Err)
	}
	if elapsed > 500*time.Millisecond {
		t.Fatalf("hung dial was not bounded: elapsed=%s", elapsed)
	}
}

func TestMtProtoAWGWarpConnectorAppliesRelayInitWriteDeadline(t *testing.T) {
	conn := &awgRouteTestConn{}
	connector := &mtProtoAWGWarpConnector{
		dialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		connectTimeout:   time.Second,
		relayInitTimeout: 50 * time.Millisecond,
	}

	outbound, result := connector.Connect(
		context.Background(),
		mtproxyfrontend.OutboundRequest{DCID: 2, SignedDC: 2, RelayInit: make([]byte, 64)},
	)
	if result.Err != nil {
		t.Fatalf("connect failed: %v", result.Err)
	}
	if outbound == nil {
		t.Fatal("expected outbound connection")
	}
	_ = outbound.Close()

	if conn.nonZeroWriteDeadlines.Load() != 1 {
		t.Fatalf("non-zero write deadlines = %d, want 1", conn.nonZeroWriteDeadlines.Load())
	}
	if conn.clearedWriteDeadlines.Load() != 1 {
		t.Fatalf("cleared write deadlines = %d, want 1", conn.clearedWriteDeadlines.Load())
	}
}

type awgRouteTestConn struct {
	nonZeroWriteDeadlines atomic.Int32
	clearedWriteDeadlines atomic.Int32
}

func (c *awgRouteTestConn) Read([]byte) (int, error) { return 0, io.EOF }
func (c *awgRouteTestConn) Write(p []byte) (int, error) { return len(p), nil }
func (c *awgRouteTestConn) Close() error { return nil }
func (c *awgRouteTestConn) LocalAddr() net.Addr { return awgRouteTestAddr("local") }
func (c *awgRouteTestConn) RemoteAddr() net.Addr { return awgRouteTestAddr("remote") }
func (c *awgRouteTestConn) SetDeadline(time.Time) error { return nil }
func (c *awgRouteTestConn) SetReadDeadline(time.Time) error { return nil }
func (c *awgRouteTestConn) SetWriteDeadline(deadline time.Time) error {
	if deadline.IsZero() {
		c.clearedWriteDeadlines.Add(1)
	} else {
		c.nonZeroWriteDeadlines.Add(1)
	}
	return nil
}

type awgRouteTestAddr string

func (a awgRouteTestAddr) Network() string { return "test" }
func (a awgRouteTestAddr) String() string { return string(a) }