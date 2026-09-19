package main

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
)

func TestGenerateWireGuardKeyPair(t *testing.T) {
	pair, err := generateWireGuardKeyPair()
	if err != nil {
		t.Fatalf("generate key pair: %v", err)
	}
	privateKey, err := base64.StdEncoding.DecodeString(pair.PrivateKey)
	if err != nil {
		t.Fatalf("decode private key: %v", err)
	}
	publicKey, err := base64.StdEncoding.DecodeString(pair.PublicKey)
	if err != nil {
		t.Fatalf("decode public key: %v", err)
	}
	if len(privateKey) != 32 || len(publicKey) != 32 {
		t.Fatalf("unexpected key sizes private=%d public=%d", len(privateKey), len(publicKey))
	}
	expectedPublic, err := curve25519.X25519(privateKey, curve25519.Basepoint)
	if err != nil {
		t.Fatalf("derive public key: %v", err)
	}
	if string(expectedPublic) != string(publicKey) {
		t.Fatal("public key does not match generated private key")
	}
}

func TestProbeAwgWarpConfigRejectsEmptyInputs(t *testing.T) {
	if got := probeAwgWarpConfig("", "149.154.175.50:443", 0); got.Code != "config_path_empty" {
		t.Fatalf("empty path code = %q", got.Code)
	}
	if got := probeAwgWarpConfig("missing.conf", "", 0); got.Code != "probe_target_empty" {
		t.Fatalf("empty target code = %q", got.Code)
	}
}

func TestProbeAwgWarpTelegramRoundTripAcceptsMatchingResPQ(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	const target = "149.154.175.50:443"
	err := probeAwgWarpTelegramRoundTrip(
		ctx,
		func(_ context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" || address != target {
				t.Fatalf("unexpected dial network=%q address=%q", network, address)
			}
			client, server := net.Pipe()
			go serveAwgWarpTelegramResPQ(server, false)
			return client, nil
		},
		target,
	)
	if err != nil {
		t.Fatalf("Telegram round trip failed: %v", err)
	}
}

func TestProbeAwgWarpTelegramRoundTripRejectsMismatchedNonce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	err := probeAwgWarpTelegramRoundTrip(
		ctx,
		func(context.Context, string, string) (net.Conn, error) {
			client, server := net.Pipe()
			go serveAwgWarpTelegramResPQ(server, true)
			return client, nil
		},
		"149.154.175.50:443",
	)
	if err == nil {
		t.Fatal("mismatched Telegram nonce unexpectedly passed")
	}
}

func TestProbeAwgWarpTelegramRoundTripRejectsSilentPeer(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	err := probeAwgWarpTelegramRoundTrip(
		ctx,
		func(context.Context, string, string) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer server.Close()
				_, _ = io.Copy(io.Discard, server)
			}()
			return client, nil
		},
		"149.154.175.50:443",
	)
	if err == nil {
		t.Fatal("silent Telegram peer unexpectedly passed")
	}
}

func serveAwgWarpTelegramResPQ(conn net.Conn, mismatchNonce bool) {
	defer conn.Close()

	var header [4]byte
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return
	}
	if binary.LittleEndian.Uint32(header[:]) != awgWarpIntermediateTransport {
		return
	}
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return
	}
	requestLength := binary.LittleEndian.Uint32(header[:])
	if requestLength != awgWarpUnencryptedHeaderSize+awgWarpReqPQMultiBodySize {
		return
	}
	request := make([]byte, int(requestLength))
	if _, err := io.ReadFull(conn, request); err != nil {
		return
	}
	if binary.LittleEndian.Uint64(request[:8]) != 0 ||
		binary.LittleEndian.Uint32(request[16:20]) != awgWarpReqPQMultiBodySize ||
		binary.LittleEndian.Uint32(request[20:24]) != awgWarpReqPQMultiConstructor {
		return
	}

	response := make([]byte, 100)
	binary.LittleEndian.PutUint64(response[8:16], uint64(time.Now().Unix())<<32)
	binary.LittleEndian.PutUint32(response[16:20], uint32(len(response)-awgWarpUnencryptedHeaderSize))
	binary.LittleEndian.PutUint32(response[20:24], awgWarpResPQConstructor)
	copy(response[24:40], request[24:40])
	if mismatchNonce {
		response[24] ^= 0xff
	}

	binary.LittleEndian.PutUint32(header[:], uint32(len(response)))
	if err := writeFullConn(conn, header[:]); err != nil {
		return
	}
	_ = writeFullConn(conn, response)
}
