package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
	"tg-ws-proxy/tgwsroute"
)

const (
	mtProtoDirectWSBackend  = "direct_ws"
	mtProtoCFProxyBackend   = "cf_proxy_ws"
	mtProtoRouteChainReady  = "MTPROTO_ROUTE_CHAIN_READY"
	mtProtoRouteUnavailable = "MTPROTO_ROUTE_UNAVAILABLE"
)

type mtProtoRouteDial func(domain, path, logPrefix string) (mtProtoFrameSocket, error)
type mtProtoDirectWSDial func(targetIP, domain, path string, timeout float64) (mtProtoFrameSocket, error)

type mtProtoDirectWSConnector struct {
	resolveTarget     mtProtoTargetResolver
	dial              mtProtoDirectWSDial // Legacy injection for existing tests only.
	dialContext       func(context.Context, string, string, string, float64) (mtProtoFrameSocket, error)
	enableHealth      bool
	enableAlternates  bool
}

func newMtProtoDirectWSConnector() *mtProtoDirectWSConnector {
	return &mtProtoDirectWSConnector{
		resolveTarget:    mtProtoTargetForDC,
		dialContext:      defaultMtProtoDirectWSDialContext,
		enableHealth:     true,
		enableAlternates: true,
	}
}

func (c *mtProtoDirectWSConnector) Capability() mtproxyfrontend.OutboundCapability {
	return mtproxyfrontend.OutboundCapability{
		Status:          mtProtoRouteChainReady,
		SelectedBackend: mtProtoDirectWSBackend,
	}
}

func (c *mtProtoDirectWSConnector) Connect(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
) (net.Conn, mtproxyfrontend.OutboundResult) {
	result := mtproxyfrontend.OutboundResult{SelectedBackend: mtProtoDirectWSBackend}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		result.Reason, result.Err = "cancelled", err
		return nil, result
	}

	// A test DC must never be resolved through the production DC mapping.
	// Keep the paths mutually exclusive so a resolver cannot run (or fail)
	// before the test endpoint and /apiws_test are selected.
	var target string
	var ok bool
	if request.IsTestDC {
		target, _, ok = mtProtoTestTargetForDC(request.DCID)
	} else {
		resolveTarget := c.resolveTarget
		if resolveTarget == nil {
			resolveTarget = mtProtoTargetForDC
		}
		target, _, ok = resolveTarget(request.DCID)
	}
	if !ok || target == "" {
		result.Reason = "direct_ws_target_unavailable"
		result.Err = fmt.Errorf("no direct WebSocket target for dc %d", request.DCID)
		return nil, result
	}

	isMedia := request.IsMedia
	domains := wsDomains(request.DCID, &isMedia)
	if len(domains) == 0 {
		result.Reason = "direct_ws_domain_unavailable"
		result.Err = fmt.Errorf("no direct WebSocket domain for dc %d", request.DCID)
		return nil, result
	}

	path := "/apiws"
	if request.IsTestDC {
		path = "/apiws_test"
	}
	targets := directWSTargetCandidates(request.DCID, target,
		c.enableAlternates && !request.IsTestDC)
	// Preserve the legacy fallback decision for a single cooled-down IP.
	// With a second target, skip only the cooled-down primary; otherwise
	// it would incorrectly suppress a healthy alternative.
	legacyCooldown := directIPCoolingDown(target, monoNow()) &&
		directIPCooldownFallbackAvailable(getRuntimeSettings())
	if legacyCooldown && len(targets) == 1 {
		result.Reason = "direct_ip_cooldown"
		result.Err = fmt.Errorf("direct WebSocket target %s is on IP cooldown", target)
		return nil, result
	}

	// Bound the whole route, not just each dial. A missing TCP SYN/ACK must
	// not leave a backlog of active MTProto requests on Android.
	routeCtx, stopRoute := context.WithTimeout(ctx, 15*time.Second)
	defer stopRoute()

	var lastErr error
	lastReason := "direct_ws_connect_failed"
	timedOut := false
	generation := getRuntimeSettings().PolicyGen
	for _, candidate := range targets {
		if err := routeCtx.Err(); err != nil {
			lastErr = err
			break
		}
		if legacyCooldown && candidate == target {
			lastReason = "direct_ip_cooldown"
			lastErr = fmt.Errorf("direct WebSocket target %s is on IP cooldown", target)
			continue
		}

		var release func()
		if c.enableHealth {
			var guardReason string
			release, guardReason = beginDirectWSAttempt(
				request.DCID, candidate, generation, time.Now())
			if guardReason != "" {
				lastReason = guardReason
				lastErr = fmt.Errorf("direct WebSocket target unavailable: %s", guardReason)
				continue
			}
		}

		budget := 10 * time.Second
		if len(targets) > 1 {
			budget = 7 * time.Second
		}
		attemptCtx, stopAttempt := context.WithTimeout(routeCtx, budget)
		stream, connErr, failureReason := c.connectDirectWSTarget(
			attemptCtx, request, candidate, domains, path, budget.Seconds())
		stopAttempt()
		if release != nil {
			release()
		}

		if connErr == nil {
			if c.enableHealth {
				markDirectWSSuccess(request.DCID, candidate, generation)
			}
			// Never clear another IP's cooldown when an alternative succeeds.
			markDirectIPSuccess(candidate)
			result.ActualBackend = mtProtoDirectWSBackend
			result.Reason = "connected"
			return stream, result
		}
		lastErr = connErr
		if failureReason != "" {
			if failureReason == directTCPFailureTimeout {
				timedOut = true
				markDirectIPTimeout(candidate, monoNow())
			}
			if c.enableHealth {
				markDirectWSFailure(request.DCID, candidate, generation,
					failureReason, time.Now())
			}
		}
	}

	if ctx.Err() != nil {
		result.Reason, result.Err = "cancelled", ctx.Err()
		return nil, result
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("direct WebSocket failed for dc %d", request.DCID)
	}
	if errors.Is(lastErr, context.Canceled) && routeCtx.Err() == nil {
		result.Reason = "cancelled"
	} else if timedOut {
		result.Reason = "direct_ws_ip_timeout"
	} else {
		result.Reason = lastReason
	}
	result.Err = lastErr
	return nil, result
}

