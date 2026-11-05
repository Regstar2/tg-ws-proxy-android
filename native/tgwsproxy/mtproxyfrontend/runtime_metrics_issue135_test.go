package mtproxyfrontend

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

func TestRuntimeStatusExposesLiveBridgeTrafficAndConnectionTime(t *testing.T) {
	clientPeer, clientBridge := net.Pipe()
	upstreamBridge, upstreamPeer := net.Pipe()
	defer clientPeer.Close()
	defer upstreamPeer.Close()

	runtime := NewRuntime(nil)
	runtime.traffic.lastConnectMs.Store(87)
	results := make(chan bridgeResult, 1)
	go func() {
		results <- bridgeTransformedStreams(
			context.Background(),
			&mtProtoTrafficConn{Conn: clientBridge, bytesWritten: &runtime.traffic.bytesDown},
			&mtProtoTrafficConn{Conn: upstreamBridge, bytesWritten: &runtime.traffic.bytesUp},
			nil, nil,
		)
	}()

	up := []byte("client-payload")
	down := []byte("upstream-payload")
	writeAndReadFullForBridgeTest(t, clientPeer, upstreamPeer, up, up)
	waitForLiveTraffic(t, runtime, int64(len(up)), 0)
	status := runtime.Status()
	if status.BytesUp != int64(len(up)) || status.BytesDown != 0 {
		t.Fatalf("live traffic after upload: up=%d down=%d", status.BytesUp, status.BytesDown)
	}

	writeAndReadFullForBridgeTest(t, upstreamPeer, clientPeer, down, down)
	waitForLiveTraffic(t, runtime, int64(len(up)), int64(len(down)))
	status = runtime.Status()
	if status.BytesUp != int64(len(up)) || status.BytesDown != int64(len(down)) {
		t.Fatalf("live duplex counters: up=%d down=%d", status.BytesUp, status.BytesDown)
	}
	if status.LastConnectMs != 87 {
		t.Fatalf("connect_ms=%d, want 87", status.LastConnectMs)
	}
	if !strings.Contains(runtime.StatusString(), fmt.Sprintf("bytes_up=%d;bytes_down=%d;connect_ms=87", len(up), len(down))) {
		t.Fatalf("missing metrics in native status: %s", runtime.StatusString())
	}

	_ = clientPeer.Close()
	select {
	case result := <-results:
		if result.Err != nil {
			t.Fatalf("bridge ended with error: %v", result.Err)
		}
		if result.UpBytes != int64(len(up)) || result.DownBytes != int64(len(down)) {
			t.Fatalf("bridge counts differ from live counters: %+v", result)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("bridge did not close")
	}
}

func TestOldRuntimeTrafficCountersCannotLeakIntoNextGeneration(t *testing.T) {
	runtime := NewRuntime(nil)
	old := runtime.traffic
	old.bytesUp.Add(1024)
	runtime.mu.Lock()
	runtime.traffic = &mtProtoTrafficCounters{}
	runtime.mu.Unlock()
	old.bytesUp.Add(512)
	old.bytesDown.Add(256)
	if got := runtime.Status(); got.BytesUp != 0 || got.BytesDown != 0 || got.LastConnectMs != 0 {
		t.Fatalf("previous runtime traffic leaked: %+v", got)
	}
}

func TestTrafficWrapperCountsOnlyBytesAcceptedByConnection(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	counter := &mtProtoTrafficCounters{}
	wrapped := &mtProtoTrafficConn{Conn: left, bytesWritten: &counter.bytesUp}
	payload := []byte("accepted")
	result := make(chan error, 1)
	go func() {
		_, err := wrapped.Write(payload)
		result <- err
	}()
	got := make([]byte, len(payload))
	if _, err := right.Read(got); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) || counter.bytesUp.Load() != int64(len(payload)) {
		t.Fatalf("payload=%q counter=%d", got, counter.bytesUp.Load())
	}
}

func waitForLiveTraffic(t *testing.T, runtime *Runtime, up, down int64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		status := runtime.Status()
		if status.BytesUp == up && status.BytesDown == down {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("live counters did not reach up=%d down=%d: got up=%d down=%d", up, down, status.BytesUp, status.BytesDown)
		}
		time.Sleep(time.Millisecond)
	}
}
