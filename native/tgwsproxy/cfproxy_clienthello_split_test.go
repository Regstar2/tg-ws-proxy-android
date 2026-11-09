package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type cfClientHelloRecordingConn struct {
	net.Conn
	writes   [][]byte
	failAt   int
	limit    int
	writeErr error
}

func (c *cfClientHelloRecordingConn) Write(p []byte) (int, error) {
	index := len(c.writes)
	n := len(p)
	var err error
	if index == c.failAt {
		if c.limit >= 0 && c.limit < n {
			n = c.limit
		}
		err = c.writeErr
	}
	c.writes = append(c.writes, append([]byte(nil), p[:n]...))
	return n, err
}

func cfClientHelloTestRecord(t *testing.T, hostname string) []byte {
	t.Helper()
	client, server := net.Pipe()
	captured := make(chan []byte, 1)
	go func() {
		defer server.Close()
		buffer := make([]byte, 16384)
		n, _ := server.Read(buffer)
		captured <- append([]byte(nil), buffer[:n]...)
	}()
	config := &tls.Config{
		ServerName:         hostname,
		InsecureSkipVerify: true, // Test-only: capture ClientHello without a TLS server.
		CurvePreferences:   classicalTLSCurves(),
	}
	tlsClient := tls.Client(client, config)
	_ = tlsClient.Handshake()
	_ = tlsClient.Close()
	record := <-captured
	if len(record) < 9 {
		t.Fatalf("captured TLS record is too short: %d", len(record))
	}
	return record
}

func TestCFClientHelloTCPSplitPreservesRecordAndApplicationWrites(t *testing.T) {
	hello := cfClientHelloTestRecord(t, "example.test")
	offset, ok := cfClientHelloSNISplitOffset(hello)
	if !ok || offset <= 9 || offset >= len(hello) {
		t.Fatalf("invalid SNI split offset %d, found=%t", offset, ok)
	}
	recorder := &cfClientHelloRecordingConn{failAt: -1}
	conn := newCFClientHelloTCPWriteSplitter(recorder)
	n, err := conn.Write(hello)
	if err != nil || n != len(hello) || len(recorder.writes) != 2 {
		t.Fatalf("TLS write n=%d err=%v parts=%d", n, err, len(recorder.writes))
	}
	if len(recorder.writes[0]) != offset ||
		!bytes.Equal(bytes.Join(recorder.writes, nil), hello) {
		t.Fatal("split modified TLS ClientHello bytes")
	}
	applicationRecord := []byte{23, 3, 3, 0, 3, 9, 8, 7}
	n, err = conn.Write(applicationRecord)
	if err != nil || n != len(applicationRecord) || len(recorder.writes) != 3 ||
		!bytes.Equal(recorder.writes[2], applicationRecord) {
		t.Fatalf("later TLS application write was modified: n=%d err=%v", n, err)
	}
}

func TestCFClientHelloTCPSplitLeavesUnrecognizedRecordsUntouched(t *testing.T) {
	valid := cfClientHelloTestRecord(t, "example.test")
	noSNI := cfClientHelloTestRecord(t, "127.0.0.1")
	badHandshake := bytes.Clone(valid)
	badHandshake[5] = 2
	trailing := append(bytes.Clone(valid), 0)
	malformed := bytes.Clone(valid)
	malformed[9+2+32] = 255
	for name, data := range map[string][]byte{
		"empty":         nil,
		"truncated":     valid[:8],
		"no-sni":        noSNI,
		"application":   {23, 3, 3, 0, 1, 0},
		"other-handshake": badHandshake,
		"extra-record":  trailing,
		"bad-session":   malformed,
	} {
		t.Run(name, func(t *testing.T) {
			if _, ok := cfClientHelloSNISplitOffset(data); ok {
				t.Fatal("unexpected split for an unsupported record")
			}
			recorder := &cfClientHelloRecordingConn{failAt: -1}
			conn := newCFClientHelloTCPWriteSplitter(recorder)
			n, err := conn.Write(data)
			if err != nil || n != len(data) || len(recorder.writes) != 1 ||
				!bytes.Equal(recorder.writes[0], data) {
				t.Fatalf("unexpected passthrough n=%d err=%v", n, err)
			}
		})
	}
}

