package main

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

const (
	mtProtoDirectBackend    = "direct_tcp"
	mtProtoRouteDirectReady = "MTPROTO_ROUTE_DIRECT_READY"
)

type mtProtoTargetResolver func(dc int) (host string, port int, ok bool)
type mtProtoDialContext func(context.Context, string, string) (net.Conn, error)

type mtProtoDirectConnector struct {
	resolveTarget mtProtoTargetResolver
	dialContext   mtProtoDialContext
	now           func() time.Time
}

func newMtProtoDirectConnector() *mtProtoDirectConnector {
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 60 * time.Second,
	}
	return &mtProtoDirectConnector{
		resolveTarget: mtProtoTargetForDC,
		dialContext:   dialer.DialContext,
		now:           time.Now,
	}
}

func mtProtoTargetForDC(dc int) (string, int, bool) {
	dcOptMu.RLock()
	target := strings.TrimSpace(dcOpt[dc])
	dcOptMu.RUnlock()
	if target == "" {
		target = fallbackTarget(dc, "")
	}
	return target, 443, target != ""
}

func mtProtoTestTargetForDC(dc int) (string, int, bool) {
	target := strings.TrimSpace(dcTestIP[dc])
	return target, 443, target != ""
}

func (c *mtProtoDirectConnector) Capability() mtproxyfrontend.OutboundCapability {
	return mtproxyfrontend.OutboundCapability{
		Status:          mtProtoRouteDirectReady,
		SelectedBackend: mtProtoDirectBackend,
	}
}

func (c *mtProtoDirectConnector) Connect(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
) (net.Conn, mtproxyfrontend.OutboundResult) {
	result := mtproxyfrontend.OutboundResult{
		SelectedBackend: mtProtoDirectBackend,
		FallbackUsed:    false,
	}

	var host string
	var port int
	var ok bool
	if request.IsTestDC {
		host, port, ok = mtProtoTestTargetForDC(request.DCID)
	} else {
		host, port, ok = c.resolveTarget(request.DCID)
	}
	if !ok || host == "" || port < 1 || port > 65535 {
		result.Reason = "dc_target_unavailable"
		result.Err = fmt.Errorf("no direct target for dc %d", request.DCID)
		return nil, result
	}

	address := net.JoinHostPort(host, strconv.Itoa(port))
	now := c.currentTime()
	if health, coolingDown := activeDirectTCPCooldown(request.DCID, host, port, now); coolingDown {
		remaining := health.CooldownUntil.Sub(now)
		if remaining < 0 {
			remaining = 0
		}
		if logInfo != nil {
			logInfo.Printf(
				"direct_tcp_cooldown dc=%d ip=%s port=%d remaining_ms=%d reason=%s consecutive_failures=%d detail=cooldown",
				request.DCID,
				host,
				port,
				remaining.Milliseconds(),
				health.Reason,
				health.ConsecutiveFailures,
			)
		}
		result.Reason = "direct_tcp_ip_cooldown"
		result.Err = fmt.Errorf(
			"direct TCP target %s is on cooldown after %s",
			address,
			health.Reason,
		)
		return nil, result
	}

	logMtProtoRouteAttempt(request, routeTCPFallback, "target=%s", address)

	conn, err := c.dialContext(ctx, "tcp", address)
	if err != nil {
		if failureReason, shouldCooldown := classifyDirectTCPFailure(err); shouldCooldown {
			failureTime := c.currentTime()
			health := markDirectTCPFailure(request.DCID, host, port, failureReason, failureTime)
			if logInfo != nil {
				logInfo.Printf(
					"direct_tcp_failure dc=%d ip=%s port=%d reason=%s consecutive_failures=%d cooldown_ms=%d detail=circuit_open",
					request.DCID,
					host,
					port,
					failureReason,
					health.ConsecutiveFailures,
					health.CooldownUntil.Sub(failureTime).Milliseconds(),
				)
			}
		}
		result.Reason = "direct_tcp_connect_failed"
		result.Err = fmt.Errorf("connect %s: %w", address, err)
		return nil, result
	}
	if err := writeFullConn(conn, request.RelayInit); err != nil {
		_ = conn.Close()
		result.Reason = "relay_init_write_failed"
		result.Err = fmt.Errorf("write relay init to %s: %w", address, err)
		return nil, result
	}

	markDirectTCPSuccess(request.DCID, host, port)
	result.ActualBackend = mtProtoDirectBackend
	result.Reason = "connected"
	return conn, result
}

func (c *mtProtoDirectConnector) currentTime() time.Time {
	if c.now != nil {
		return c.now()
	}
	return time.Now()
}

func writeFullConn(conn net.Conn, data []byte) error {
	for len(data) > 0 {
		n, err := conn.Write(data)
		if err != nil {
			return err
		}
		if n <= 0 {
			return fmt.Errorf("short write")
		}
		data = data[n:]
	}
	return nil
}
