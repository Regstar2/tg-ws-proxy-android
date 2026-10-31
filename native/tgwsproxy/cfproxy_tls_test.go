package main

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"reflect"
	"testing"
)

func TestCFProxyTLSClientHelloUsesClassicalGroups(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	got := make(chan []tls.CurveID, 1)
	serverTLS := tls.Server(server, &tls.Config{GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
		got <- append([]tls.CurveID(nil), hello.SupportedCurves...)
		return nil, errors.New("captured ClientHello")
	}})
	go serverTLS.Handshake()
	config := webSocketTLSConfig("example.test", cfProxyTLSCurves())
	_ = tls.Client(client, config).Handshake()
	curves := <-got
	if !reflect.DeepEqual(curves, []tls.CurveID{tls.X25519, tls.CurveP256}) {
		t.Fatalf("CF ClientHello groups=%v", curves)
	}
	if webSocketTLSConfig("example.test", nil).CurvePreferences != nil {
		t.Fatal("other WebSocket routes must retain Go's default groups")
	}
}

func TestCFProxyTLSFallbackIsBoundedPerDomain(t *testing.T) {
	primaryErr := &wsStageError{Stage: "tls_handshake", Err: errors.New("handshake timeout")}
	var calls [][]tls.CurveID
	d := cfProxyTLSDialer{dial: func(_, _, _ string, _ float64, curves []tls.CurveID) (*RawWebSocket, error) {
		calls = append(calls, curves)
		if curves == nil {
			return nil, &wsStageError{Stage: "tcp_dial", Err: errors.New("offline")}
		}
		return nil, primaryErr
	}}
	for _, ip := range []string{"first", "second", "third"} {
		if _, err := d.connect(ip, "example.test", "/apiws", 1); !errors.Is(err, primaryErr) {
			t.Fatalf("lost original failure: %v", err)
		}
	}
	if len(calls) != 4 || calls[0] == nil || calls[1] != nil || calls[2] == nil || calls[3] == nil {
		t.Fatalf("unexpected TLS profile sequence: %v", calls)
	}
}

func TestCFProxyTLSFallbackSuccess(t *testing.T) {
	socket := &RawWebSocket{}
	count := 0
	d := cfProxyTLSDialer{dial: func(_, _, _ string, _ float64, curves []tls.CurveID) (*RawWebSocket, error) {
		count++
		if curves != nil {
			return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("handshake failed")}
		}
		return socket, nil
	}}
	got, err := d.connect("target", "example.test", "/apiws", 1)
	if err != nil || got != socket || count != 2 {
		t.Fatalf("socket=%v err=%v calls=%d", got, err, count)
	}
}

func TestCFProxyTLSDoesNotRetryNonTLSFailures(t *testing.T) {
	for _, failure := range []error{
		&wsStageError{Stage: "tcp_dial", Err: errors.New("offline")},
		&wsStageError{Stage: "ws_upgrade_read", Err: errors.New("timeout")},
		&wsStageError{Stage: "tls_handshake", Err: context.Canceled},
		&WsHandshakeError{StatusCode: 503},
	} {
		calls := 0
		d := cfProxyTLSDialer{dial: func(_, _, _ string, _ float64, _ []tls.CurveID) (*RawWebSocket, error) {
			calls++
			return nil, failure
		}}
		_, err := d.connect("target", "example.test", "/apiws", 1)
		if !errors.Is(err, failure) || calls != 1 {
			t.Fatalf("failure=%v err=%v calls=%d", failure, err, calls)
		}
	}
}
