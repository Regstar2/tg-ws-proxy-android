package main

import (
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestParseAwgWarpDialTarget(t *testing.T) {
	target, err := parseAwgWarpDialTarget("tcp", "149.154.167.51:443")
	if err != nil {
		t.Fatalf("parse target: %v", err)
	}
	if target != netip.MustParseAddrPort("149.154.167.51:443") {
		t.Fatalf("target = %s", target)
	}

	if _, err := parseAwgWarpDialTarget("udp", "149.154.167.51:443"); err == nil {
		t.Fatal("expected UDP rejection")
	}
	if _, err := parseAwgWarpDialTarget("tcp", "telegram.org:443"); err == nil {
		t.Fatal("expected hostname rejection for the initial PoC")
	}
	if _, err := parseAwgWarpDialTarget("tcp4", "[2001:db8::1]:443"); err == nil {
		t.Fatal("expected tcp4/IPv6 mismatch rejection")
	}
}

func TestAwgWarpConfigAllowsTarget(t *testing.T) {
	cfg, err := parseAwgWarpConfig(strings.Replace(awgWarpTestConfig, "AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 149.154.160.0/20", 1))
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	if !cfg.allowsTarget(netip.MustParseAddr("149.154.167.51")) {
		t.Fatal("expected Telegram DC address to be allowed")
	}
	if cfg.allowsTarget(netip.MustParseAddr("1.1.1.1")) {
		t.Fatal("unexpected target allowed outside configured prefix")
	}
}

func TestParseAwgWarpDiagnosticsIgnoresPrivateKey(t *testing.T) {
	state := `private_key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
jc=4
public_key=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
endpoint=162.159.192.1:2408
last_handshake_time_sec=1700000000
last_handshake_time_nsec=42
tx_bytes=1234
rx_bytes=5678
allowed_ip=0.0.0.0/0
`
	diagnostics, err := parseAwgWarpDiagnostics(state)
	if err != nil {
		t.Fatalf("parse diagnostics: %v", err)
	}
	if diagnostics.Endpoint != "162.159.192.1:2408" {
		t.Fatalf("endpoint = %q", diagnostics.Endpoint)
	}
	if diagnostics.TunnelTxBytes != 1234 || diagnostics.TunnelRxBytes != 5678 {
		t.Fatalf("unexpected byte counters: tx=%d rx=%d", diagnostics.TunnelTxBytes, diagnostics.TunnelRxBytes)
	}
	wantHandshake := time.Unix(1700000000, 42)
	if !diagnostics.LastHandshakeAt.Equal(wantHandshake) {
		t.Fatalf("handshake = %v, want %v", diagnostics.LastHandshakeAt, wantHandshake)
	}
}

func TestParseAwgWarpDiagnosticsRejectsMalformedCounters(t *testing.T) {
	_, err := parseAwgWarpDiagnostics("tx_bytes=not-a-number\n")
	if err == nil {
		t.Fatal("expected malformed counter error")
	}
}
