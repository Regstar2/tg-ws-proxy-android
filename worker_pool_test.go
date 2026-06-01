package main

import (
	"bufio"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

type nopConn struct{}

func (nopConn) Read(_ []byte) (int, error)       { return 0, nil }
func (nopConn) Write(p []byte) (int, error)      { return len(p), nil }
func (nopConn) Close() error                     { return nil }
func (nopConn) LocalAddr() net.Addr              { return dummyAddr("local") }
func (nopConn) RemoteAddr() net.Addr             { return dummyAddr("remote") }
func (nopConn) SetDeadline(time.Time) error      { return nil }
func (nopConn) SetReadDeadline(time.Time) error  { return nil }
func (nopConn) SetWriteDeadline(time.Time) error { return nil }

type dummyAddr string

func (a dummyAddr) Network() string { return string(a) }
func (a dummyAddr) String() string  { return string(a) }

type fakeWorkerDialer struct {
	count atomic.Int64
	fail  bool
}

func (d *fakeWorkerDialer) DialWorker(_ WorkerPoolKey) (*RawWebSocket, error) {
	d.count.Add(1)
	if d.fail {
		return nil, errFakeDial
	}
	return newFakeWebSocket(), nil
}

var errFakeDial = &net.DNSError{Err: "fake dial failed", Name: "worker.test"}

func newFakeWebSocket() *RawWebSocket {
	return &RawWebSocket{
		conn:      nopConn{},
		bufReader: bufio.NewReader(nopConn{}),
	}
}

func withPoolSize(t *testing.T, size int) {
	t.Helper()
	previous := poolSize
	poolSize = size
	t.Cleanup(func() { poolSize = previous })
}

func testWorkerKey() WorkerPoolKey {
	return WorkerPoolKey{DC: 2, WorkerDomain: "worker.example", Dst: "149.154.167.51", Media: false}
}

func TestWorkerPoolGetMissSchedulesRefill(t *testing.T) {
	withPoolSize(t, 1)
	stats.Reset()
	dialer := &fakeWorkerDialer{}
	pool := newWorkerWsPool(dialer)

	if got := pool.Get(testWorkerKey()); got != nil {
		t.Fatal("first get should miss")
	}

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if dialer.count.Load() > 0 && pool.IdleCount() > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("refill was not scheduled, dialed=%d idle=%d", dialer.count.Load(), pool.IdleCount())
}

func TestWorkerPoolHitReusesIdleConnection(t *testing.T) {
	withPoolSize(t, 1)
	stats.Reset()
	pool := newWorkerWsPool(&fakeWorkerDialer{})
	key := testWorkerKey()
	ws := newFakeWebSocket()
	pool.idle[key] = []poolEntry{{ws: ws, created: pool.now()}}

	if got := pool.Get(key); got != ws {
		t.Fatal("expected idle websocket to be reused")
	}
	if stats.workerPoolHits.Load() != 1 {
		t.Fatalf("worker pool hit counter = %d, want 1", stats.workerPoolHits.Load())
	}
}

func TestWorkerPoolDropsExpiredConnection(t *testing.T) {
	withPoolSize(t, 1)
	stats.Reset()
	pool := newWorkerWsPool(&fakeWorkerDialer{fail: true})
	pool.maxAge = 1
	now := 10.0
	pool.now = func() float64 { return now }
	key := testWorkerKey()
	expired := newFakeWebSocket()
	expired.closed.Store(true)
	pool.idle[key] = []poolEntry{{ws: expired, created: 0}}

	if got := pool.Get(key); got != nil {
		t.Fatal("expired websocket should not be returned")
	}
	if stats.workerPoolHits.Load() != 0 {
		t.Fatalf("worker pool hit counter = %d, want 0", stats.workerPoolHits.Load())
	}
}

func TestWorkerPoolResetClearsIdle(t *testing.T) {
	pool := newWorkerWsPool(&fakeWorkerDialer{})
	key := testWorkerKey()
	pool.idle[key] = []poolEntry{{ws: newFakeWebSocket(), created: pool.now()}}

	pool.CloseAll()

	if got := pool.IdleCount(); got != 0 {
		t.Fatalf("idle count = %d, want 0", got)
	}
}

func TestWorkerPoolDoesNotRefillWhenPoolSizeZero(t *testing.T) {
	withPoolSize(t, 0)
	dialer := &fakeWorkerDialer{}
	pool := newWorkerWsPool(dialer)

	if got := pool.Get(testWorkerKey()); got != nil {
		t.Fatal("pool disabled should return nil")
	}
	time.Sleep(30 * time.Millisecond)
	if dialer.count.Load() != 0 {
		t.Fatalf("dial count = %d, want 0", dialer.count.Load())
	}
}

func TestWorkerPoolDoesNotWarmupWhenWorkerRouteDisabled(t *testing.T) {
	settings := runtimeSettings{
		Mode: modeCFOnly,
		Worker: workerConfig{
			Enabled: true,
			Domain:  "worker.example",
		},
		CF: cfProxyConfig{Enabled: true, Only: true},
	}

	if settings.workerRouteAvailable() {
		t.Fatal("worker route should be unavailable for strict CF-only mode")
	}
}
