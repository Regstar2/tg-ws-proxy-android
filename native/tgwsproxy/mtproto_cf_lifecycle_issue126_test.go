package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

func TestMtProtoCFTLSHandshakeCancelsPromptly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	started := make(chan struct{})
	finished := make(chan error, 1)
	go func() {
		_, err := wsConnectWithCurvesContextDial(
			ctx, "127.0.0.1", "example.com", "/apiws", 10, classicalTLSCurves(),
			func(context.Context, string, string) (net.Conn, error) {
				close(started)
				return client, nil
			},
		)
		finished <- err
	}()

	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("CF TLS dial did not start")
	}

	_ = server.SetReadDeadline(time.Now().Add(time.Second))
	firstRecord := make([]byte, 5)
	if _, err := server.Read(firstRecord); err != nil {
		t.Fatalf("CF ClientHello was not sent: %v", err)
	}

	cancel()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancelled CF handshake unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("CF handshake outlived the cancelled frontend")
	}
}

func TestMtProtoRouteDecisionSnapshotSurvivesReconfiguration(t *testing.T) {
	decision := mtProtoRouteDecision{
		Routes: []routeKind{routeCFProxyWS, routeTCPFallback},
		Generation: 126,
	}
	ctx := context.WithValue(context.Background(), mtProtoRouteDecisionKey{}, decision)
	got, ok := ctx.Value(mtProtoRouteDecisionKey{}).(mtProtoRouteDecision)
	if !ok || got.Generation != decision.Generation {
		t.Fatal("route decision generation was not retained")
	}
	first := mtProtoAttemptTruthForRoutes(got.Routes, routeCFProxyWS)
	fallback := mtProtoAttemptTruthForRoutes(got.Routes, routeTCPFallback)
	if first.SelectedBackend != mtProtoCFProxyBackend || first.FallbackUsed {
		t.Fatalf("first attempt truth changed: %+v", first)
	}
	if fallback.SelectedBackend != mtProtoCFProxyBackend || !fallback.FallbackUsed {
		t.Fatalf("fallback truth changed: %+v", fallback)
	}
}

func TestMtProtoCFTLSDialCancelledBeforeConnect(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := wsConnectWithCurvesContextDial(
		ctx, "127.0.0.1", "example.com", "/apiws", 10, classicalTLSCurves(),
		func(ctx context.Context, _, _ string) (net.Conn, error) {
			return nil, ctx.Err()
		},
	)
	var stage *wsStageError
	if !errors.As(err, &stage) || stage.Stage != "tcp_dial" || !strings.Contains(err.Error(), "canceled") {
		t.Fatalf("cancelled dial classification=%v", err)
	}
}
