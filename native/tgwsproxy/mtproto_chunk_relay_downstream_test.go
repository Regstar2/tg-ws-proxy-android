package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"testing"
)

func TestChunkRelayReadAccepts12KiBDownstreamAndAcknowledgesAfterConsume(t *testing.T) {
	if mtProtoChunkRelayBytes != 8*1024 {
		t.Fatalf("serial chunk bytes=%d want=%d", mtProtoChunkRelayBytes, 8*1024)
	}
	if mtProtoChunkRelayDownBytes != 12*1024 {
		t.Fatalf("down chunk bytes=%d want=%d", mtProtoChunkRelayDownBytes, 12*1024)
	}
	if mtProtoChunkRelayUploadBytes != 12*1024 {
		t.Fatalf("pipeline upload chunk bytes=%d want=%d", mtProtoChunkRelayUploadBytes, 12*1024)
	}

	payload := make([]byte, mtProtoChunkRelayDownBytes)
	for i := range payload {
		payload[i] = byte(i % 251)
	}

	var acks []string
	calls := 0
	conn := newTestChunkRelayConn(t, func(_ context.Context, action string, query url.Values, _ []byte) (int, http.Header, []byte, error) {
		if action != "down" {
			t.Fatalf("action=%s", action)
		}
		acks = append(acks, query.Get("ack"))
		calls++
		if calls == 1 {
			headers := make(http.Header)
			headers.Set("X-Tgws-Chunk-Seq", "1")
			return http.StatusOK, headers, payload, nil
		}
		return http.StatusGone, make(http.Header), nil, nil
	})

	first := make([]byte, 4096)
	n, err := conn.Read(first)
	if err != nil || n != len(first) {
		t.Fatalf("first read n=%d err=%v", n, err)
	}
	if !bytes.Equal(first, payload[:len(first)]) {
		t.Fatal("first read payload mismatch")
	}
	if conn.ackSeq != 0 {
		t.Fatalf("ackSeq=%d before full consume want=0", conn.ackSeq)
	}

	second := make([]byte, len(payload)-len(first))
	n, err = conn.Read(second)
	if err != nil || n != len(second) {
		t.Fatalf("second read n=%d err=%v", n, err)
	}
	if !bytes.Equal(second, payload[len(first):]) {
		t.Fatal("second read payload mismatch")
	}
	if conn.ackSeq != 1 {
		t.Fatalf("ackSeq=%d after full consume want=1", conn.ackSeq)
	}

	_, err = conn.Read(make([]byte, 1))
	if !errors.Is(err, io.EOF) {
		t.Fatalf("third read err=%v want EOF", err)
	}
	if len(acks) != 2 || acks[0] != "0" || acks[1] != "1" {
		t.Fatalf("acks=%v want=[0 1]", acks)
	}
}
