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
	mtProtoAWGWarpBackend          = "awg_warp"
	mtProtoRouteAWGWarpReady       = "MTPROTO_ROUTE_AWG_WARP_READY"
	mtProtoAWGWarpConnectTimeout   = 10 * time.Second
	mtProtoAWGWarpRelayInitTimeout = 5 * time.Second
)

type mtProtoAWGWarpConnector struct {
	dialContext      mtProtoDialContext
	connectTimeout   time.Duration
	relayInitTimeout time.Duration
	dialGateOnce     sync.Once
	dialGate         chan struct{}
}

func newMtProtoAWGWarpConnector() *mtProtoAWGWarpConnector {
	return &mtProtoAWGWarpConnector{
		dialContext:      globalAWGWarpRouteRuntime.DialContext,
		connectTimeout:   mtProtoAWGWarpConnectTimeout,
		relayInitTimeout: mtProtoAWGWarpRelayInitTimeout,
	}
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

	connectCtx, cancelConnect := context.WithTimeout(ctx, c.effectiveConnectTimeout())
	defer cancelConnect()

	queueStarted := time.Now()
	gate := c.serialDialGate()
	select {
	case <-gate:
	case <-connectCtx.Done():
		result.Reason = "awg_warp_connect_queue_timeout"
		result.Err = fmt.Errorf("wait for AWG/WARP connect slot to %s: %w", address, connectCtx.Err())
		return nil, result
	}
	queueDuration := time.Since(queueStarted)

	dialStarted := time.Now()
	conn, err := c.effectiveDialContext()(connectCtx, "tcp", address)
	gate <- struct{}{}
	if err != nil {
		result.Reason = "awg_warp_connect_failed"
		result.Err = fmt.Errorf("connect AWG/WARP target %s: %w", address, err)
		return nil, result
	}
	if logInfo != nil {
		logInfo.Printf(
			"AWG/WARP connect stage=tcp_connected signed_dc=%d dc=%d media=%t target=%s queue_ms=%d dial_ms=%d",
			request.SignedDC,
			request.DCID,
			request.IsMedia,
			address,
			queueDuration.Milliseconds(),
			time.Since(dialStarted).Milliseconds(),
		)
	}

	if err := writeFullConnWithTimeout(conn, request.RelayInit, c.effectiveRelayInitTimeout()); err != nil {
		_ = conn.Close()
		result.Reason = "relay_init_write_failed"
		result.Err = fmt.Errorf("write relay init through AWG/WARP to %s: %w", address, err)
		return nil, result
	}
	if logInfo != nil {
		logInfo.Printf(
			"AWG/WARP connect stage=relay_init_written signed_dc=%d dc=%d media=%t target=%s bytes=%d",
			request.SignedDC,
			request.DCID,
			request.IsMedia,
			address,
			len(request.RelayInit),
		)
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

func (c *mtProtoAWGWarpConnector) serialDialGate() chan struct{} {
	c.dialGateOnce.Do(func() {
		c.dialGate = make(chan struct{}, 1)
		c.dialGate <- struct{}{}
	})
	return c.dialGate
}

func (c *mtProtoAWGWarpConnector) effectiveDialContext() mtProtoDialContext {
	if c.dialContext != nil {
		return c.dialContext
	}
	return globalAWGWarpRouteRuntime.DialContext
}

func (c *mtProtoAWGWarpConnector) effectiveConnectTimeout() time.Duration {
	if c.connectTimeout > 0 {
		return c.connectTimeout
	}
	return mtProtoAWGWarpConnectTimeout
}

func (c *mtProtoAWGWarpConnector) effectiveRelayInitTimeout() time.Duration {
	if c.relayInitTimeout > 0 {
		return c.relayInitTimeout
	}
	return mtProtoAWGWarpRelayInitTimeout
}

func writeFullConnWithTimeout(conn net.Conn, data []byte, timeout time.Duration) error {
	if conn == nil {
		return fmt.Errorf("connection is nil")
	}
	if timeout <= 0 {
		return writeFullConn(conn, data)
	}
	if err := conn.SetWriteDeadline(time.Now().Add(timeout)); err != nil {
		return fmt.Errorf("set relay init write deadline: %w", err)
	}
	defer func() {
		_ = conn.SetWriteDeadline(time.Time{})
	}()
	return writeFullConn(conn, data)
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
