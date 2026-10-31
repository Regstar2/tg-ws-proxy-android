package main

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestWorkerTLSPrimaryThenDefault(t *testing.T) {
	config := &tls.Config{ServerName: "worker.example", NextProtos: []string{"http/1.1"}}
	var fallbackUsed atomic.Int32
	var profiles []*tls.Config
	connected, peer := net.Pipe()
	defer connected.Close()
	defer peer.Close()
	conn, err := workerTLSConnect(context.Background(), config, &fallbackUsed, func(profile *tls.Config) (net.Conn, error) {
		profiles = append(profiles, profile)
		if profile.CurvePreferences != nil {
			return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("handshake failed")}
		}
		return connected, nil
	})
	if err != nil || conn != connected || len(profiles) != 2 {
		t.Fatalf("conn=%v err=%v attempts=%d", conn, err, len(profiles))
	}
	if !reflect.DeepEqual(profiles[0].CurvePreferences, classicalTLSCurves()) || profiles[1].CurvePreferences != nil {
		t.Fatalf("unexpected TLS profiles: %v %v", profiles[0].CurvePreferences, profiles[1].CurvePreferences)
	}
	for _, profile := range profiles {
		if profile.InsecureSkipVerify || profile.ServerName != config.ServerName || !reflect.DeepEqual(profile.NextProtos, config.NextProtos) {
			t.Fatal("worker TLS fallback changed certificate verification, SNI, or ALPN")
		}
	}
	profiles = nil
	_, err = workerTLSConnect(context.Background(), config, &fallbackUsed, func(profile *tls.Config) (net.Conn, error) {
		profiles = append(profiles, profile)
		return connected, nil
	})
	if err != nil || len(profiles) != 1 || profiles[0].CurvePreferences != nil {
		t.Fatalf("successful fallback was not reused: profiles=%d err=%v", len(profiles), err)
	}
}

func TestWorkerTLSDoesNotFallbackForCancellationOrNonTLSFailure(t *testing.T) {
	for _, failure := range []error{
		&wsStageError{Stage: "tcp_dial", Err: errors.New("offline")},
		&wsStageError{Stage: "tls_handshake", Err: context.Canceled},
		&wsStageError{Stage: "tls_handshake", Err: &tls.CertificateVerificationError{}},
	} {
		var used atomic.Int32
		calls := 0
		_, err := workerTLSConnect(context.Background(), &tls.Config{}, &used, func(*tls.Config) (net.Conn, error) {
			calls++
			return nil, failure
		})
		if !errors.Is(err, failure) || calls != 1 || used.Load() != 0 {
			t.Fatalf("failure=%v err=%v calls=%d fallback=%d", failure, err, calls, used.Load())
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	var used atomic.Int32
	calls := 0
	_, _ = workerTLSConnect(ctx, &tls.Config{}, &used, func(*tls.Config) (net.Conn, error) {
		calls++
		cancel()
		return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("timeout")}
	})
	if calls != 1 || used.Load() != 0 {
		t.Fatalf("fallback after cancellation: calls=%d", calls)
	}
}

func TestWorkerTLSFallbackBudgetSharedAcrossConcurrentRequests(t *testing.T) {
	var used atomic.Int32
	var fallbackCalls atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = workerTLSConnect(context.Background(), &tls.Config{}, &used, func(config *tls.Config) (net.Conn, error) {
				if config.CurvePreferences == nil {
					fallbackCalls.Add(1)
				}
				return nil, &wsStageError{Stage: "tls_handshake", Err: errors.New("outage")}
			})
		}()
	}
	wg.Wait()
	if got := fallbackCalls.Load(); got != 1 {
		t.Fatalf("fallback attempts=%d, want 1", got)
	}
}

func TestChunkRelayTLSFallbackBeforeHTTPIsSent(t *testing.T) {
	var handshakes atomic.Int32
	var requests atomic.Int32
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	server.TLS = &tls.Config{GetConfigForClient: func(*tls.ClientHelloInfo) (*tls.Config, error) {
		if handshakes.Add(1) == 1 {
			return nil, errors.New("reject primary ClientHello")
		}
		return nil, nil
	}}
	server.StartTLS()
	defer server.Close()
	c := &mtProtoChunkRelayConn{domain: strings.TrimPrefix(server.URL, "https://")}
	status, _, _, err := c.freshHTTPRequest(context.Background(), "open", url.Values{}, nil)
	if err != nil || status != http.StatusNoContent || handshakes.Load() != 2 || requests.Load() != 1 {
		t.Fatalf("status=%d err=%v handshakes=%d HTTP requests=%d", status, err, handshakes.Load(), requests.Load())
	}
}
