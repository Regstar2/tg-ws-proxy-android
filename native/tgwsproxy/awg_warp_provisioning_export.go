package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
)

const defaultAwgWarpProbeTimeout = 12 * time.Second

type wireGuardKeyPair struct {
	PrivateKey string `json:"private_key"`
	PublicKey  string `json:"public_key"`
}

type awgWarpProbeResult struct {
	OK                bool   `json:"ok"`
	Code              string `json:"code"`
	Endpoint          string `json:"endpoint,omitempty"`
	LastHandshakeUnix int64  `json:"last_handshake_unix,omitempty"`
	TunnelTxBytes     uint64 `json:"tunnel_tx_bytes,omitempty"`
	TunnelRxBytes     uint64 `json:"tunnel_rx_bytes,omitempty"`
}

func generateWireGuardKeyPair() (wireGuardKeyPair, error) {
	privateKey := make([]byte, curve25519.ScalarSize)
	if _, err := rand.Read(privateKey); err != nil {
		return wireGuardKeyPair{}, err
	}
	privateKey[0] &= 248
	privateKey[31] &= 127
	privateKey[31] |= 64

	publicKey, err := curve25519.X25519(privateKey, curve25519.Basepoint)
	if err != nil {
		return wireGuardKeyPair{}, err
	}
	return wireGuardKeyPair{
		PrivateKey: base64.StdEncoding.EncodeToString(privateKey),
		PublicKey:  base64.StdEncoding.EncodeToString(publicKey),
	}, nil
}

func probeAwgWarpConfig(path, target string, timeout time.Duration) awgWarpProbeResult {
	if strings.TrimSpace(path) == "" {
		return awgWarpProbeResult{Code: "config_path_empty"}
	}
	if strings.TrimSpace(target) == "" {
		return awgWarpProbeResult{Code: "probe_target_empty"}
	}
	if timeout <= 0 {
		timeout = defaultAwgWarpProbeTimeout
	}

	dialer, err := newAwgWarpDialerFromFile(path)
	if err != nil {
		return awgWarpProbeResult{Code: "config_or_tunnel_init_failed"}
	}
	defer dialer.Close()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	conn, err := dialer.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		return awgWarpProbeResult{Code: "connect_failed"}
	}
	_ = conn.Close()

	diagnostics, err := dialer.Diagnostics()
	if err != nil {
		return awgWarpProbeResult{Code: "diagnostics_failed"}
	}
	result := awgWarpProbeResult{
		Endpoint:      diagnostics.Endpoint,
		TunnelTxBytes: diagnostics.TunnelTxBytes,
		TunnelRxBytes: diagnostics.TunnelRxBytes,
	}
	if !diagnostics.LastHandshakeAt.IsZero() {
		result.LastHandshakeUnix = diagnostics.LastHandshakeAt.Unix()
	}
	if result.LastHandshakeUnix == 0 {
		result.Code = "no_handshake"
		return result
	}
	if result.TunnelTxBytes == 0 || result.TunnelRxBytes == 0 {
		result.Code = "no_tunnel_traffic"
		return result
	}
	result.OK = true
	result.Code = "ok"
	return result
}

func cJSON(value any) *C.char {
	encoded, err := json.Marshal(value)
	if err != nil {
		return C.CString(`{"ok":false,"code":"json_encode_failed"}`)
	}
	return C.CString(string(encoded))
}

//export GenerateWireGuardKeyPair
func GenerateWireGuardKeyPair() *C.char {
	pair, err := generateWireGuardKeyPair()
	if err != nil {
		return cJSON(map[string]any{"ok": false, "code": "key_generation_failed"})
	}
	return cJSON(map[string]any{
		"ok":          true,
		"private_key": pair.PrivateKey,
		"public_key":  pair.PublicKey,
	})
}

//export ValidateAWGWarpConfig
func ValidateAWGWarpConfig(configPath *C.char) C.int {
	if configPath == nil {
		return 1
	}
	path := strings.TrimSpace(C.GoString(configPath))
	if path == "" {
		return 1
	}
	if _, err := loadAwgWarpConfigFile(path); err != nil {
		return 1
	}
	return 0
}

//export ProbeAWGWarpConfig
func ProbeAWGWarpConfig(configPath *C.char, target *C.char, timeoutMillis C.longlong) *C.char {
	path := ""
	if configPath != nil {
		path = strings.TrimSpace(C.GoString(configPath))
	}
	probeTarget := ""
	if target != nil {
		probeTarget = strings.TrimSpace(C.GoString(target))
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	return cJSON(probeAwgWarpConfig(path, probeTarget, timeout))
}
