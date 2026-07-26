package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"testing"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestMtProtoDirectConnectorWritesRelayInit(t *testing.T) {
	outbound, remote := net.Pipe()
	defer remote.Close()
	relayInit := bytes.Repeat([]byte{0x5a}, 64)
	received := make(chan []byte, 1)
	go func() {
		data := make([]byte, len(relayInit))
		_, _ = io.ReadFull(remote, data)
		received <- data
	}()

	var dialAddress string
	connector := &mtProtoDirectConnector{
		resolveTarget: func(dc int) (string, int, bool) {
			if dc != 2 {
				t.Errorf("dc=%d", dc)
			}
			return "149.154.167.51", 443, true
		},
		dialContext: func(_ context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" {
				t.Errorf("network=%s", network)
			}
			dialAddress = address
			return outbound, nil
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportPaddedIntermediate,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if dialAddress != "149.154.167.51:443" {
		t.Fatalf("address=%s", dialAddress)
	}
	if result.SelectedBackend != mtProtoDirectBackend ||
		result.ActualBackend != mtProtoDirectBackend ||
		result.FallbackUsed ||
		result.Reason != "connected" {
		t.Fatalf("route truth=%+v", result)
	}
	if got := <-received; !bytes.Equal(got, relayInit) {
		t.Fatal("relay init mismatch")
	}
}

func TestMtProtoDirectConnectorHasNoSilentFallback(t *testing.T) {
	dialErr := errors.New("network unavailable")
	connector := &mtProtoDirectConnector{
		resolveTarget: func(int) (string, int, bool) {
			return "149.154.167.51", 443, true
		},
		dialContext: func(context.Context, string, string) (net.Conn, error) {
			return nil, dialErr
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportPaddedIntermediate,
		RelayInit: make([]byte, 64),
	})
	if conn != nil {
		t.Fatal("unexpected connection")
	}
	if !errors.Is(result.Err, dialErr) {
		t.Fatalf("err=%v", result.Err)
	}
	if result.ActualBackend != "" || result.FallbackUsed {
		t.Fatalf("unexpected fallback route truth=%+v", result)
	}
	if result.Reason != "direct_tcp_connect_failed" {
		t.Fatalf("reason=%s", result.Reason)
	}
}

func TestMtProtoDirectConnectorUsesTestDCTarget(t *testing.T) {
	outbound, remote := net.Pipe()
	defer remote.Close()
	received := make(chan struct{}, 1)
	go func() {
		_, _ = io.CopyN(io.Discard, remote, 64)
		received <- struct{}{}
	}()

	var dialAddress string
	connector := &mtProtoDirectConnector{
		resolveTarget: func(int) (string, int, bool) {
			t.Fatal("production target resolver must not be used for test DC")
			return "", 0, false
		},
		dialContext: func(_ context.Context, _ string, address string) (net.Conn, error) {
			dialAddress = address
			return outbound, nil
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		IsTestDC:  true,
		Transport: mtproxyfrontend.TransportPaddedIntermediate,
		RelayInit: make([]byte, 64),
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if dialAddress != "149.154.167.40:443" {
		t.Fatalf("address=%s", dialAddress)
	}
	<-received
}

func TestMtProtoDirectConnectorCapability(t *testing.T) {
	capability := newMtProtoDirectConnector().Capability()
	if capability.Status != mtProtoRouteDirectReady {
		t.Fatalf("status=%s", capability.Status)
	}
	if capability.SelectedBackend != mtProtoDirectBackend {
		t.Fatalf("backend=%s", capability.SelectedBackend)
	}
}

var _ mtproxyfrontend.OutboundConnector = (*mtProtoDirectConnector)(nil)
