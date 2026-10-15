package main

import (
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"io"
	"strings"
	"testing"

	"tg-ws-proxy/mtproxyfrontend"
	"tg-ws-proxy/tgwsroute"
)

func TestFlowsealProductionTLSVerifiesWorkerHostname(t *testing.T) {
	config := flowsealWorkerVerifiedTLSConfig("example.workers.dev")
	if config.InsecureSkipVerify || config.ServerName != "example.workers.dev" {
		t.Fatalf("TLS verification disabled for production Worker: %+v", config)
	}
}

func TestFlowsealUpgradeRequiresRfc6455Headers(t *testing.T) {
	key := []byte("0123456789abcdef")
	keyEncoded := base64.StdEncoding.EncodeToString(key)
	accept := sha1.Sum([]byte(keyEncoded + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	lines := []string{
		"HTTP/1.1 101 Switching Protocols",
		"Upgrade: websocket",
		"Connection: keep-alive, Upgrade",
		"Sec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(accept[:]),
	}
	if !flowsealValidUpgradeResponse(lines, key) {
		t.Fatal("valid RFC 6455 handshake rejected")
	}
	for _, missing := range []string{"Upgrade:", "Connection:", "Sec-WebSocket-Accept:"} {
		var mutated []string
		for _, line := range lines {
			if !strings.HasPrefix(line, missing) {
				mutated = append(mutated, line)
			}
		}
		if flowsealValidUpgradeResponse(mutated, key) {
			t.Fatalf("handshake accepted without %s", missing)
		}
	}
}

func TestFlowsealLegacyWorkerOnlyBulkFullDuplexThroughMtProtoStream(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.Mode = modeWorkerOnly
		settings.Worker.Enabled = true
		settings.Worker.Domain = "legacy.workers.dev"
		settings.Worker.Transport = workerTransportLegacyWebSocket
		settings.Worker.Failover = workerFailoverSettings{}
		return settings
	})

	socket := newWorkerRelayHarnessSocket()
	dialCount := 0
	connector := &mtProtoWorkerConnector{
		flowsealParity: true,
		dialTransportContext: func(_ context.Context, transport workerTransport, domain, path, _ string) (mtProtoFrameSocket, error) {
			dialCount++
			if transport != workerTransportLegacyWebSocket || domain != "legacy.workers.dev" {
				t.Fatalf("wrong endpoint: transport=%s domain=%s", transport, domain)
			}
			if !containsAll(path, "/apiws?", "dst=149.154.167.51", "dc=2") {
				t.Fatalf("invalid Flowseal path: %s", path)
			}
			return socket, nil
		},
	}
	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID: 2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()
	if dialCount != 1 || result.ActualBackend != mtProtoWorkerBackend || result.FallbackUsed {
		t.Fatalf("incorrect route selection: count=%d result=%+v", dialCount, result)
	}
	// More than the problematic 7 * 64 KiB burst. Each TCP read contains
	// one complete obfuscated MTProto abridged packet, not arbitrary bytes.
	const count = 24
	const packetBytes = 65536
	plain := make([]byte, 0, count*packetBytes)
	for i := 0; i < count; i++ {
		plain = append(plain, legacyAbridgedTestPacket(packetBytes-4, byte(i))...)
	}
	encrypted := xorWithStreamForTest(t, relayInit[8:40], relayInit[40:56], plain, true)
	for i := 0; i < count; i++ {
		payload := encrypted[i*packetBytes : (i+1)*packetBytes]
		if n, err := conn.Write(payload); err != nil || n != len(payload) {
			t.Fatalf("upload %d: n=%d err=%v", i, n, err)
		}
	}
	got := socket.sentFrames()
	if len(got) != count+1 {
		t.Fatalf("forwarded packets=%d want=%d", len(got), count+1)
	}
	for i := 0; i < count; i++ {
		if !bytes.Equal(got[i+1], encrypted[i*packetBytes:(i+1)*packetBytes]) {
			t.Fatalf("MTProto packet %d changed", i)
		}
	}

	for i := 0; i < 4; i++ {
		reply := bytes.Repeat([]byte{byte(70 + i)}, 128*1024)
		socket.recv <- reply
		readBack := make([]byte, len(reply))
		if _, err := io.ReadFull(conn, readBack); err != nil {
			t.Fatalf("download %d: %v", i, err)
		}
		if !bytes.Equal(reply, readBack) {
			t.Fatalf("download %d corrupted", i)
		}
	}
}

