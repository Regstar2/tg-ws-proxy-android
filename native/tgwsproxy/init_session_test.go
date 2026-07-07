package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func buildTestInitWithSignedDC(t *testing.T, signedDC int16) []byte {
	t.Helper()
	init := make([]byte, 64)
	copy(init[8:40], bytes.Repeat([]byte{0x01}, 32))
	copy(init[40:56], bytes.Repeat([]byte{0x02}, 16))

	stream, err := newAESCTR(init[8:40], init[40:56])
	if err != nil {
		t.Fatalf("newAESCTR: %v", err)
	}
	ks := make([]byte, 64)
	stream.XORKeyStream(ks, zero64)

	plain := make([]byte, 8)
	binary.LittleEndian.PutUint32(plain[0:4], 0xEFEFEFEF)
	binary.LittleEndian.PutUint16(plain[4:6], uint16(signedDC))
	for i := 0; i < 8; i++ {
		init[56+i] = ks[56+i] ^ plain[i]
	}
	return init
}

func TestInitSessionWorkerRoutePreservesFirstPacket(t *testing.T) {
	original := make([]byte, 64)
	for i := range original {
		original[i] = byte(i)
	}

	session := newInitSession(original, "149.154.175.55", 443, "149.154.175.55")
	if !session.dcOk {
		t.Fatal("expected ipToDC fallback to resolve DC")
	}
	if session.dcFromInitOk {
		t.Fatal("random init bytes should not parse as valid dcFromInit")
	}

	payload, splitter := session.prepareForRoute(routeCFWorkerWS)
	if splitter != nil {
		t.Fatal("cf_worker_ws must not create MsgSplitter")
	}
	if !bytes.Equal(payload, original) {
		t.Fatal("cf_worker_ws first packet must equal original init bytes")
	}
	if initPacketMutated(original, payload) {
		t.Fatal("cf_worker_ws packet mutation count must be 0")
	}

	workerPacket := session.workerFirstPacket()
	if !bytes.Equal(workerPacket, original) {
		t.Fatal("workerFirstPacket must return original init bytes")
	}
}

func TestInitSessionDirectRouteMayPatchWhenMappedViaIP(t *testing.T) {
	original := make([]byte, 64)
	for i := range original {
		original[i] = byte(i)
	}

	dcOptMu.Lock()
	prev := dcOpt
	dcOpt = map[int]string{1: "149.154.175.55"}
	dcOptMu.Unlock()
	defer func() {
		dcOptMu.Lock()
		dcOpt = prev
		dcOptMu.Unlock()
	}()

	session := newInitSession(original, "149.154.175.55", 443, "149.154.175.55")
	payload, splitter := session.prepareForRoute(routeDirectWS)
	if bytes.Equal(payload, original) {
		t.Fatal("direct_ws should patch init when dc is resolved via ipToDC and DC is configured")
	}
	if splitter == nil {
		t.Fatal("direct_ws should create MsgSplitter after init patch")
	}

	workerPayload, workerSplitter := session.prepareForRoute(routeCFWorkerWS)
	if !bytes.Equal(workerPayload, original) {
		t.Fatal("cf_worker_ws must stay unpatched even when direct_ws patches")
	}
	if workerSplitter != nil {
		t.Fatal("cf_worker_ws must not create MsgSplitter")
	}
}

func TestInitSessionDcFromInitSkipsPatch(t *testing.T) {
	init := buildTestInitWithSignedDC(t, 2)

	session := newInitSession(init, "149.154.167.50", 443, "149.154.167.50")
	if !session.dcFromInitOk || session.dc != 2 {
		t.Fatalf("expected dcFromInit=2, got dc=%d ok=%t", session.dc, session.dcFromInitOk)
	}

	payload, splitter := session.prepareForRoute(routeDirectWS)
	if !bytes.Equal(payload, init) {
		t.Fatal("direct_ws should not patch when dcFromInit succeeded")
	}
	if splitter != nil {
		t.Fatal("no splitter expected when init was not patched")
	}
}

func TestInitSessionWorkerFirstPacketCanPatchExplicitMediaDestination(t *testing.T) {
	init := buildTestInitWithSignedDC(t, 2)
	session := newInitSession(init, "149.154.167.50", 443, "149.154.167.50")

	patched := session.workerFirstPacketForDestination(4, true, true)
	if bytes.Equal(patched, init) {
		t.Fatal("expected worker first packet to be patched")
	}

	dc, isMedia, ok := dcFromInit(patched)
	if !ok || dc != 4 || !isMedia {
		t.Fatalf("patched init decoded dc=%d isMedia=%t ok=%t", dc, isMedia, ok)
	}

	unchanged := session.workerFirstPacketForDestination(4, true, false)
	if !bytes.Equal(unchanged, init) {
		t.Fatal("worker first packet must remain unchanged when patch=false")
	}
}

func TestInitSessionWorkerFirstPacketCanPatchExplicitCoreDestination(t *testing.T) {
	init := buildTestInitWithSignedDC(t, -2)
	session := newInitSession(init, "149.154.167.151", 443, "149.154.167.151")

	patched := session.workerFirstPacketForDestination(2, false, true)

	dc, isMedia, ok := dcFromInit(patched)
	if !ok || dc != 2 || isMedia {
		t.Fatalf("patched init decoded dc=%d isMedia=%t ok=%t", dc, isMedia, ok)
	}
}
