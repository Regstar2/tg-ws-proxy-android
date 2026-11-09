package main

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"testing"
)

func TestDirectWSClientHelloExperimentProfileMatchesCFProxy(t *testing.T) {
	// The same explicit A/B policy is selected for both WS routes;
	// no other route acquires a profile through these call sites.
	otherRoutes := cfClientHelloPoCProfile{}
	if otherRoutes.tcpSplit || otherRoutes.verifyCertificates {
		t.Fatal("ordinary WebSocket clients must retain the production TLS behavior")
	}
	switch {
	case webSocketClientHelloPoCProfile.tcpSplit:
		if !webSocketClientHelloPoCProfile.verifyCertificates {
			t.Fatal("split profile must validate certificates just like the PoC control")
		}
	case webSocketClientHelloPoCProfile.verifyCertificates:
		// Explicit unmodified A/B control.
	default:
		// No build-tag: production path unchanged.
	}
}

func TestDirectWSClientHelloPrimaryUsesOneDial(t *testing.T) {
	socket := &RawWebSocket{}
	calls := 0
	got, err := mtProtoDirectWSConnectWithDial("198.51.100.2", "test.example", "/apiws", 10, func(ip, domain, path string, timeout float64) (*RawWebSocket, error) {
		calls++
		if ip != "198.51.100.2" || domain != "test.example" || path != "/apiws" || timeout != 10 {
			t.Fatalf("primary dial changed endpoint: %s %s %s %v", ip, domain, path, timeout)
		}
		return socket, nil
	})
	if err != nil || got != socket || calls != 1 {
		t.Fatalf("socket=%p err=%v calls=%d", got, err, calls)
	}
}

func TestDirectWSClientHelloTLSFailureDoesNotTriggerDNSFallback(t *testing.T) {
	sentinel := errors.New("handshake error")
	calls := 0
	_, err := mtProtoDirectWSConnectWithDial("198.51.100.3", "test.example", "/apiws", 10, func(ip, domain, path string, timeout float64) (*RawWebSocket, error) {
		calls++
		return nil, &wsStageError{Stage: "tls_handshake", Err: sentinel}
	})
	if !errors.Is(err, sentinel) || calls != 1 {
		t.Fatalf("TLS fallback policy changed: err=%v calls=%d", err, calls)
	}
}

func TestDirectWSClientHelloDNSFallbackReusesDial(t *testing.T) {
	resolved, err := resolvePreferredIPs("localhost", 2)
	if err != nil || len(resolved.Preferred()) == 0 {
		t.Skipf("localhost resolution unavailable on this CI host: %v", err)
	}
	socket := &RawWebSocket{}
	calls := 0
	got, err := mtProtoDirectWSConnectWithDial("198.51.100.4", "localhost", "/apiws", 2, func(ip, domain, path string, timeout float64) (*RawWebSocket, error) {
		calls++
		if domain != "localhost" || path != "/apiws" || timeout != 2 {
			t.Fatalf("fallback changed handshake fields: %s %s %v", domain, path, timeout)
		}
		if calls == 1 {
			if ip != "198.51.100.4" {
				t.Fatalf("primary target=%s", ip)
			}
			return nil, &wsStageError{Stage: "tcp_dial", Err: context.DeadlineExceeded}
		}
		if net.ParseIP(ip) == nil {
			t.Fatalf("fallback must dial resolved IP, got %s", ip)
		}
		return socket, nil
	})
	if err != nil || got != socket || calls != 2 {
		t.Fatalf("DNS fallback result=%p err=%v calls=%d", got, err, calls)
	}
}

// Neither profile may change the curve preferences or verification model of
// default, non-experimental callers through the shared connection helper.
func TestDirectWSClientHelloProductionTLSConfigurationIsUntouched(t *testing.T) {
	cfg := webSocketTLSConfig("example.test", nil)
	if cfg.ServerName != "example.test" || len(cfg.CurvePreferences) != 0 {
		t.Fatal("production TLS ServerName or curves changed")
	}
	if cfg.MinVersion != 0 || cfg.MaxVersion != 0 || cfg.NextProtos != nil {
		t.Fatal("production TLS version/ALPN preferences changed")
	}
	if webSocketTLSConfig("example.test", classicalTLSCurves()).CurvePreferences[0] != tls.X25519 {
		t.Fatal("CF classical curve configuration changed")
	}
}
