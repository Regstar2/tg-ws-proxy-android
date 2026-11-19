package main

import (
	"context"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func TestCFProxyPlainWebSocketUsesPort80AndValidatesUpgrade(t *testing.T) {
	for _, valid := range []bool{true, false} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Host != "kws4.pool.example" || r.URL.Path != "/apiws" || r.Header.Get("Sec-WebSocket-Protocol") != "binary" {
				t.Errorf("unexpected request: %s %s %v", r.Host, r.URL.Path, r.Header)
			}
			w.Header().Set("Upgrade", "websocket")
			w.Header().Set("Connection", "Upgrade")
			if valid {
				accept := sha1.Sum([]byte(r.Header.Get("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
				w.Header().Set("Sec-WebSocket-Accept", base64.StdEncoding.EncodeToString(accept[:]))
			}
			w.WriteHeader(http.StatusSwitchingProtocols)
		}))
		ws, err := wsConnectPlainContextDial(context.Background(), "203.0.113.4", "kws4.pool.example", "/apiws", 2,
			func(ctx context.Context, network, address string) (net.Conn, error) {
				if network != "tcp" || address != "203.0.113.4:80" {
					t.Errorf("unexpected dial: %s %s", network, address)
				}
				return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
			})
		if ws != nil {
			ws.Close()
		}
		server.Close()
		if (ws != nil) != valid || (err == nil) != valid {
			t.Fatalf("valid upgrade=%t, got connection=%t, err=%v", valid, ws != nil, err)
		}
	}
}

func TestCFProxyPlainWebSocketRequiresExplicitConfig(t *testing.T) {
	for _, tc := range []struct {
		raw  string
		want bool
	}{
		{"", false}, {"@cfproxy_no_tls=0", false}, {"@cfproxy_no_tls=1", true},
	} {
		_, settings, err := parseRuntimeConfig(tc.raw)
		if err != nil || settings.CF.NoTLS != tc.want {
			t.Fatalf("parse %q: got NoTLS=%t, err=%v", tc.raw, settings.CF.NoTLS, err)
		}
	}
}

func TestWebSocketTLSNameIsIndependentOfHTTPHost(t *testing.T) {
	cases := []struct {
		name, dialIP, host, serverName string
	}{
		{"cf_dc2_hostname", "kws2.pool.example", "kws2.pool.example", "pool.example"},
		{"cf_dc4_ip_fallback", "203.0.113.4", "kws4.pool.example", "pool.example"},
		{"direct_ws", "149.154.167.220", "kws2.web.telegram.org", ""},
		{"worker_ws", "worker.example", "worker.example", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			wantSNI := tc.serverName
			if wantSNI == "" {
				wantSNI = tc.host
			}
			server, roots, observed := newNativeVerifiedWSTestServer(t, []string{wantSNI}, false)

			ws, err := wsConnectWithCurvesContextDialSNIWithRoots(
				context.Background(), tc.dialIP, tc.host, tc.serverName, "/apiws", 2,
				classicalTLSCurves(),
				func(ctx context.Context, network, address string) (net.Conn, error) {
					if network != "tcp" || address != net.JoinHostPort(tc.dialIP, "443") {
						t.Errorf("unexpected dial: %s %s", network, address)
					}
					return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
				}, roots,
			)
			if err != nil {
				t.Fatal(err)
			}
			defer ws.Close()

			select {
			case got := <-observed:
				if got != ([3]string{wantSNI, tc.host, "/apiws"}) {
					t.Fatalf("SNI/Host/path = %v, want %v", got, [3]string{wantSNI, tc.host, "/apiws"})
				}
			default:
				t.Fatal("WebSocket upgrade request did not reach server")
			}
		})
	}
}

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
	config := webSocketTLSConfig("example.test", classicalTLSCurves())
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
	d := compatibleWebSocketTLSDialer{dial: func(_, _, _ string, _ float64, curves []tls.CurveID) (*RawWebSocket, error) {
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
	d := compatibleWebSocketTLSDialer{dial: func(_, _, _ string, _ float64, curves []tls.CurveID) (*RawWebSocket, error) {
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
		&WsHandshakeError{StatusCode: 403},
		&WsHandshakeError{StatusCode: 421},
		&WsHandshakeError{StatusCode: 1013},
		&WsHandshakeError{StatusCode: 503},
	} {
		calls := 0
		d := compatibleWebSocketTLSDialer{dial: func(_, _, _ string, _ float64, _ []tls.CurveID) (*RawWebSocket, error) {
			calls++
			return nil, failure
		}}
		_, err := d.connect("target", "example.test", "/apiws", 1)
		if !errors.Is(err, failure) || calls != 1 {
			t.Fatalf("failure=%v err=%v calls=%d", failure, err, calls)
		}
	}
}

func TestCFProxyTLSDoesNotStartFallbackAfterCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	d := compatibleWebSocketTLSDialer{ctx: ctx, dial: func(_, _, _ string, _ float64, _ []tls.CurveID) (*RawWebSocket, error) {
		calls++
		cancel()
		return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("handshake timeout")}
	}}
	_, err := d.connect("target", "example.test", "/apiws", 1)
	if err == nil || calls != 1 {
		t.Fatalf("err=%v calls=%d", err, calls)
	}
}