// Each candidate is attempted with the same DC, SNI/Host and WS path.
// Connector-only injection preserves existing unit tests and leaves the
// Cloudflare, Worker and direct_tcp transports entirely untouched.
func (c *mtProtoDirectWSConnector) connectDirectWSTarget(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
	target string,
	domains []string,
	path string,
	timeout float64,
) (net.Conn, error, string) {
	var lastErr error
	var tcpFailure string
	for _, domain := range domains {
		if err := ctx.Err(); err != nil {
			return nil, err, tcpFailure
		}
		prefix := fmt.Sprintf("[MTProto] DC%d%s direct_ws",
			request.DCID, mediaTag(request.IsMedia))
		logMtProtoRouteAttempt(ctx, request, routeDirectWS,
			"target=%s domain=%s", target, domain)

		var ws mtProtoFrameSocket
		var err error
		switch {
		case c.dial != nil:
			ws, err = c.dial(target, domain, path, timeout)
		case c.dialContext != nil:
			ws, err = c.dialContext(ctx, target, domain, path, timeout)
		default:
			ws, err = defaultMtProtoDirectWSDialContext(
				ctx, target, domain, path, timeout)
		}
		if err != nil {
			lastErr = err
			if reason := directWSTCPFailureReason(err); reason != "" {
				tcpFailure = reason
			} else {
				tcpFailure = ""
			}
			var wsErr *WsHandshakeError
			if errors.As(err, &wsErr) && wsErr.IsRedirect() {
				continue
			}
			logDomainConnectFailure(prefix, domain, target, err)
			continue
		}
		if err := ctx.Err(); err != nil {
			ws.Close()
			return nil, err, tcpFailure
		}
		stream, err := mtProtoWebSocketConn(ws, request.RelayInit, domain)
		if err != nil {
			ws.Close()
			lastErr = err
			tcpFailure = ""
			continue
		}
		stats.connectionsWs.Add(1)
		return stream, nil, ""
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("direct WebSocket has no usable domains for dc %d", request.DCID)
	}
	return nil, lastErr, tcpFailure
}

type mtProtoCFProxyConnector struct {
	dial        mtProtoRouteDial
	dialContext func(context.Context, string, string, string) (mtProtoFrameSocket, error)
}

type cfPoolMissWindow struct {
	started time.Time
	count   int64
}

var cfPoolMissSamples = struct {
	sync.Mutex
	byDC map[int]cfPoolMissWindow
}{byDC: make(map[int]cfPoolMissWindow)}

func countCFPoolMiss(dc int, now time.Time) int64 {
	stats.cfPoolMisses.Add(1)
	cfPoolMissSamples.Lock()
	defer cfPoolMissSamples.Unlock()
	window := cfPoolMissSamples.byDC[dc]
	if window.started.IsZero() || now.Sub(window.started) >= 30*time.Second || now.Before(window.started) {
		window = cfPoolMissWindow{started: now}
	}
	window.count++
	cfPoolMissSamples.byDC[dc] = window
	return window.count
}

func newMtProtoCFProxyConnector() *mtProtoCFProxyConnector {
	return &mtProtoCFProxyConnector{
		dialContext: defaultMtProtoCFProxyDial,
	}
}

