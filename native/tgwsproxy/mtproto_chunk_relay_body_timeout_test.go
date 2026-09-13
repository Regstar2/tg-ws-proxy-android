package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"testing"
	"time"
)

func TestChunkRelayDownBodyReadTimesOutAfterHeaders(t *testing.T) {
	reader, writer := io.Pipe()
	defer writer.Close()

	started := time.Now()
	_, err := readChunkRelayBodyWithTimeout(
		context.Background(),
		reader,
		mtProtoChunkRelayDownBytes+4096,
		25*time.Millisecond,
	)
	elapsed := time.Since(started)

	if !errors.Is(err, errChunkRelayDownBodyTimeout) {
		t.Fatalf("err=%v want downstream body timeout", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err=%v must wrap context deadline exceeded", err)
	}
	if elapsed > 500*time.Millisecond {
		t.Fatalf("body timeout took %s, expected prompt abort", elapsed)
	}
}

func TestChunkRelayNonDownBodyReadHasNoShortPayloadTimeout(t *testing.T) {
	payload := []byte("ok")
	resp := &http.Response{
		StatusCode: http.StatusNoContent,
		Body:       io.NopCloser(bytes.NewReader(payload)),
	}
	body, err := readChunkRelayResponseBody(context.Background(), "open", resp, 16)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if !bytes.Equal(body, payload) {
		t.Fatalf("body=%q want=%q", body, payload)
	}
}

func TestChunkRelayDownBodyTimeoutRetriesSameAck(t *testing.T) {
	var acks []string
	calls := 0
	conn := newTestChunkRelayConn(t, func(_ context.Context, action string, query url.Values, _ []byte) (int, http.Header, []byte, error) {
		if action != "down" {
			t.Fatalf("action=%s", action)
		}
		acks = append(acks, query.Get("ack"))
		calls++
		headers := make(http.Header)
		headers.Set("X-Tgws-Chunk-Seq", "1")
		if calls == 1 {
			return http.StatusOK, headers, nil, errors.Join(errChunkRelayDownBodyTimeout, context.DeadlineExceeded)
		}
		return http.StatusOK, headers, []byte("payload"), nil
	})

	query := url.Values{
		"sid":  {conn.sessionID},
		"dst":  {conn.workerDst},
		"ack":  {"0"},
		"wait": {"6000"},
	}
	status, headers, body, err := conn.requestWithRetry(context.Background(), "down", query, nil, "down", 0)
	if err != nil {
		t.Fatalf("requestWithRetry: %v", err)
	}
	if status != http.StatusOK || headers.Get("X-Tgws-Chunk-Seq") != "1" || string(body) != "payload" {
		t.Fatalf("status=%d seq=%q body=%q", status, headers.Get("X-Tgws-Chunk-Seq"), body)
	}
	if len(acks) != 2 || acks[0] != "0" || acks[1] != "0" {
		t.Fatalf("acks=%v want=[0 0]", acks)
	}

	snapshot := conn.downPoll.snapshot()
	if snapshot.retries != 1 {
		t.Fatalf("retries=%d want=1", snapshot.retries)
	}
	if snapshot.timeouts != 1 {
		t.Fatalf("timeouts=%d want=1", snapshot.timeouts)
	}
	if snapshot.bodyTimeouts != 1 {
		t.Fatalf("bodyTimeouts=%d want=1", snapshot.bodyTimeouts)
	}
}
