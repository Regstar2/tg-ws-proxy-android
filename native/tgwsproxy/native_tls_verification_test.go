package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func newNativeVerifiedWSTestServer(t *testing.T, names []string, expired bool) (*httptest.Server, *x509.CertPool, <-chan [3]string) {
	t.Helper()
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ca := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject: pkix.Name{CommonName: "Native WebSocket test CA"},
		NotBefore: now.Add(-time.Hour),
		NotAfter: now.Add(48 * time.Hour),
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA: true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, ca, ca, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	caCert, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(caCert)

	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leaf := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject: pkix.Name{CommonName: "Native WebSocket test server"},
		DNSNames: names,
		NotBefore: now.Add(-time.Hour),
		NotAfter: now.Add(24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if expired {
		leaf.NotBefore = now.Add(-48 * time.Hour)
		leaf.NotAfter = now.Add(-24 * time.Hour)
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, caCert, &leafKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	observed := make(chan [3]string, 1)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		observed <- [3]string{r.TLS.ServerName, r.Host, r.URL.Path}
		w.Header().Set("Connection", "Upgrade")
		w.Header().Set("Upgrade", "websocket")
		w.WriteHeader(http.StatusSwitchingProtocols)
	}))
	server.TLS = &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{leafDER, caDER}, PrivateKey: leafKey}},
	}
	server.StartTLS()
	t.Cleanup(server.Close)
	return server, roots, observed
}

func dialNativeVerifiedWSTestServer(server *httptest.Server) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
}

func TestNativeWebSocketRejectsInvalidCertificates(t *testing.T) {
	cases := []struct {
		name, certificateName, verifiedName string
		expired, untrusted bool
	}{
		{name: "self_signed_or_untrusted_ca", certificateName: "worker.example", verifiedName: "worker.example", untrusted: true},
		{name: "expired_certificate", certificateName: "worker.example", verifiedName: "worker.example", expired: true},
		{name: "hostname_mismatch", certificateName: "another.example", verifiedName: "worker.example"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server, roots, requests := newNativeVerifiedWSTestServer(t, []string{tc.certificateName}, tc.expired)
			if tc.untrusted {
				roots = x509.NewCertPool()
			}
			ws, err := wsConnectWithCurvesContextDialSNIWithRoots(
				context.Background(), "203.0.113.5", "worker.example", tc.verifiedName,
				"/apiws", 2, classicalTLSCurves(), dialNativeVerifiedWSTestServer(server), roots,
			)
			if ws != nil {
				ws.Close()
				t.Fatal("untrusted upstream was accepted")
			}
			var verification *tls.CertificateVerificationError
			if !errors.As(err, &verification) {
				t.Fatalf("expected TLS certificate verification failure, got %T: %v", err, err)
			}
			select {
			case <-requests:
				t.Fatal("WebSocket upgrade sent despite invalid TLS certificate")
			default:
			}
		})
	}
}

func TestNativeWebSocketRejectsSelfSignedCertificate(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusSwitchingProtocols)
	}))
	defer server.Close()
	ws, err := wsConnectWithCurvesContextDialSNIWithRoots(
		context.Background(), "127.0.0.1", "example.com", "example.com",
		"/apiws", 2, classicalTLSCurves(), dialNativeVerifiedWSTestServer(server), x509.NewCertPool(),
	)
	if ws != nil {
		ws.Close()
		t.Fatal("self-signed server without an approved root was accepted")
	}
	var verification *tls.CertificateVerificationError
	if !errors.As(err, &verification) {
		t.Fatalf("self-signed server failure=%T: %v", err, err)
	}
}

func TestNativeWebSocketConfigurationsVerifyHostnames(t *testing.T) {
	cases := []struct {
		name string
		serverName string
		insecure bool
	}{
		{"cf_proxy", webSocketTLSConfig("pool.example", classicalTLSCurves()).ServerName, webSocketTLSConfig("pool.example", nil).InsecureSkipVerify},
		{"worker_websocket", flowsealWorkerVerifiedTLSConfig("worker.example").ServerName, flowsealWorkerTLSConfig("worker.example").InsecureSkipVerify},
		{"worker_utls", workerUTLSConfig("worker.example").ServerName, workerUTLSConfig("worker.example").InsecureSkipVerify},
	}
	for _, tc := range cases {
		if tc.insecure || tc.serverName == "" {
			t.Fatalf("%s permits unverified TLS or has no SNI", tc.name)
		}
	}
}

func TestNativeTLSCertificateFailureIsNotRetried(t *testing.T) {
	certFailure := &tls.CertificateVerificationError{Err: x509.UnknownAuthorityError{}}
	var calls int
	dialer := &compatibleWebSocketTLSDialer{dial: func(_, _, _ string, _ float64, _ []tls.CurveID) (*RawWebSocket, error) {
		calls++
		return nil, &wsStageError{Stage: "tls_handshake", Err: certFailure}
	}}
	_, err := dialer.connect("203.0.113.10", "worker.example", "/apiws", 1)
	if !errors.Is(err, certFailure) || calls != 1 {
		t.Fatalf("certificate failure caused fallback: attempts=%d err=%v", calls, err)
	}
	if chunkRelayErrorRetryable(&urlWrappedCertificateFailure{err: certFailure}) {
		t.Fatal("chunk relay retries a rejected certificate")
	}
}

type urlWrappedCertificateFailure struct { err error }
func (e *urlWrappedCertificateFailure) Error() string { return e.err.Error() }
func (e *urlWrappedCertificateFailure) Unwrap() error { return e.err }

func TestAndroidCAAppendOnlyImportsValidCertificates(t *testing.T) {
	server, _, _ := newNativeVerifiedWSTestServer(t, []string{"worker.example"}, false)
	directory := t.TempDir()
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw})
	if err := os.WriteFile(filepath.Join(directory, "trusted.0"), certPEM, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "invalid.0"), []byte("not a certificate"), 0600); err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	if got := appendAndroidCACerts(roots, directory); got != 1 {
		t.Fatalf("imported %d certificate files, want 1", got)
	}
	if len(roots.Subjects()) != 1 {
		t.Fatalf("trusted subjects=%d want 1", len(roots.Subjects()))
	}
}
