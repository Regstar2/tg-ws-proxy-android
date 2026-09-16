package main

import (
	"encoding/base64"
	"testing"

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
