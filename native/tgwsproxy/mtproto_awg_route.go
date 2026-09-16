package main

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

const (
	mtProtoAWGWarpBackend    = "awg_warp"
	mtProtoRouteAWGWarpReady = "MTPROTO_ROUTE_AWG_WARP_READY"
)

type mtProtoAWGWarpConnector struct{}

func newMtProtoAWGWarpConnector() *mtProtoAWGWarpConnector {
	return &mtProtoAWGWarpConnector{}
}

func (c *mtProtoAWGWarpConnector) Capability() mtproxyfrontend.OutboundCapability {
	policy := globalAWGWarpRouteRuntime.Policy()
	if !policy.Enabled {
		return mtproxyfrontend.OutboundCapability{
			Status:          mtProtoRouteUnavailable,
			SelectedBackend: mtProtoAWGWarpBackend,
		}
	}
	return mtproxyfrontend.OutboundCapability{
		Status:          mtProtoRouteAWGWarpReady,
		SelectedBackend: mtProtoAWGWarpBackend,
	}
}

func (c *mtProtoAWGWarpConnector) Connect(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
) (net.Conn, mtproxyfrontend.OutboundResult) {
	result := mtproxyfrontend.OutboundResult{
		SelectedBackend: mtProtoAWGWarpBackend,
		FallbackUsed:    false,
	}

	var host string
	var port int
	var ok bool
	if request.IsTestDC {
		host, port, ok = mtProtoTestTargetForDC(request.DCID)
	} else {
		host, port, ok = mtProtoTargetForDC(request.DCID)
	}
	if !ok || host == "" || port < 1 || port > 65535 {
		result.Reason = "dc_target_unavailable"
		result.Err = fmt.Errorf("no AWG/WARP target for dc %d", request.DCID)
		return nil, result
	}

	address := net.JoinHostPort(host, strconv.Itoa(port))
	logMtProtoRouteAttempt(request, routeAWGWarp, "target=%s", address)

	conn, err := globalAWGWarpRouteRuntime.DialContext(ctx, "tcp", address)
	if err != nil {
		result.Reason = "awg_warp_connect_failed"
		result.Err = fmt.Errorf("connect AWG/WARP target %s: %w", address, err)
		return nil, result
	}
	if err := writeFullConn(conn, request.RelayInit); err != nil {
		_ = conn.Close()
		result.Reason = "relay_init_write_failed"
		result.Err = fmt.Errorf("write relay init through AWG/WARP to %s: %w", address, err)
		return nil, result
	}

	result.ActualBackend = mtProtoAWGWarpBackend
	result.Reason = "connected"
	logMtProtoAWGWarpDiagnostics("connected", request, address)
	return &mtProtoAWGWarpDiagnosticsConn{
		Conn:        conn,
		request:     request,
		innerTarget: address,
	}, result
}

type mtProtoAWGWarpDiagnosticsConn struct {
	net.Conn
	request     mtproxyfrontend.OutboundRequest
	innerTarget string
	once        sync.Once
}

func (c *mtProtoAWGWarpDiagnosticsConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() {
		logMtProtoAWGWarpDiagnostics("closed", c.request, c.innerTarget)
	})
	return err
}

func logMtProtoAWGWarpDiagnostics(
	event string,
	request mtproxyfrontend.OutboundRequest,
	innerTarget string,
) {
	diagnostics, err := globalAWGWarpRouteRuntime.Diagnostics()
	if err != nil {
		if logDebug != nil {
			logDebug.Printf(
				"AWG/WARP diagnostics unavailable event=%s signed_dc=%d dc=%d media=%t error=%v",
				event,
				request.SignedDC,
				request.DCID,
				request.IsMedia,
				err,
			)
		}
		return
	}

	handshake := "none"
	if !diagnostics.LastHandshakeAt.IsZero() {
		handshake = diagnostics.LastHandshakeAt.UTC().Format(time.RFC3339Nano)
	}
	if innerTarget == "" {
		innerTarget = "none"
	}
	endpoint := diagnostics.Endpoint
	if endpoint == "" {
		endpoint = "none"
	}

	if logInfo != nil {
		logInfo.Printf(
			"AWG/WARP diagnostics event=%s signed_dc=%d dc=%d media=%t endpoint=%s last_handshake=%s tunnel_tx_bytes=%d tunnel_rx_bytes=%d app_up_bytes=%d app_down_bytes=%d inner_target=%s",
			event,
			request.SignedDC,
			request.DCID,
			request.IsMedia,
			endpoint,
			handshake,
			diagnostics.TunnelTxBytes,
			diagnostics.TunnelRxBytes,
			diagnostics.AppBytesUp,
			diagnostics.AppBytesDown,
			innerTarget,
		)
	}
}
