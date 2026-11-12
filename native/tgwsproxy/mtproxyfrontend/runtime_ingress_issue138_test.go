package mtproxyfrontend

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

type issue138LogCapture struct {
	mu    sync.Mutex
	lines []string
}

func (l *issue138LogCapture) Printf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, fmt.Sprintf(format, args...))
}

func (l *issue138LogCapture) rejectionLines() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	var lines []string
	for _, line := range l.lines {
		if strings.Contains(line, "reason=handshake_failed") {
			lines = append(lines, line)
		}
	}
	return lines
}

func TestIssue138UnexpectedIngressClassification(t *testing.T) {
	cases := []struct {
		name   string
		prefix []byte
		want   string
	}{
		{"plain HTTP", []byte("GET /"), "plain_http"},
		{"malformed TLS", []byte{0x16, 0x03, 0x03, 0, 1}, "malformed_tls_record"},
		{"binary", []byte{0x7f, 0x7f, 0x7f, 0x7f, 0x7f}, "unrecognized_binary"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := classifyUnexpectedFakeTLSIngress(tc.prefix); got != tc.want {
				t.Fatalf("ingress=%s, want %s", got, tc.want)
			}
		})
	}
}

func TestIssue138MalformedHandshakeStormPreservesConfirmedAWG(t *testing.T) {
	logs := &issue138LogCapture{}
	runtime := NewRuntime(logs, fakeOutboundConnector{})
	port := reservePort(t)
	if result := runtime.Start(Config{
		Host: "127.0.0.1", Port: port, Secret: testSecret,
		FakeTLSDomain: "www.example.com",
	}); result.Err != nil {
		t.Fatalf("start: %v", result.Err)
	}
	defer runtime.Stop()

	runtime.mu.Lock()
	ctx := runtime.runCtx
	runtime.mu.Unlock()
	runtime.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 2, SelectedBackend: "direct_tcp", ActualBackend: "awg_warp",
		FallbackUsed: true, Reason: "connected",
	})

	address := net.JoinHostPort("127.0.0.1", fmt.Sprint(port))
	invalid := bytes.Repeat([]byte{0x7f}, 64)
	const attempts = 256
	for i := 0; i < attempts; i++ {
		conn, err := net.DialTimeout("tcp", address, time.Second)
		if err != nil {
			t.Fatalf("dial attempt %d: %v", i, err)
		}
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err = conn.Write(invalid); err != nil {
			_ = conn.Close()
			t.Fatalf("write attempt %d: %v", i, err)
		}
		response, err := io.ReadAll(conn)
		_ = conn.Close()
		if err != nil || !bytes.Contains(response, []byte("HTTP/1.1 301")) {
			t.Fatalf("attempt %d: redirect=%q error=%v", i, response, err)
		}
	}

	status := runtime.Status()
	if status.HandshakeRejected != attempts || status.FakeTLSRedirected != attempts {
		t.Fatalf("rejected=%d redirected=%d, want %d", status.HandshakeRejected, status.FakeTLSRedirected, attempts)
	}
	if status.ActualBackend != "awg_warp" || !status.FallbackUsed ||
		status.RouteReason != "connected" || status.LastError != "" {
		t.Fatalf("malformed local input corrupted route truth: %+v", status)
	}
	if !strings.Contains(runtime.StatusString(), "handshake_rejected=256") {
		t.Fatalf("status misses cumulative counter: %s", runtime.StatusString())
	}
	lines := logs.rejectionLines()
	if len(lines) > 10 || len(lines) < 2 {
		t.Fatalf("unbounded or missing storm diagnostics: %d entries", len(lines))
	}
	if !strings.Contains(lines[0], "ingress_class=unrecognized_binary") ||
		!strings.Contains(lines[len(lines)-1], "class_count=256") {
		t.Fatalf("missing safe classification/count: first=%q last=%q", lines[0], lines[len(lines)-1])
	}

	// An existing DD link with the same configured secret still authenticates.
	valid, _, _ := buildClientHandshake(t, testSecret, protoPaddedIntermediate, 2)
	conn, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatalf("dial valid DD: %v", err)
	}
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if err := writeFull(conn, valid); err != nil {
		t.Fatalf("write DD: %v", err)
	}
	_ = conn.Close()
}

func TestIssue138ValidEEAfterConcurrentInvalidClientHellos(t *testing.T) {
	logs := &issue138LogCapture{}
	runtime := NewRuntime(logs, fakeOutboundConnector{})
	port := reservePort(t)
	if result := runtime.Start(Config{
		Host: "127.0.0.1", Port: port, Secret: testSecret,
		FakeTLSDomain: "www.example.com",
	}); result.Err != nil {
		t.Fatalf("start: %v", result.Err)
	}
	defer runtime.Stop()
	address := net.JoinHostPort("127.0.0.1", fmt.Sprint(port))

	wrongHello := buildFakeTLSClientHello(t, bytes.Repeat([]byte{0x42}, 16), time.Now())
	const attempts = 64
	var wg sync.WaitGroup
	errorsFound := make(chan error, attempts)
	for i := 0; i < attempts; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := net.DialTimeout("tcp", address, 3*time.Second)
			if err != nil {
				errorsFound <- err
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
			if err := writeFull(conn, wrongHello); err != nil {
				errorsFound <- err
				return
			}
			_, err = io.Copy(io.Discard, conn)
			if err != nil {
				errorsFound <- err
			}
		}()
	}
	wg.Wait()
	close(errorsFound)
	for err := range errorsFound {
		t.Fatalf("concurrent malformed connection failed: %v", err)
	}
	if got := runtime.Status().HandshakeRejected; got != attempts {
		t.Fatalf("rejected=%d, want %d", got, attempts)
	}
	if lines := logs.rejectionLines(); len(lines) > 8 || len(lines) < 2 {
		t.Fatalf("expected logarithmic diagnostic sampling, got %d lines", len(lines))
	}

	client, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatalf("dial valid EE: %v", err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	secret := decodeTestSecret(t, testSecret)
	if err := writeFull(client, buildFakeTLSClientHello(t, secret, time.Now())); err != nil {
		t.Fatalf("write valid hello: %v", err)
	}
	readFakeTLSServerHello(t, client)
	wire, _, _ := buildClientHandshake(t, testSecret, protoPaddedIntermediate, 2)
	if err := writeFull(client, wrapFakeTLSRecords(wire)); err != nil {
		t.Fatalf("write obfuscated init: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for runtime.Status().FakeTLSAccepted < 1 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := runtime.Status(); got.FakeTLSAccepted != 1 || got.HandshakeRejected != attempts {
		t.Fatalf("valid EE handshake was not accepted after storm: %+v", got)
	}
}

func TestIssue138HandshakeRejectionCategories(t *testing.T) {
	cases := []struct{
		err error
		want string
	}{
		{ErrFakeTLSExpected, "unexpected_client_hello"},
		{ErrFakeTLSVerification, "invalid_fake_tls"},
		{ErrHandshakeRead, "handshake_read_failed"},
		{ErrUnsupportedTransport, "unsupported_transport"},
		{ErrInvalidDC, "invalid_dc"},
		{context.Canceled, "other_handshake_failure"},
	}
	for _, tc := range cases {
		if got, _ := handshakeRejectionCategory(tc.err); got != tc.want {
			t.Fatalf("%v: got %s, want %s", tc.err, got, tc.want)
		}
	}
}
