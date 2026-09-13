package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"reflect"
	"strconv"
	"testing"
	"time"
)

func TestChunkRelayDownPollProfileFastIdleDeepIdleFast(t *testing.T) {
	var state chunkRelayDownPollState

	for i := 0; i < mtProtoChunkRelayPollIdleAfterEmpty; i++ {
		waitMS := state.waitMS()
		if waitMS != mtProtoChunkRelayPollWaitMS {
			t.Fatalf("empty=%d wait=%d want=%d", i, waitMS, mtProtoChunkRelayPollWaitMS)
		}
		state.recordStarted(waitMS)
		state.recordEmpty(waitMS)
	}
	if got := state.waitMS(); got != mtProtoChunkRelayPollIdleWaitMS {
		t.Fatalf("idle wait=%d want=%d", got, mtProtoChunkRelayPollIdleWaitMS)
	}

	for i := mtProtoChunkRelayPollIdleAfterEmpty; i < mtProtoChunkRelayPollDeepIdleAfterEmpty; i++ {
		waitMS := state.waitMS()
		if waitMS != mtProtoChunkRelayPollIdleWaitMS {
			t.Fatalf("empty=%d wait=%d want=%d", i, waitMS, mtProtoChunkRelayPollIdleWaitMS)
		}
		state.recordStarted(waitMS)
		state.recordEmpty(waitMS)
	}
	if got := state.waitMS(); got != mtProtoChunkRelayPollDeepIdleWaitMS {
		t.Fatalf("deep idle wait=%d want=%d", got, mtProtoChunkRelayPollDeepIdleWaitMS)
	}

	deepWaitMS := state.waitMS()
	state.recordStarted(deepWaitMS)
	oldWaitMS, newWaitMS := state.recordPayload()
	if oldWaitMS != mtProtoChunkRelayPollDeepIdleWaitMS || newWaitMS != mtProtoChunkRelayPollWaitMS {
		t.Fatalf("payload reset=%d->%d", oldWaitMS, newWaitMS)
	}

	snapshot := state.snapshot()
	if snapshot.emptyPolls != mtProtoChunkRelayPollDeepIdleAfterEmpty {
		t.Fatalf("empty polls=%d want=%d", snapshot.emptyPolls, mtProtoChunkRelayPollDeepIdleAfterEmpty)
	}
	if snapshot.payloadPolls != 1 {
		t.Fatalf("payload polls=%d want=1", snapshot.payloadPolls)
	}
	if snapshot.polls != mtProtoChunkRelayPollDeepIdleAfterEmpty+1 {
		t.Fatalf("polls=%d want=%d", snapshot.polls, mtProtoChunkRelayPollDeepIdleAfterEmpty+1)
	}
}

func TestChunkRelayDownPollTimeoutTracksLongWait(t *testing.T) {
	tests := []struct {
		waitMS int
		want   time.Duration
	}{
		{mtProtoChunkRelayPollWaitMS, 10 * time.Second},
		{mtProtoChunkRelayPollIdleWaitMS, 16 * time.Second},
		{mtProtoChunkRelayPollDeepIdleWaitMS, 24 * time.Second},
	}
	for _, test := range tests {
		if got := chunkRelayDownRequestTimeout(test.waitMS); got != test.want {
			t.Fatalf("wait=%d timeout=%s want=%s", test.waitMS, got, test.want)
		}
	}
}

func TestChunkRelayDownPollErrorReturnsProfileToFast(t *testing.T) {
	var state chunkRelayDownPollState
	for i := 0; i < mtProtoChunkRelayPollDeepIdleAfterEmpty; i++ {
		waitMS := state.waitMS()
		state.recordStarted(waitMS)
		state.recordEmpty(waitMS)
	}
	oldWaitMS, newWaitMS := state.resetAfterError()
	if oldWaitMS != mtProtoChunkRelayPollDeepIdleWaitMS || newWaitMS != mtProtoChunkRelayPollWaitMS {
		t.Fatalf("error reset=%d->%d", oldWaitMS, newWaitMS)
	}
}

func TestChunkRelayReadUsesAdaptivePollProfileAndResetsAfterPayload(t *testing.T) {
	var waits []int
	calls := 0
	conn := newTestChunkRelayConn(t, func(_ context.Context, action string, query url.Values, _ []byte) (int, http.Header, []byte, error) {
		if action != "down" {
			t.Fatalf("action=%s", action)
		}
		waitMS, err := strconv.Atoi(query.Get("wait"))
		if err != nil {
			t.Fatalf("wait=%q: %v", query.Get("wait"), err)
		}
		waits = append(waits, waitMS)
		calls++
		if calls <= mtProtoChunkRelayPollDeepIdleAfterEmpty {
			return http.StatusNoContent, make(http.Header), nil, nil
		}
		if calls == mtProtoChunkRelayPollDeepIdleAfterEmpty+1 {
			headers := make(http.Header)
			headers.Set("X-Tgws-Chunk-Seq", "1")
			return http.StatusOK, headers, []byte("x"), nil
		}
		return http.StatusGone, make(http.Header), nil, nil
	})

	buf := make([]byte, 1)
	n, err := conn.Read(buf)
	if err != nil || n != 1 || string(buf[:n]) != "x" {
		t.Fatalf("first read n=%d err=%v data=%q", n, err, buf[:n])
	}
	_, err = conn.Read(buf)
	if !errors.Is(err, io.EOF) {
		t.Fatalf("second read err=%v want EOF", err)
	}

	want := []int{
		mtProtoChunkRelayPollWaitMS,
		mtProtoChunkRelayPollWaitMS,
		mtProtoChunkRelayPollWaitMS,
		mtProtoChunkRelayPollIdleWaitMS,
		mtProtoChunkRelayPollIdleWaitMS,
		mtProtoChunkRelayPollIdleWaitMS,
		mtProtoChunkRelayPollIdleWaitMS,
		mtProtoChunkRelayPollIdleWaitMS,
		mtProtoChunkRelayPollDeepIdleWaitMS,
		mtProtoChunkRelayPollWaitMS,
	}
	if !reflect.DeepEqual(waits, want) {
		t.Fatalf("waits=%v want=%v", waits, want)
	}
}