func (c *mtProtoCFProxyConnector) Capability() mtproxyfrontend.OutboundCapability {
	return mtproxyfrontend.OutboundCapability{
		Status:          mtProtoRouteChainReady,
		SelectedBackend: mtProtoCFProxyBackend,
	}
}

func (c *mtProtoCFProxyConnector) Connect(
	ctx context.Context,
	request mtproxyfrontend.OutboundRequest,
) (net.Conn, mtproxyfrontend.OutboundResult) {
	result := mtproxyfrontend.OutboundResult{
		SelectedBackend: mtProtoCFProxyBackend,
	}
	if err := ctx.Err(); err != nil {
		result.Reason = "cancelled"
		result.Err = err
		return nil, result
	}

	if request.IsTestDC {
		result.Reason = "test_dc_cfproxy_unsupported"
		result.Err = fmt.Errorf("CF proxy route is not used for Telegram test DC")
		return nil, result
	}

	settings := getRuntimeSettings()
	if settings.PolicyPresent && !settings.AllowCFProxy {
		result.Reason = "disabled_by_policy"
		result.Err = fmt.Errorf("CF proxy route disabled by policy")
		return nil, result
	}
	if !settings.CF.Enabled {
		result.Reason = "cfproxy_disabled"
		result.Err = fmt.Errorf("CF proxy route disabled")
		return nil, result
	}

	wsDC := effectiveWSHostDC(request.DCID)
	selection := cfPool.SelectionForDC(wsDC)
	if len(selection.Candidates) == 0 {
		result.CFPoolMissCount = countCFPoolMiss(wsDC, time.Now())
		result.Reason = "cfproxy_unavailable"
		result.Err = cfPoolUnavailableError(wsDC, selection)
		return nil, result
	}
	stats.cfPoolHits.Add(1)

	var lastErr error
	var lastReason string
	attempted := false
	skipCachedUpstream := false
	for _, candidate := range selection.Candidates {
		if err := ctx.Err(); err != nil {
			result.Reason = "cancelled"
			result.Err = err
			return nil, result
		}
		if skipCachedUpstream && candidate.Source == tgwsroute.CFDomainSourceCachedUpstream {
			if logDebug != nil {
				logDebug.Printf("MTProto CF cached upstream skipped domain=%s reason=previous_dns_failure", candidate.Domain)
			}
			continue
		}
		baseDomain := candidate.Domain
		if !cfPool.TryReserve(wsDC, baseDomain) {
			continue
		}
		if err := ctx.Err(); err != nil {
			cfPool.ReleaseReservation(wsDC, baseDomain)
			result.Reason = "cancelled"
			result.Err = err
			return nil, result
		}
		attempted = true
		host := cfProxyHost(wsDC, baseDomain)
		prefix := fmt.Sprintf("[MTProto] DC%d%s cfproxy", request.DCID, mediaTag(request.IsMedia))
		logMtProtoRouteAttempt(
			ctx,
			request,
			routeCFProxyWS,
			"host=%s pool_domain=%s source=%s",
			host,
			baseDomain,
			candidate.Source,
		)

		var ws mtProtoFrameSocket
		var err error
		if c.dialContext != nil {
			ws, err = c.dialContext(ctx, host, "/apiws", prefix)
		} else {
			ws, err = c.dial(host, "/apiws", prefix)
		}
		if cancelled := ctx.Err(); cancelled != nil {
			if ws != nil {
				ws.Close()
			}
			cfPool.ReleaseReservation(wsDC, baseDomain)
			result.Reason = "cancelled"
			result.Err = cancelled
			return nil, result
		}
		if err != nil {
			lastErr = err
			kind := classifyCFFailure(err)
			lastReason = string(kind)
			health := cfPool.MarkFailure(wsDC, baseDomain, kind, 0)
			if logInfo != nil {
				logInfo.Printf("MTProto CF domain cooldown domain=%s reason=%s until=%s",
					baseDomain, kind, formatCooldownUntil(health.CooldownUntil))
			}
			if candidate.Source == tgwsroute.CFDomainSourceCachedUpstream && tgwsroute.IsCFDNSFailure(kind) {
				skipCachedUpstream = true
				if logInfo != nil {
					logInfo.Printf("MTProto CF cached upstream fast-skip enabled after dns failure domain=%s", baseDomain)
				}
			}
			continue
		}

		stopCancel := context.AfterFunc(ctx, ws.Close)
		stream, err := mtProtoWebSocketConn(ws, request.RelayInit, host)
		stopCancel()
		if cancelled := ctx.Err(); cancelled != nil {
			ws.Close()
			cfPool.ReleaseReservation(wsDC, baseDomain)
			result.Reason = "cancelled"
			result.Err = cancelled
			return nil, result
		}
		if err != nil {
			ws.Close()
			lastErr = err
			lastReason = "packet_splitter_failed"
			cfPool.MarkFailure(wsDC, baseDomain, tgwsroute.CFFailureWebSocket, 0)
			continue
		}

		cfPool.MarkSuccess(wsDC, baseDomain, 0)
		cfPool.ReleaseReservation(wsDC, baseDomain)
		stats.connectionsCfProxy.Add(1)
		result.ActualBackend = mtProtoCFProxyBackend
		result.Reason = "connected"
		return stream, result
	}

	stats.cfPoolRefillErrors.Add(1)
	if !attempted {
		result.CFPoolMissCount = countCFPoolMiss(wsDC, time.Now())
		result.Reason = "cfproxy_unavailable"
		result.Err = cfPoolUnavailableError(wsDC, cfPool.AvailabilityForDC(wsDC))
		return nil, result
	}
	if lastReason == "" {
		lastReason = "cfproxy_all_domains_failed"
	}
	result.Reason = lastReason
	if lastErr == nil {
		lastErr = fmt.Errorf("CF proxy domains failed")
	}
	result.Err = lastErr
	return nil, result
}

