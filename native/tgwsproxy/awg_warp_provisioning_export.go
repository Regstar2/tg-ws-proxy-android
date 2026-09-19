package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
)

const (
	defaultAwgWarpProbeTimeout       = 15 * time.Second
	awgWarpProbeReuseDelay           = 3 * time.Second
	awgWarpProbeTelegramTimeout      = 4 * time.Second
	awgWarpProbeMTProtoMaxPayload    = 64 * 1024
	awgWarpIntermediateTransport     = uint32(0xeeeeeeee)
	awgWarpReqPQMultiConstructor     = uint32(0xbe7e8ef1)
	awgWarpResPQConstructor          = uint32(0x05162463)
	awgWarpUnencryptedHeaderSize     = 20
	awgWarpReqPQMultiBodySize        = 20
	awgWarpMinResPQBodyPrefixSize    = 20
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

func probeAwgWarpTelegramRoundTrip(
	ctx context.Context,
	dial awgWarpProbeDialContext,
	target string,
) error {
	if ctx == nil {
		return fmt.Errorf("Telegram probe context is nil")
	}
	if dial == nil {
		return fmt.Errorf("Telegram probe dialer is nil")
	}
	target = strings.TrimSpace(target)
	if target == "" {
		return fmt.Errorf("Telegram probe target is empty")
	}

	conn, err := dial(ctx, "tcp", target)
	if err != nil {
		return fmt.Errorf("open Telegram probe connection: %w", err)
	}
	defer conn.Close()

	deadline := time.Now().Add(awgWarpProbeTelegramTimeout)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := conn.SetDeadline(deadline); err != nil {
		return fmt.Errorf("set Telegram probe deadline: %w", err)
	}

	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return fmt.Errorf("generate Telegram probe nonce: %w", err)
	}

	payload := make([]byte, awgWarpUnencryptedHeaderSize+awgWarpReqPQMultiBodySize)
	messageID := uint64(time.Now().Unix()) << 32
	messageID |= uint64(binary.LittleEndian.Uint32(nonce[:4]) & 0xfffffffc)
	binary.LittleEndian.PutUint64(payload[8:16], messageID)
	binary.LittleEndian.PutUint32(payload[16:20], awgWarpReqPQMultiBodySize)
	binary.LittleEndian.PutUint32(payload[20:24], awgWarpReqPQMultiConstructor)
	copy(payload[24:40], nonce[:])

	var header [4]byte
	binary.LittleEndian.PutUint32(header[:], awgWarpIntermediateTransport)
	if err := writeFullConn(conn, header[:]); err != nil {
		return fmt.Errorf("write Telegram probe transport preface: %w", err)
	}
	binary.LittleEndian.PutUint32(header[:], uint32(len(payload)))
	if err := writeFullConn(conn, header[:]); err != nil {
		return fmt.Errorf("write Telegram probe frame length: %w", err)
	}
	if err := writeFullConn(conn, payload); err != nil {
		return fmt.Errorf("write Telegram req_pq_multi: %w", err)
	}

	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return fmt.Errorf("read Telegram response frame length: %w", err)
	}
	responseLength := binary.LittleEndian.Uint32(header[:])
	if responseLength&0x80000000 != 0 {
		return fmt.Errorf("unexpected Telegram quick ACK")
	}
	minimumResponseLength := uint32(awgWarpUnencryptedHeaderSize + awgWarpMinResPQBodyPrefixSize)
	if responseLength < minimumResponseLength ||
		responseLength > awgWarpProbeMTProtoMaxPayload ||
		responseLength%4 != 0 {
		return fmt.Errorf("invalid Telegram response length %d", responseLength)
	}

	response := make([]byte, int(responseLength))
	if _, err := io.ReadFull(conn, response); err != nil {
		return fmt.Errorf("read Telegram response payload: %w", err)
	}
	if binary.LittleEndian.Uint64(response[:8]) != 0 {
		return fmt.Errorf("Telegram probe response is encrypted")
	}

	messageLength := binary.LittleEndian.Uint32(response[16:20])
	if messageLength < awgWarpMinResPQBodyPrefixSize ||
		int(messageLength)+awgWarpUnencryptedHeaderSize != len(response) {
		return fmt.Errorf("invalid Telegram response message length %d", messageLength)
	}
	if binary.LittleEndian.Uint32(response[20:24]) != awgWarpResPQConstructor {
		return fmt.Errorf("unexpected Telegram response constructor")
	}
	if !bytes.Equal(response[24:40], nonce[:]) {
		return fmt.Errorf("Telegram response nonce mismatch")
	}
	return nil
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

	// A TCP connect alone is insufficient. Require Telegram itself to answer an
	// unencrypted MTProto req_pq_multi request through the same userspace tunnel.
	// This rejects WARP endpoints that can carry generic Internet traffic but
	// leave real Telegram MTProto sessions with zero downstream bytes.
	if err := probeAwgWarpTelegramRoundTrip(ctx, dialer.DialContext, target); err != nil {
		return awgWarpProbeResult{Code: "telegram_roundtrip_failed"}
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