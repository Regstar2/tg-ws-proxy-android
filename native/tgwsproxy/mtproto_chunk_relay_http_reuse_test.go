package main

import (
	"context"
	"net/http"
	"net/url"
	"testing"
	"time"
)

func TestChunkRelayDownTransportEnablesHTTP11KeepAlive(t *testing.T) {
	lifeCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	conn := &mtProtoChunkRelayConn{
		domain:    "example.workers.dev",
		sessionID: "reuse_profile_001",
		workerDst: "149.154.167.51",
		lifeCtx:   lifeCtx,
		cancel:    cancel,
	}
	transport := conn.newDownHTTPTransport()
	defer transport.CloseIdleConnections()

	if transport.DisableKeepAlives {
		t.Fatal("downstream transport must keep HTTP/1.1 connections alive")
	}
	if transport.ForceAttemptHTTP2 {
		t.Fatal("downstream reuse experiment must keep HTTP/2 disabled")
	}
	if transport.MaxIdleConns != 1 || transport.MaxIdleConnsPerHost != 1 || transport.MaxConnsPerHost != 1 {
		t.Fatalf(
			"unexpected downstream pool limits: idle=%d per_host=%d max=%d",
			transport.MaxIdleConns,
			transport.MaxIdleConnsPerHost,
			transport.MaxConnsPerHost,
		)
	}
	if transport.IdleConnTimeout != mtProtoChunkRelayDownIdleConnTimeout {
		t.Fatalf("IdleConnTimeout=%s want=%s", transport.IdleConnTimeout, mtProtoChunkRelayDownIdleConnTimeout)
	}
	if transport.IdleConnTimeout <= 20*time.Second {
		t.Fatalf("IdleConnTimeout=%s must outlive the 20s adaptive long poll", transport.IdleConnTimeout)
	}
	if got := transport.TLSClientConfig.NextProtos; len(got) != 1 || got[0] != "http/1.1" {
		t.Fatalf("NextProtos=%v want=[http/1.1]", got)
	}
}

func TestChunkRelayDownHTTPReuseRequiresFixedLengthWorkerRevision(t *testing.T) {
	if chunkRelayDownHTTPReuseSupported("chunk-relay-mtproto-v10") {
		t.Fatal("v10 must stay on the fresh downstream request path")
	}
	if !chunkRelayDownHTTPReuseSupported("chunk-relay-mtproto-v11") {
		t.Fatal("v11 must enable downstream HTTP reuse")
	}
	if chunkRelayDownHTTPReuseSupported("chunk-relay-mtproto-v12") {
		t.Fatal("unknown future revisions must opt in explicitly")
	}
}

func TestChunkRelayDownHTTPReuseStats(t *testing.T) {
	conn := newTestChunkRelayConn(t, func(_ context.Context, _ string, _ url.Values, _ []byte) (int, http.Header, []byte, error) {
		return http.StatusNoContent, make(http.Header), nil, nil
	})

	for range 5 {
		conn.recordDownHTTPAttempt()
	}
	conn.recordDownHTTPDial()

	attempts, dials, reusePct := conn.downHTTPStats()
	if attempts != 5 || dials != 1 {
		t.Fatalf("attempts=%d dials=%d want=5/1", attempts, dials)
	}
	if reusePct != 80 {
		t.Fatalf("reusePct=%.2f want=80.00", reusePct)
	}
}
