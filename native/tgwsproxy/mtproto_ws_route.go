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
	resolveTarget mtProtoTargetResolver
	dial          mtProtoDirectWSDial
}

func newMtProtoDirectWSConnector() *mtProtoDirectWSConnector {
	return &mtProtoDirectWSConnector{
		resolveTarget: mtProtoTargetForDC,
		dial:          defaultMtProtoDirectWSDial,
	}
}

func (c *mtProtoDirectWSConnector) Capability() mtproxyfrontend.OutboundCapability {
	return mtproxyfrontend.OutboundCapability{
		Status:          mtProtoRouteChainReady,
		SelectedBackend: mtProtoDirectWSBackend,
	}
}

func (c *mtProtoDirectWSConnector) Connect(
	_ context.Context,
	request mtproxyfrontend.OutboundRequest,
) (net.Conn, mtproxyfrontend.OutboundResult) {
	result := mtproxyfrontend.OutboundResult{
		SelectedBackend: mtProtoDirectWSBackend,
	}

	var target string
	var ok bool
	if request.IsTestDC {
		target, _, ok = mtProtoTestTargetForDC(request.DCID)
	} else {
		target, _, ok = c.resolveTarget(request.DCID)
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

	now := monoNow()
	if directIPCoolingDown(target, now) && directIPCooldownFallbackAvailable(getRuntimeSettings()) {
		result.Reason = "direct_ip_cooldown"
		result.Err = fmt.Errorf("direct WebSocket target %s is on IP cooldown", target)
		return nil, result
	}

	path := "/apiws"
	if request.IsTestDC {
		path = "/apiws_test"
	}

	var lastErr error
	timedOut := false
	for _, domain := range domains {
		prefix := fmt.Sprintf("[MTProto] DC%d%s direct_ws", request.DCID, mediaTag(request.IsMedia))
		logMtProtoRouteAttempt(
			request,
			routeDirectWS,
			"target=%s domain=%s",
			target,
			domain,
		)

		ws, err := c.dial(target, domain, path, 10)
		if err != nil {
			lastErr = err
			if directWSErrorTimedOut(err) {
				timedOut = true
			}
			var wsErr *WsHandshakeError
			if errors.As(err, &wsErr) && wsErr.IsRedirect() {
				continue
			}
			logDomainConnectFailure(prefix, domain, target, err)
			continue
		}

		stream, err := mtProtoWebSocketConn(ws, request.RelayInit, domain)
		if err != nil {
			ws.Close()
			lastErr = err
			continue
		}
		stats.connectionsWs.Add(1)
		markDirectIPSuccess(target)
		result.ActualBackend = mtProtoDirectWSBackend
		result.Reason = "connected"
		return stream, result
	}

	result.Reason = "direct_ws_connect_failed"
	if timedOut {
		markDirectIPTimeout(target, now)
		result.Reason = "direct_ws_ip_timeout"
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("direct WebSocket failed for dc %d", request.DCID)
	}
	result.Err = lastErr
	return nil, result
}

func defaultMtProtoDirectWSDial(targetIP, domain, path string, timeout float64) (mtProtoFrameSocket, error) {
	ws, err := wsConnect(targetIP, domain, path, timeout)
	if err == nil {
		return ws, nil
	}
	if !shouldTryDomainDial(err) {
		return nil, err
	}

	resolvedWS, _, _, fallbackErr := wsConnectViaResolvedDomain(domain, path, timeout)
	if fallbackErr == nil {
		return resolvedWS, nil
	}
	return nil, fallbackErr
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
		attempted = true
		host := cfProxyHost(wsDC, baseDomain)
		prefix := fmt.Sprintf("[MTProto] DC%d%s cfproxy", request.DCID, mediaTag(request.IsMedia))
		logMtProtoRouteAttempt(
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

		stream, err := mtProtoWebSocketConn(ws, request.RelayInit, host)
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
	dialer := compatibleWebSocketTLSDialer{ctx: ctx}
	ws, err := dialer.connect(domain, domain, path, 10)
	if err == nil {
		return ws, nil
	}
	logDomainConnectFailure(logPrefix, domain, domain, err)

	resolved, resolveErr := resolvePreferredIPs(domain, 10)
	if resolveErr != nil {
		return nil, err
	}

	lastErr := err
	for _, ip := range resolved.Preferred() {
		ws, lastErr = dialer.connect(ip, domain, path, 10)
		if lastErr == nil {
			return ws, nil
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
