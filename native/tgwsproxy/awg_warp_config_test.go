package main

import (
	"strings"
	"testing"
)

const awgWarpTestConfig = `[Interface]
PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address = 172.16.0.2/32, 2606:4700:110:8765::2/128
MTU = 1280
DNS = 1.1.1.1
Jc = 4
Jmin = 20
Jmax = 40
S1 = 10
S2 = 20
S3 = 30
S4 = 40
H1 = 100-200
H2 = 201
H3 = 202
H4 = 203
I1 = <r 10>

[Peer]
PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
Endpoint = engage.cloudflareclient.com:2408
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`

func TestParseAwgWarpConfig(t *testing.T) {
	cfg, err := parseAwgWarpConfig(awgWarpTestConfig)
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	if cfg.mtu != 1280 {
		t.Fatalf("mtu = %d, want 1280", cfg.mtu)
	}
	if len(cfg.addresses) != 2 {
		t.Fatalf("addresses = %v, want 2 entries", cfg.addresses)
	}
	if len(cfg.peer.allowedIPs) != 2 {
		t.Fatalf("allowed IPs = %v, want 2 entries", cfg.peer.allowedIPs)
	}
	if cfg.peer.endpoint != "engage.cloudflareclient.com:2408" {
		t.Fatalf("endpoint = %q", cfg.peer.endpoint)
	}
	if len(cfg.deviceOptions) != 12 {
		t.Fatalf("device options = %d, want 12", len(cfg.deviceOptions))
	}
	if !cfg.allowsTarget(cfg.addresses[0]) {
		t.Fatal("0.0.0.0/0 should allow the IPv4 tunnel address")
	}
}

func TestParseAwgWarpConfigAcceptsBareInterfaceAddresses(t *testing.T) {
	cfgText := strings.Replace(
		awgWarpTestConfig,
		"Address = 172.16.0.2/32, 2606:4700:110:8765::2/128",
		"Address = 172.16.0.2, 2606:4700:110:8765::2",
		1,
	)
	cfg, err := parseAwgWarpConfig(cfgText)
	if err != nil {
		t.Fatalf("parse config with bare addresses: %v", err)
	}
	if got := cfg.addresses[0].String(); got != "172.16.0.2" {
		t.Fatalf("IPv4 address = %q", got)
	}
	if got := cfg.addresses[1].String(); got != "2606:4700:110:8765::2" {
		t.Fatalf("IPv6 address = %q", got)
	}
}

func TestParseAwgWarpConfigUsesConservativeDefaultMTU(t *testing.T) {
	cfgText := strings.Replace(awgWarpTestConfig, "MTU = 1280\n", "", 1)
	cfg, err := parseAwgWarpConfig(cfgText)
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	if cfg.mtu != defaultAwgWarpMTU {
		t.Fatalf("mtu = %d, want default %d", cfg.mtu, defaultAwgWarpMTU)
	}
}

func TestParseAwgWarpConfigRejectsMultiplePeers(t *testing.T) {
	_, err := parseAwgWarpConfig(awgWarpTestConfig + "\n[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n")
	if err == nil || !strings.Contains(err.Error(), "exactly one [Peer]") {
		t.Fatalf("err = %v, want multiple-peer rejection", err)
	}
}

func TestParseAwgWarpConfigRejectsJminAboveJmax(t *testing.T) {
	cfgText := strings.Replace(awgWarpTestConfig, "Jmin = 20", "Jmin = 50", 1)
	_, err := parseAwgWarpConfig(cfgText)
	if err == nil || !strings.Contains(err.Error(), "Jmin must not exceed Jmax") {
		t.Fatalf("err = %v, want Jmin/Jmax validation error", err)
	}
}

func TestParseAwgWarpConfigRejectsInvalidPrivateKeyWithoutEchoingSecret(t *testing.T) {
	const invalidSecret = "this-is-not-a-wireguard-private-key"
	cfgText := strings.Replace(
		awgWarpTestConfig,
		"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		invalidSecret,
		1,
	)
	_, err := parseAwgWarpConfig(cfgText)
	if err == nil {
		t.Fatal("expected invalid key error")
	}
	if strings.Contains(err.Error(), invalidSecret) {
		t.Fatalf("error leaks private key material: %v", err)
	}
}

func TestAwgWarpUAPIConfigOrdersDeviceOptionsBeforePeer(t *testing.T) {
	cfg, err := parseAwgWarpConfig(awgWarpTestConfig)
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	uapi := cfg.uapiConfig()
	publicKeyOffset := strings.Index(uapi, "public_key=")
	if publicKeyOffset < 0 {
		t.Fatal("UAPI config is missing public_key")
	}
	for _, deviceKey := range []string{"jc=", "jmin=", "jmax=", "s1=", "h1=", "i1="} {
		offset := strings.Index(uapi, deviceKey)
		if offset < 0 {
			t.Fatalf("UAPI config is missing %s", deviceKey)
		}
		if offset > publicKeyOffset {
			t.Fatalf("device option %s appears after public_key", deviceKey)
		}
	}
	if !strings.Contains(uapi, "replace_peers=true\n") {
		t.Fatal("UAPI config must replace peers for isolated one-peer PoC")
	}
	if !strings.Contains(uapi, "allowed_ip=0.0.0.0/0\n") || !strings.Contains(uapi, "allowed_ip=::/0\n") {
		t.Fatal("UAPI config is missing AllowedIPs")
	}
}