func cfPoolUnavailableError(dc int, selection tgwsroute.CFDomainSelection) error {
	return fmt.Errorf("CF proxy pool unavailable dc=%d eligible=%d cooldown=%d in_flight=%d retry_after_seconds=%.1f",
		dc, len(selection.Candidates), len(selection.SkippedCooldown), len(selection.SkippedInFlight), selection.RetryAfterSeconds)
}

func defaultMtProtoCFProxyDial(ctx context.Context, domain, path, logPrefix string) (mtProtoFrameSocket, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	dialer := compatibleWebSocketTLSDialer{ctx: ctx}
	ws, err := dialer.connect(domain, domain, path, 10)
	if err == nil {
		return ws, nil
	}
	if cancelled := ctx.Err(); cancelled != nil {
		return nil, cancelled
	}
	logDomainConnectFailure(logPrefix, domain, domain, err)

	resolved, resolveErr := resolvePreferredIPsContext(ctx, domain, 10)
	if resolveErr != nil {
		return nil, err
	}

	lastErr := err
	for _, ip := range resolved.Preferred() {
		if cancelled := ctx.Err(); cancelled != nil {
			return nil, cancelled
		}
		ws, lastErr = dialer.connect(ip, domain, path, 10)
		if lastErr == nil {
			return ws, nil
		}
		if cancelled := ctx.Err(); cancelled != nil {
			return nil, cancelled
		}
		logDomainConnectFailure(logPrefix, domain, ip, lastErr)
		var wsErr *WsHandshakeError
		if errors.As(lastErr, &wsErr) && wsErr.IsRedirect() {
			break
		}
	}
	return nil, lastErr
}

func mtProtoWebSocketConn(ws mtProtoFrameSocket, relayInit []byte, remote string) (net.Conn, error) {
	return mtProtoWebSocketConnWithFraming(ws, relayInit, remote, true)
}

// Chunk Relay preserves its existing TCP-read WebSocket message framing.
// This is deliberately separate from the packet-aware Legacy Worker path.
func mtProtoWorkerWebSocketConn(ws mtProtoFrameSocket, relayInit []byte, remote string) (net.Conn, error) {
	return mtProtoWebSocketConnWithFraming(ws, relayInit, remote, false)
}

// Flowseal's Legacy /apiws bridge uses MsgSplitter for Worker fallback:
// assemble complete MTProto transport packets before emitting WS messages.
// Never apply this framing to the production Chunk Relay transport.
func mtProtoLegacyWorkerWebSocketConn(ws mtProtoFrameSocket, relayInit []byte, remote string) (net.Conn, error) {
	return mtProtoWebSocketConnWithFraming(ws, relayInit, remote, true)
}

func mtProtoWebSocketConnWithFraming(
	ws mtProtoFrameSocket,
	relayInit []byte,
	remote string,
	splitOutboundPackets bool,
) (net.Conn, error) {
	ws = wrapMtProtoFrameSocket(ws)
	if err := ws.Send(relayInit); err != nil {
		return nil, fmt.Errorf("write relay init to WebSocket %s: %w", remote, err)
	}

	var splitter *MsgSplitter
	if splitOutboundPackets {
		var err error
		splitter, err = newMsgSplitter(relayInit)
		if err != nil {
			return nil, fmt.Errorf("create packet splitter for WebSocket %s: %w", remote, err)
		}
	}

	return &mtProtoWebSocketStream{
		socket:   ws,
		splitter: splitter,
		local:    mtProtoNetAddr("mtproto-local"),
		remote:   mtProtoNetAddr(strings.TrimSpace(remote)),
	}, nil
}

var _ mtproxyfrontend.OutboundConnector = (*mtProtoDirectWSConnector)(nil)
var _ mtproxyfrontend.OutboundConnector = (*mtProtoCFProxyConnector)(nil)
