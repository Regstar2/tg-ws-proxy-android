package main

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"

	"tg-ws-proxy/mtproxyfrontend"
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
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, result := connector.Connect(context.Background(), request)
			if result.Reason != "cfproxy_unavailable" {
				t.Errorf("reason=%s, want cfproxy_unavailable", result.Reason)
			}
		}()
	}
	wg.Wait()
	close(release)
	<-done
	if got := dials.Load(); got != 1 {
		t.Fatalf("dials=%d, want 1", got)
	}
}
