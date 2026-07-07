package main

import (
	"bytes"
	"testing"
)

func xorWithStreamForTest(t *testing.T, key, iv, in []byte, skipInit bool) []byte {
	t.Helper()
	stream, err := newAESCTR(key, iv)
	if err != nil {
		t.Fatalf("newAESCTR: %v", err)
	}
	if skipInit {
		skip := make([]byte, obfsHandshakeLen)
		stream.XORKeyStream(skip, zero64)
	}
	out := make([]byte, len(in))
	stream.XORKeyStream(out, in)
	return out
}

func TestWorkerReencryptContextRelayInitUsesEffectiveDestination(t *testing.T) {
	clientInit := buildTestInitWithSignedDC(t, -2)

	ctx, err := newWorkerReencryptContext(clientInit, 2, false)
	if err != nil {
		t.Fatalf("newWorkerReencryptContext: %v", err)
	}

	dc, isMedia, ok := dcFromInit(ctx.relayInit)
	if !ok || dc != 2 || isMedia {
		t.Fatalf("relay init decoded dc=%d isMedia=%t ok=%t", dc, isMedia, ok)
	}
}

func TestWorkerReencryptContextClientToRelayRoundTrip(t *testing.T) {
	clientInit := buildTestInitWithSignedDC(t, 2)
	ctx, err := newWorkerReencryptContext(clientInit, 2, false)
	if err != nil {
		t.Fatalf("newWorkerReencryptContext: %v", err)
	}

	plain := bytes.Repeat([]byte{0x31}, 128)
	clientCipher := xorWithStreamForTest(t, clientInit[8:40], clientInit[40:56], plain, true)
	relayCipher := ctx.clientToRelay(clientCipher)
	relayPlain := xorWithStreamForTest(t, ctx.relayInit[8:40], ctx.relayInit[40:56], relayCipher, true)

	if !bytes.Equal(relayPlain, plain) {
		t.Fatal("client->relay transform did not preserve plaintext")
	}
}

func TestWorkerReencryptContextRelayToClientRoundTrip(t *testing.T) {
	clientInit := buildTestInitWithSignedDC(t, 2)
	ctx, err := newWorkerReencryptContext(clientInit, 2, false)
	if err != nil {
		t.Fatalf("newWorkerReencryptContext: %v", err)
	}

	plain := bytes.Repeat([]byte{0x42}, 128)
	relayDecKeyIV := reverseBytes(ctx.relayInit[8:56])
	relayCipher := xorWithStreamForTest(t, relayDecKeyIV[:32], relayDecKeyIV[32:], plain, false)
	clientCipher := ctx.relayToClient(relayCipher)
	clientEncKeyIV := reverseBytes(clientInit[8:56])
	clientPlain := xorWithStreamForTest(t, clientEncKeyIV[:32], clientEncKeyIV[32:], clientCipher, false)

	if !bytes.Equal(clientPlain, plain) {
		t.Fatal("relay->client transform did not preserve plaintext")
	}
}