func TestCFClientHelloTCPSplitPropagatesShortWritesAndErrors(t *testing.T) {
	hello := cfClientHelloTestRecord(t, "example.test")
	split, ok := cfClientHelloSNISplitOffset(hello)
	if !ok {
		t.Fatal("missing SNI split point")
	}
	sentinel := errors.New("transport failed")
	cases := []struct {
		name     string
		failAt   int
		limit    int
		err      error
		wantN    int
		wantErr  error
		wantCall int
	}{
		{"first short", 0, 3, nil, 3, io.ErrShortWrite, 1},
		{"first error", 0, 4, sentinel, 4, sentinel, 1},
		{"second short", 1, 3, nil, split + 3, io.ErrShortWrite, 2},
		{"second error", 1, 4, sentinel, split + 4, sentinel, 2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			recorder := &cfClientHelloRecordingConn{failAt: tc.failAt, limit: tc.limit, writeErr: tc.err}
			n, err := newCFClientHelloTCPWriteSplitter(recorder).Write(hello)
			if n != tc.wantN || !errors.Is(err, tc.wantErr) || len(recorder.writes) != tc.wantCall {
				t.Fatalf("n=%d err=%v calls=%d", n, err, len(recorder.writes))
			}
		})
	}
}

func TestCFClientHelloTCPSplitRetainsDeadlinesAndClose(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()
	conn := newCFClientHelloTCPWriteSplitter(client)
	if err := conn.SetWriteDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	_, err := conn.Write(cfClientHelloTestRecord(t, "example.test"))
	var netErr net.Error
	if !errors.As(err, &netErr) || !netErr.Timeout() {
		t.Fatalf("write deadline not respected: %v", err)
	}
	_ = conn.Close()
	if _, err := conn.Write([]byte{1}); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("closed connection allowed write: %v", err)
	}
}

func TestCFClientHelloTCPSplitCancellationClosesUnderlyingConnection(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()
	conn := newCFClientHelloTCPWriteSplitter(client)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	hello := cfClientHelloTestRecord(t, "example.test")
	wrote := make(chan error, 1)
	go func() {
		_, err := conn.Write(hello)
		wrote <- err
	}()
	cancel()
	select {
	case err := <-wrote:
		if err == nil {
			t.Fatal("cancelled connection write unexpectedly succeeded")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancellation did not interrupt blocked TCP write")
	}
}

func TestCFClientHelloTCPSplitStrictTLSAndWebSocketUpgrade(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/apiws" || r.Header.Get("Upgrade") != "websocket" {
			http.Error(w, "unsupported endpoint", http.StatusBadRequest)
			return
		}
		w.Header().Set("Connection", "Upgrade")
		w.Header().Set("Upgrade", "websocket")
		w.WriteHeader(http.StatusSwitchingProtocols)
	}))
	defer server.Close()

	roots := x509.NewCertPool()
	certificate := server.Certificate()
	roots.AddCert(certificate)
	serverName := "127.0.0.1"
	if len(certificate.IPAddresses) == 0 {
		if len(certificate.DNSNames) == 0 {
			t.Fatal("test server has no usable certificate SAN")
		}
		serverName = certificate.DNSNames[0]
	} else {
		serverName = certificate.IPAddresses[0].String()
	}
	for _, split := range []bool{false, true} {
		t.Run(fmt.Sprintf("split=%t", split), func(t *testing.T) {
			raw, err := net.DialTimeout("tcp", server.Listener.Addr().String(), 2*time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer raw.Close()
			var conn net.Conn = raw
			if split {
				conn = newCFClientHelloTCPWriteSplitter(conn)
			}
			clientTLS := tls.Client(conn, &tls.Config{ServerName: serverName, RootCAs: roots})
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			if err := clientTLS.HandshakeContext(ctx); err != nil {
				t.Fatalf("strict TLS handshake: %v", err)
			}
			defer clientTLS.Close()
			req, err := http.NewRequest(http.MethodGet, "https://"+serverName+"/apiws", nil)
			if err != nil {
				t.Fatal(err)
			}
			req.Header.Set("Connection", "Upgrade")
			req.Header.Set("Upgrade", "websocket")
			req.Header.Set("Sec-WebSocket-Version", "13")
			req.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
			if err := req.Write(clientTLS); err != nil {
				t.Fatal(err)
			}
			response, err := http.ReadResponse(bufio.NewReader(clientTLS), req)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != http.StatusSwitchingProtocols {
				t.Fatalf("WebSocket upgrade status=%d", response.StatusCode)
			}
		})
	}
}

func TestCFClientHelloTCPSplitRejectsUntrustedServer(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer server.Close()
	raw, err := net.DialTimeout("tcp", server.Listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	clientTLS := tls.Client(newCFClientHelloTCPWriteSplitter(raw), &tls.Config{
		ServerName: "127.0.0.1",
		RootCAs:    x509.NewCertPool(),
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := clientTLS.HandshakeContext(ctx); err == nil {
		t.Fatal("untrusted certificate was accepted")
	}
}
