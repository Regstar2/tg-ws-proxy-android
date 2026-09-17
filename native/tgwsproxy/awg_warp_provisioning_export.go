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
	"fmt"
	"net"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
)

const (
	defaultAwgWarpProbeTimeout = 15 * time.Second
	awgWarpProbeReuseDelay     = 3 * time.Second
	awgWarpProbeDataTimeout    = 4 * time.Second
	awgWarpProbeDataTarget     = "1.1.1.1:80"
	awgWarpProbeDataRequest    = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\nConnection: close\r\n\r\n"
)

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

type awgWarpProbeDialContext func(context.Context, string, string) (net.Conn, error)

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

func probeAwgWarpDataRoundTrip(ctx context.Context, dial awgWarpProbeDialContext) error {
	if ctx == nil {
		return fmt.Errorf("data probe context is nil")
	}
	if dial == nil {
		return fmt.Errorf("data probe dialer is nil")
	}

	conn, err := dial(ctx, "tcp", awgWarpProbeDataTarget)
	if err != nil {
		return fmt.Errorf("open data probe connection: %w", err)
	}
	defer conn.Close()

	deadline := time.Now().Add(awgWarpProbeDataTimeout)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := conn.SetDeadline(deadline); err != nil {
		return fmt.Errorf("set data probe deadline: %w", err)
	}
	if err := writeFullConn(conn, []byte(awgWarpProbeDataRequest)); err != nil {
		return fmt.Errorf("write data probe request: %w", err)
	}

	var response [1]byte
	n, err := conn.Read(response[:])
	if n > 0 {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read data probe response: %w", err)
	}
	return fmt.Errorf("data probe returned no response bytes")
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

	// Telegram needs one userspace AWG tunnel to remain reusable after the
	// initial connection burst. Some WARP endpoints accept several immediate
	// inner TCP flows and then stop completing new connects a few seconds later.
	// Keep the first flow alive, verify an immediate parallel flow, wait through
	// that observed failure window, then require a delayed third flow as well.
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	firstConn, err := dialer.DialContext(ctx, "tcp", target)
	if err != nil {
		return awgWarpProbeResult{Code: "connect_failed"}
	}
	defer firstConn.Close()

	secondConn, err := dialer.DialContext(ctx, "tcp", target)
	if err != nil {
		return awgWarpProbeResult{Code: "parallel_connect_failed"}
	}
	_ = secondConn.Close()

	reuseTimer := time.NewTimer(awgWarpProbeReuseDelay)
	defer reuseTimer.Stop()
	select {
	case <-ctx.Done():
		return awgWarpProbeResult{Code: "reuse_wait_timeout"}
	case <-reuseTimer.C:
	}

	thirdConn, err := dialer.DialContext(ctx, "tcp", target)
	if err != nil {
		return awgWarpProbeResult{Code: "delayed_reuse_failed"}
	}
	_ = thirdConn.Close()

	// A TCP connect alone is insufficient. The failing Android profile that led
	// to this probe could complete SYN/SYN-ACK to Telegram while every real
	// application response remained at zero bytes. Require a small request and
	// at least one downstream payload byte through the same userspace tunnel.
	if err := probeAwgWarpDataRoundTrip(ctx, dialer.DialContext); err != nil {
		return awgWarpProbeResult{Code: "data_roundtrip_failed"}
	}

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
	if diagnostics.AppBytesDown <= 0 {
		result.Code = "no_application_downstream"
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
func ValidateAWGWarpConfig(configPath *C.char) (status C.int) {
	status = 1
	defer func() {
		if recover() != nil {
			status = 1
		}
	}()
	if configPath == nil {
		return status
	}
	path := strings.TrimSpace(C.GoString(configPath))
	if path == "" {
		return status
	}
	if _, err := loadAwgWarpConfigFile(path); err != nil {
		return status
	}
	return 0
}

//export ProbeAWGWarpConfig
func ProbeAWGWarpConfig(configPath *C.char, target *C.char, timeoutMillis C.longlong) (result *C.char) {
	defer func() {
		if recover() != nil {
			result = cJSON(awgWarpProbeResult{Code: "native_probe_panic"})
		}
	}()

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
