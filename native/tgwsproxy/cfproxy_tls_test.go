package main

import (
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
