package mtproxyfrontend

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"
)

func runtimeForStatusTest(t *testing.T) (*Runtime, context.Context) {
	t.Helper()
	r := NewRuntime(nil)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	r.mu.Lock()
	r.runCtx = ctx
	r.mu.Unlock()
	return r, ctx
}

func TestMTProtoStatusKeepsConfirmedAWGAfterFailedHandshakeAndClosedTCP(t *testing.T) {
	r, ctx := runtimeForStatusTest(t)
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 4, SelectedBackend: "direct_tcp",
		ActualBackend: "awg_warp", FallbackUsed: true, Reason: "connected",
	})

	// Unrelated fake-TLS probes and failed TCP attempts must not hide a working AWG route.
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 4, SelectedBackend: "direct_tcp",
		Reason: "handshake_failed", Err: errors.New("unexpected client hello"),
	})
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 4, SelectedBackend: "direct_tcp",
		Reason: "tcp_connect_failed", Err: errors.New("dial failed"),
	})
	// A previous TCP session can close after the AWG fallback was established.
	r.updateRouteTruthFor(ctx, OutboundResult{
		SelectedBackend: "direct_tcp", ActualBackend: "direct_tcp",
		FallbackUsed: false, Reason: "connection_closed",
	})
	got := r.Status()
	if got.ActualBackend != "awg_warp" || got.SelectedBackend != "direct_tcp" ||
		!got.FallbackUsed || got.RouteReason != "connected" {
		t.Fatalf("later failure/close erased confirmed AWG fallback: %+v", got)
	}
	if !strings.Contains(r.StatusString(), "actual_backend=awg_warp;fallback_used=true") {
		t.Fatalf("native status does not expose confirmed AWG: %s", r.StatusString())
	}
}

func TestMTProtoStatusNeverClaimsSelectedRouteAsActualBeforeSuccess(t *testing.T) {
	r, ctx := runtimeForStatusTest(t)
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 3, SelectedBackend: "direct_tcp",
		Reason: "tcp_connect_failed", Err: errors.New("tcp unavailable"),
	})
	got := r.Status()
	if got.ActualBackend != "" || got.SelectedBackend != "direct_tcp" || got.FallbackUsed {
		t.Fatalf("unsuccessful route must not appear active: %+v", got)
	}
}

func TestMTProtoStatusIgnoresOldGenerationAfterNewBackendSucceeded(t *testing.T) {
	r, ctx := runtimeForStatusTest(t)
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 8, SelectedBackend: "direct_tcp",
		ActualBackend: "awg_warp", FallbackUsed: true, Reason: "connected",
	})
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 7, SelectedBackend: "cf_proxy_ws",
		ActualBackend: "cf_proxy_ws", Reason: "connected",
	})
	if got := r.Status(); got.ActualBackend != "awg_warp" || !got.FallbackUsed ||
		got.SelectedBackend != "direct_tcp" {
		t.Fatalf("older session replaced newer route generation: %+v", got)
	}
	r.updateRouteTruthFor(ctx, OutboundResult{
		RouteGeneration: 9, SelectedBackend: "cf_proxy_ws",
		ActualBackend: "cf_proxy_ws", Reason: "connected",
	})
	if got := r.Status(); got.ActualBackend != "cf_proxy_ws" || got.FallbackUsed {
		t.Fatalf("newer confirmed route was not displayed: %+v", got)
	}
}

type issue135MutableCapabilityConnector struct {
	selected string
}

func (c *issue135MutableCapabilityConnector) Capability() OutboundCapability {
	return OutboundCapability{Status: "READY", SelectedBackend: c.selected}
}

func (c *issue135MutableCapabilityConnector) Connect(
	context.Context, OutboundRequest,
) (net.Conn, OutboundResult) {
	return nil, OutboundResult{Reason: "not_used"}
}

func TestMTProtoStatusSelectedTracksHotPolicyWithoutRewritingActual(t *testing.T) {
	connector := &issue135MutableCapabilityConnector{selected: "direct_tcp"}
	r := NewRuntime(nil, connector)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r.mu.Lock()
	r.runCtx = ctx
	r.status = StatusListeningRouteReady
	r.mu.Unlock()

	r.updateRouteTruthFor(ctx, OutboundResult{
		SelectedBackend: "direct_tcp", ActualBackend: "awg_warp",
		RouteGeneration: 4, FallbackUsed: true, Reason: "connected",
	})
	connector.selected = "cf_proxy_ws" // a hot policy update, before new sessions
	got := r.Status()
	if got.SelectedBackend != "cf_proxy_ws" || got.ActualBackend != "awg_warp" ||
		!got.FallbackUsed {
		t.Fatalf("selected and actual must stay independent: %+v", got)
	}
}

func TestMTProtoStatusPreservesRunIsolation(t *testing.T) {
	r, oldCtx := runtimeForStatusTest(t)
	newCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r.mu.Lock()
	r.runCtx = newCtx
	r.mu.Unlock()
	r.updateRouteTruthFor(oldCtx, OutboundResult{
		RouteGeneration: 10, ActualBackend: "direct_tcp", Reason: "connected",
	})
	if got := r.Status(); got.ActualBackend != "" {
		t.Fatalf("retired listener updated active route: %+v", got)
	}
}
