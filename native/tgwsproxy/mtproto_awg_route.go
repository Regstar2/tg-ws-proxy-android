package main

import (
	"context"
	"fmt"
	"net"
	"strconv"

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
	return conn, result
}