func TestFlowsealLegacyDC2MediaPreservesSignedDCAndDuplexOnKnownWorkingIP(t *testing.T) {
	withMtProtoWorkerDCMap(t, map[int]string{2: "149.154.167.51"})
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.Mode = modeWorkerOnly
		settings.Worker.Enabled = true
		settings.Worker.Domain = "legacy.workers.dev"
		settings.Worker.Transport = workerTransportLegacyWebSocket
		settings.Worker.DestinationMode = tgwsroute.WorkerDestinationFlowsealDCMap
		settings.Worker.Failover = workerFailoverSettings{}
		return settings
	})

	socket := newWorkerRelayHarnessSocket()
	connector := &mtProtoWorkerConnector{
		flowsealParity: true,
		dialTransportContext: func(_ context.Context, transport workerTransport, domain, path, _ string) (mtProtoFrameSocket, error) {
			if transport != workerTransportLegacyWebSocket || domain != "legacy.workers.dev" ||
				!containsAll(path, "/apiws?", "dst=149.154.167.51", "dc=2", "media=1") {
				t.Fatalf("wrong DC2 media route transport=%s domain=%s path=%s", transport, domain, path)
			}
			if strings.Contains(path, "149.154.167.220") || strings.Contains(path, "dc=4") {
				t.Fatalf("unexpected DC2 media rewrite to Flowseal experimental destination: %s", path)
			}
			return socket, nil
		},
	}
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID: 2,
		SignedDC: -2,
		IsMedia: true,
		Transport: mtproxyfrontend.TransportPaddedIntermediate,
		RelayInit: buildTestInitWithSignedDC(t, -2),
	})
	if result.Err != nil || result.FallbackUsed || result.ActualBackend != mtProtoWorkerBackend {
		t.Fatalf("Legacy DC2 media route failed: %+v", result)
	}
	defer conn.Close()

	const uploadSize = 24 * 1024
	relayInit := buildTestInitWithSignedDC(t, -2)
	plainUpload := legacyAbridgedTestPacket(uploadSize-4, 0xAC)
	upload := xorWithStreamForTest(t, relayInit[8:40], relayInit[40:56], plainUpload, true)
	if n, err := conn.Write(upload); n != uploadSize || err != nil {
		t.Fatalf("DC2 media upload bytes=%d err=%v", n, err)
	}
	frames := socket.sentFrames()
	if len(frames) != 2 || !bytes.Equal(frames[1], upload) {
		t.Fatal("Legacy DC2 media packet changed or relay-init missing")
	}
	download := bytes.Repeat([]byte{0xBC}, 22*1024)
	socket.recv <- download
	actual := make([]byte, len(download))
	if _, err := io.ReadFull(conn, actual); err != nil || !bytes.Equal(actual, download) {
		t.Fatalf("DC2 media download changed bytes=%d err=%v", len(actual), err)
	}
}


// legacyAbridgedTestPacket creates a complete plaintext MTProto abridged
// packet; payloadBytes must be divisible by four.
func legacyAbridgedTestPacket(payloadBytes int, fill byte) []byte {
	if payloadBytes < 4 || payloadBytes%4 != 0 {
		panic("invalid test abridged payload length")
	}
	lengthWords := payloadBytes / 4
	packet := []byte{0x7f, byte(lengthWords), byte(lengthWords >> 8), byte(lengthWords >> 16)}
	return append(packet, bytes.Repeat([]byte{fill}, payloadBytes)...)
}

func TestFlowsealLegacyWorkerReassemblesObfuscatedPacketsNotTCPReads(t *testing.T) {
	relayInit := buildTestInitWithSignedDC(t, 2)
	plain := append([]byte{1, 0x11, 0x11, 0x11, 0x11}, []byte{2, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22}...)
	plain = append(plain, []byte{1, 0x33, 0x33, 0x33, 0x33}...)
	encrypted := xorWithStreamForTest(t, relayInit[8:40], relayInit[40:56], plain, true)

	legacySocket := newWorkerRelayHarnessSocket()
	legacyConn, err := mtProtoLegacyWorkerWebSocketConn(legacySocket, relayInit, "legacy.workers.dev")
	if err != nil {
		t.Fatalf("Legacy connect: %v", err)
	}
	defer legacyConn.Close()

	if n, err := legacyConn.Write(encrypted[:3]); n != 3 || err != nil {
		t.Fatalf("partial MTProto write: n=%d err=%v", n, err)
	}
	if got := legacySocket.sentFrames(); len(got) != 1 {
		t.Fatalf("premature partial packet send, frames=%d", len(got))
	}
	if n, err := legacyConn.Write(encrypted[3:6]); n != 3 || err != nil {
		t.Fatalf("packet completion write: n=%d err=%v", n, err)
	}
	if got := legacySocket.sentFrames(); len(got) != 2 || !bytes.Equal(got[1], encrypted[:5]) {
		t.Fatalf("first packet boundary lost, frames=%d", len(got))
	}
	if n, err := legacyConn.Write(encrypted[6:]); n != len(encrypted)-6 || err != nil {
		t.Fatalf("coalesced packet write: n=%d err=%v", n, err)
	}
	got := legacySocket.sentFrames()
	if len(got) != 4 || !bytes.Equal(got[2], encrypted[5:14]) || !bytes.Equal(got[3], encrypted[14:]) {
		t.Fatalf("Legacy packet boundaries not preserved, frames=%d", len(got))
	}

	// Chunk Relay retains the existing TCP-read framing behavior.
	chunkSocket := newWorkerRelayHarnessSocket()
	chunkConn, err := mtProtoWorkerWebSocketConn(chunkSocket, relayInit, "chunk.workers.dev")
	if err != nil {
		t.Fatalf("Chunk Relay connect: %v", err)
	}
	defer chunkConn.Close()
	if n, err := chunkConn.Write(encrypted[:3]); n != 3 || err != nil {
		t.Fatalf("Chunk Relay payload write: n=%d err=%v", n, err)
	}
	chunkFrames := chunkSocket.sentFrames()
	if len(chunkFrames) != 2 || !bytes.Equal(chunkFrames[1], encrypted[:3]) {
		t.Fatalf("Chunk Relay framing unexpectedly changed, frames=%d", len(chunkFrames))
	}
}
