package mtproxyfrontend

import (
	"net"
	"strconv"
	"testing"
	"time"
)

func TestFrontendStopClosesPendingHandshakeAndRejectsLateState(t *testing.T) {
	port := reservePort(t)
	runtime := NewRuntime(nil)
	config := Config{Host: "127.0.0.1", Port: port, Secret: testSecret}
	if result := runtime.Start(config); result.Err != nil {
		t.Fatalf("initial start: %v", result.Err)
	}
	defer func() { _ = runtime.Stop() }()

	client, err := net.Dial("tcp", net.JoinHostPort(config.Host, strconv.Itoa(port)))
	if err != nil {
		t.Fatalf("connect pending handshake: %v", err)
	}
	defer client.Close()
	runtime.mu.Lock()
	oldRun := runtime.runCtx
	runtime.mu.Unlock()

	deadline := time.Now().Add(time.Second)
	for runtime.Status().ActiveConnections == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if runtime.Status().ActiveConnections != 1 {
		t.Fatal("pending frontend handshake was not accepted")
	}
	if err := runtime.Stop(); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := client.Read(make([]byte, 1)); err == nil {
		t.Fatal("stopped frontend kept pending client connected")
	}

	if result := runtime.Start(config); result.Err != nil {
		t.Fatalf("restart with new route policy: %v", result.Err)
	}
	runtime.updateRouteTruthFor(oldRun, OutboundResult{
		SelectedBackend: "cf_proxy_ws",
		Reason: "tls_handshake_timeout",
	})
	if got := runtime.Status(); got.RouteReason != "awaiting_connection" ||
		got.SelectedBackend != "" || got.ActiveConnections != 0 {
		t.Fatalf("old session contaminated new frontend: %+v", got)
	}
}
