package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"
	"sync/atomic"

	"tg-ws-proxy/tgwsroute"
)

type connectionMode = tgwsroute.ConnectionMode

const (
	modeAuto               = tgwsroute.ModeAuto
	modeDirectWithFallback = tgwsroute.ModeDirectWithFallback
	modeWorkerFirst        = tgwsroute.ModeWorkerFirst
	modeCFFirst            = tgwsroute.ModeCFFirst
	modeWorkerOnly         = tgwsroute.ModeWorkerOnly
	modeCFOnly             = tgwsroute.ModeCFOnly
	modeDirectOnly         = tgwsroute.ModeDirectOnly
)

type routeKind = tgwsroute.RouteKind

const (
	routeDirectWS    = tgwsroute.RouteDirectWS
	routeCFWorkerWS  = tgwsroute.RouteCFWorkerWS
	routeCFProxyWS   = tgwsroute.RouteCFProxyWS
	routeTCPFallback = tgwsroute.RouteTCPFallback
)

type workerConfig struct {
	Enabled bool
	Domain  string
}

type runtimeSettings struct {
	Mode                connectionMode
	CF                  cfProxyConfig
	Worker              workerConfig
	CFManualDomains     []string // user CF domains override cached and built-in pool entries
	CFCachedUpstream    []string // cached Flowseal upstream domains
	NetworkProfileID    string
	NetworkProfileType  string
	NetworkProfileLabel string
	AdaptiveRouteStats  string
	AutoStrategy        string
	// Per-network route policy (from Android @route_* tokens).
	PolicyPresent bool
	AllowDirect   bool
	AllowWorker   bool
	AllowCFProxy  bool
	AllowTCP      bool
	Preferred     routeKind // empty means "no explicit preferred"
	AllowFallback bool
	PolicyGen     uint64
}

func (s runtimeSettings) workerRouteAvailable() bool {
	if !s.Worker.Enabled || s.Worker.Domain == "" {
		return false
	}
	for _, route := range routesForMode(s.Mode, s, false) {
		if route == routeCFWorkerWS {
			return true
		}
	}
	return false
}

var (
	runtimeCfg   = runtimeSettings{Mode: modeDirectWithFallback, CF: cfProxyConfig{Domain: defaultCfProxyDomain, Enabled: true}}
	runtimeCfgMu sync.RWMutex
	cfPool       = newCFDomainPool()
)

func init() {
	cfPool.SetBuiltinDomains(tgwsroute.BuiltInCFDomains())
}

var policyGenCounter atomic.Uint64

func setRuntimeSettings(cfg runtimeSettings) {
	prev := getRuntimeSettings()
	cfg.CF = normalizeCfProxyConfig(cfg.CF)
	cfg.Worker.Domain = NormalizeWorkerDomain(cfg.Worker.Domain)
	cfg.CFManualDomains = tgwsroute.NormalizeCFDomains(cfg.CFManualDomains)
	cfg.PolicyGen = policyGenCounter.Add(1)
	runtimeCfgMu.Lock()
	runtimeCfg = cfg
	runtimeCfgMu.Unlock()
	resetProxyRouteDisplayState()
	cfPool.SetManualDomains(cfg.CFManualDomains)
	cfPool.SetCachedUpstreamDomains(cfg.CFCachedUpstream)
	applyAdaptiveProfile(cfg)
	logPolicyChange(prev, cfg)
}

func parsePreferredRoute(raw string) routeKind {
	s := strings.ToLower(strings.TrimSpace(raw))
	switch s {
	case "direct_ws", "direct":
		return routeDirectWS
	case "worker_ws", "cf_worker_ws", "worker":
		return routeCFWorkerWS
	case "cf_proxy_ws", "cf":
		return routeCFProxyWS
	case "tcp_fallback", "tcp":
		return routeTCPFallback
	default:
		return ""
	}
}

func allowedRoutesList(s runtimeSettings) []routeKind {
	if !s.PolicyPresent {
		return nil
	}
	out := make([]routeKind, 0, 4)
	if s.AllowDirect {
		out = append(out, routeDirectWS)
	}
	if s.AllowWorker {
		out = append(out, routeCFWorkerWS)
	}
	if s.AllowCFProxy {
		out = append(out, routeCFProxyWS)
	}
	if s.AllowTCP {
		out = append(out, routeTCPFallback)
	}
	return out
}

func routeKindStrings(routes []routeKind) []string {
	out := make([]string, 0, len(routes))
	for _, r := range routes {
		out = append(out, string(r))
	}
	return out
}

func logPolicyChange(old, cur runtimeSettings) {
	if !cur.PolicyPresent {
		return
	}
	oldAllowed := strings.Join(routeKindStrings(allowedRoutesList(old)), "|")
	newAllowed := strings.Join(routeKindStrings(allowedRoutesList(cur)), "|")
	if oldAllowed == "" {
		oldAllowed = "none"
	}
	if newAllowed == "" {
		newAllowed = "none"
	}
	logInfo.Printf("Policy changed generation=%d oldRoutes=%s newRoutes=%s preferred=%s fallback=%t",
		cur.PolicyGen, oldAllowed, newAllowed, cur.Preferred, cur.AllowFallback)

	// Close pools for disabled routes (best-effort). This prevents "ghost" worker/direct usage.
	if !cur.AllowWorker {
		workerPool.CloseAll()
	}
	if !cur.AllowDirect {
		wsPool.CloseAll()
	}
}

func getRuntimeSettings() runtimeSettings {
	runtimeCfgMu.RLock()
	defer runtimeCfgMu.RUnlock()
	return runtimeCfg
}

func parseConnectionMode(raw string) (connectionMode, bool) {
	return tgwsroute.ParseConnectionMode(raw)
}

func legacyModeFromCF(cfg cfProxyConfig) connectionMode {
	return tgwsroute.LegacyModeFromCF(tgwsroute.CFProxyFlags{
		Enabled:  cfg.Enabled,
		Priority: cfg.Priority,
		Only:     cfg.Only,
	})
}

func NormalizeWorkerDomain(raw string) string {
	return tgwsroute.NormalizeWorkerDomain(raw)
}

func workerDomainLooksValid(domain string) bool {
	if domain == "" || strings.Contains(domain, " ") {
		return false
	}
	if strings.Contains(domain, "/apiws") {
		return false
	}
	return strings.Contains(domain, ".")
}

func buildWorkerWSPath(dcID int, dcIP string, media bool) string {
	return tgwsroute.BuildWorkerWSPath(dcID, dcIP, media)
}

func buildWorkerWSURL(workerDomain string, dcID int, dcIP string, media bool) string {
	return tgwsroute.BuildWorkerWSURL(workerDomain, dcID, dcIP, media)
}

func lookupTelegramDC(dst string) (tgwsroute.TelegramDCInfo, bool) {
	return tgwsroute.LookupTelegramDC(dst)
}

func isTelegramLikeIP(dst string) bool {
	return tgwsroute.IsTelegramLikeIP(dst)
}

func isRestrictedMode(mode connectionMode) bool {
	return tgwsroute.IsRestrictedMode(mode)
}

func blocksDirectPassthrough(mode connectionMode, dst string, mapped bool) bool {
	return tgwsroute.BlocksDirectPassthrough(mode, dst, mapped)
}

func toRouteSettings(settings runtimeSettings) tgwsroute.RouteSettings {
	return tgwsroute.RouteSettings{
		Mode: settings.Mode,
		CF: tgwsroute.CFProxyFlags{
			Enabled:  settings.CF.Enabled,
			Priority: settings.CF.Priority,
			Only:     settings.CF.Only,
		},
		Worker: tgwsroute.WorkerSettings{
			Enabled: settings.Worker.Enabled,
			Domain:  settings.Worker.Domain,
		},
	}
}

func telegramDCTargetIP(dc int, fallbackDst string) string {
	if ip, ok := dcDefaultIP[dc]; ok && ip != "" {
		return ip
	}
	return fallbackTarget(dc, fallbackDst)
}

func effectiveWSHostDC(dc int) int {
	if mapped, ok := dcOverrides[dc]; ok {
		return mapped
	}
	return dc
}

// ---------------------------------------------------------------------------
// CF domain pool
// ---------------------------------------------------------------------------

func newCFDomainPool() *tgwsroute.CFDomainPool {
	return tgwsroute.NewCFDomainPool(monoNow)
}

// ---------------------------------------------------------------------------
// Route planning
// ---------------------------------------------------------------------------

func routesForMode(mode connectionMode, settings runtimeSettings, skipDirect bool) []routeKind {
	base := tgwsroute.RoutesForMode(mode, toRouteSettings(settings), skipDirect)
	return filterRoutesByPolicy(settings, base, "routesForMode")
}

func primaryRoutesBeforeDirectWS(mode connectionMode, settings runtimeSettings) []routeKind {
	return tgwsroute.PrimaryRoutesBeforeDirectWS(mode, toRouteSettings(settings))
}

func isRouteAllowedByPolicy(settings runtimeSettings, r routeKind) bool {
	if !settings.PolicyPresent {
		return true
	}
	switch r {
	case routeDirectWS:
		return settings.AllowDirect
	case routeCFWorkerWS:
		return settings.AllowWorker
	case routeCFProxyWS:
		return settings.AllowCFProxy
	case routeTCPFallback:
		return settings.AllowTCP
	default:
		return false
	}
}

func filterRoutesByPolicy(settings runtimeSettings, routes []routeKind, reason string) []routeKind {
	if !settings.PolicyPresent {
		return routes
	}
	if len(routes) == 0 {
		return routes
	}
	before := strings.Join(routeKindStrings(routes), "|")
	allowed := strings.Join(routeKindStrings(allowedRoutesList(settings)), "|")
	out := make([]routeKind, 0, len(routes))
	var dropped []routeKind
	for _, r := range routes {
		if isRouteAllowedByPolicy(settings, r) {
			out = append(out, r)
		} else {
			dropped = append(dropped, r)
		}
	}
	after := strings.Join(routeKindStrings(out), "|")
	if after == "" {
		after = "none"
	}
	if allowed == "" {
		allowed = "none"
	}
	if len(dropped) > 0 {
		logInfo.Printf("Route policy filter reason=%s allowed=%s before=%s after=%s dropped=%s",
			reason, allowed, before, after, strings.Join(routeKindStrings(dropped), "|"))
	}
	return out
}

// ---------------------------------------------------------------------------
// Worker + CF proxy dial
// ---------------------------------------------------------------------------

func cfWorkerFallback(ctx context.Context, client net.Conn, init []byte, label string, dc int, isMedia bool, splitter *MsgSplitter) (bool, string) {
	noteRouteConnectStarted(routeCFWorkerWS)
	settings := getRuntimeSettings()
	if settings.PolicyPresent && !settings.AllowWorker {
		logWarn.Printf("[%s] DC%d%s Blocked endpoint build route=cf_worker_ws reason=disabled_by_policy allowed=%s",
			label, dc, mediaTag(isMedia), strings.Join(routeKindStrings(allowedRoutesList(settings)), "|"))
		return false, "disabled_by_policy"
	}
	domain := settings.Worker.Domain
	if !settings.Worker.Enabled || domain == "" {
		logDebug.Printf("[%s] DC%d skipping Worker route: domain is empty", label, dc)
		return false, "worker_disabled_or_empty_domain"
	}

	mTag := mediaTag(isMedia)
	dstIP := telegramDCTargetIP(dc, "")
	path := buildWorkerWSPath(dc, dstIP, isMedia)
	wsURL := buildWorkerWSURL(domain, dc, dstIP, isMedia)

	logDebug.Printf("Worker route enabled domain=%s", domain)
	logDebug.Printf("[%s] DC%d%s Building Worker endpoint for %s", label, dc, mTag, wsURL)
	logDebug.Printf("[%s] DC%d%s Trying Worker WS endpoint", label, dc, mTag)

	prefix := fmt.Sprintf("[%s] DC%d%s cfworker", label, dc, mTag)
	var ws *RawWebSocket
	var lastErr error
	poolKey := WorkerPoolKey{DC: dc, WorkerDomain: domain, Dst: dstIP, Media: isMedia}

	ws = workerPool.Get(poolKey)
	if ws != nil {
		logDebug.Printf("[%s] DC%d%s Worker pool hit", label, dc, mTag)
	} else {
		logDebug.Printf("[%s] DC%d%s cfworker hostname dial start host=%s", label, dc, mTag, domain)
		ws, lastErr = wsConnect(domain, domain, path, 10)
		if lastErr != nil {
			logDomainConnectFailure(prefix, domain, domain, lastErr)
			resolved, err := resolvePreferredIPs(domain, 10)
			if err == nil {
				for _, ip := range resolved.Preferred() {
					ws, lastErr = wsConnect(ip, domain, path, 10)
					if lastErr == nil {
						break
					}
					logDomainConnectFailure(prefix, domain, ip, lastErr)
				}
			}
		}
	}

	if ws == nil {
		reason := "worker_ws_connect_failed"
		if lastErr != nil {
			reason = fmt.Sprintf("worker_ws_connect_failed: %v", lastErr)
		}
		logWarn.Printf("[%s] DC%d%s Worker WS failed: %s", label, dc, mTag, reason)
		noteRouteConnectFailed(routeCFWorkerWS, reason)
		if settings.Mode == modeAuto || settings.Mode == modeDirectWithFallback {
			recordAdaptiveFailure(routeCFWorkerWS, dc, isMedia, reason, 0)
		}
		return false, reason
	}

	logInfo.Printf("[%s] DC%d%s Worker WS connected host=%s", label, dc, mTag, domain)
	noteRouteConnectSucceeded(routeCFWorkerWS)
	noteActiveRoute(routeCFWorkerWS)
	stats.connectionsCfWorker.Add(1)

	if err := ws.Send(init); err != nil {
		ws.Close()
		return false, fmt.Sprintf("worker_first_write_failed: %v", err)
	}

	summary := bridgeWS(ctx, client, ws, label, dc, domain, 443, isMedia, splitter)
	recordAdaptiveSessionSuccess(routeCFWorkerWS, dc, isMedia, 0, summary.String(), settings)
	return true, summary.String()
}

func cfProxyFallbackWithPool(ctx context.Context, client net.Conn, init []byte, label string, dc int, isMedia bool, splitter *MsgSplitter) (bool, string) {
	noteRouteConnectStarted(routeCFProxyWS)
	settings := getRuntimeSettings()
	if settings.PolicyPresent && !settings.AllowCFProxy {
		return false, "disabled_by_policy"
	}
	if !settings.CF.Enabled {
		return false, "cfproxy_disabled"
	}

	mTag := mediaTag(isMedia)
	wsDC := effectiveWSHostDC(dc)
	selection := cfPool.SelectionForDC(wsDC)
	for _, skipped := range selection.SkippedCooldown {
		logDebug.Printf("[%s] DC%d%s CF domain skipped domain=%s reason=cooldown until=%s",
			label, dc, mTag, skipped.Domain, formatCooldownUntil(skipped.CooldownUntil))
	}
	if len(selection.Candidates) == 0 {
		stats.cfPoolMisses.Add(1)
		logWarn.Printf("[%s] DC%d%s CF pool exhausted mode=%s", label, dc, mTag, settings.Mode)
		return false, "cfproxy_unavailable"
	}
	stats.cfPoolHits.Add(1)

	for _, candidate := range selection.Candidates {
		baseDomain := candidate.Domain
		host := cfProxyHost(wsDC, baseDomain)
		url := fmt.Sprintf("wss://%s/apiws", host)
		logInfo.Printf("[%s] DC%d%s CF domain selector source=%s domain=%s score=%d",
			label, dc, mTag, candidate.Source, baseDomain, candidate.Score)
		logDebug.Printf("[%s] DC%d%s -> CF proxy %s (pool domain=%s)", label, dc, mTag, url, baseDomain)

		ok, reason, failureKind, latencyMs := cfProxyDialHost(ctx, client, init, label, dc, isMedia, splitter, host, baseDomain)
		if ok {
			cfPool.MarkSuccess(wsDC, baseDomain, latencyMs)
			noteRouteConnectSucceeded(routeCFProxyWS)
			recordAdaptiveSessionSuccess(routeCFProxyWS, dc, isMedia, latencyMs, reason, settings)
			logInfo.Printf("[%s] DC%d%s CF proxy selected domain=%s", label, dc, mTag, baseDomain)
			return true, reason
		}
		health := cfPool.MarkFailure(baseDomain, failureKind, latencyMs)
		logWarn.Printf("[%s] DC%d%s CF domain failed domain=%s reason=%s", label, dc, mTag, baseDomain, failureKind)
		logInfo.Printf("[%s] DC%d%s CF domain cooldown domain=%s reason=%s until=%s",
			label, dc, mTag, baseDomain, failureKind, formatCooldownUntil(health.CooldownUntil))
		logDebug.Printf("[%s] DC%d%s CF proxy failed domain=%s detail=%s", label, dc, mTag, baseDomain, reason)
	}

	logWarn.Printf("[%s] DC%d%s CF pool exhausted mode=%s", label, dc, mTag, settings.Mode)
	stats.cfPoolRefillErrors.Add(1)
	noteRouteConnectFailed(routeCFProxyWS, "cfproxy_all_domains_failed")
	return false, "cfproxy_all_domains_failed"
}

func cfProxyDialHost(ctx context.Context, client net.Conn, init []byte, label string, dc int, isMedia bool, splitter *MsgSplitter, host, baseDomain string) (bool, string, tgwsroute.CFFailureKind, int64) {
	mTag := mediaTag(isMedia)
	started := time.Now()

	resolved, err := resolvePreferredIPs(host, 10)
	var candidates []string
	if err == nil {
		candidates = resolved.Preferred()
	}

	prefix := fmt.Sprintf("[%s] DC%d%s cfproxy", label, dc, mTag)
	var ws *RawWebSocket
	var lastErr error
	usedTarget := "hostname"

	ws, lastErr = wsConnect(host, host, "/apiws", 10)
	if lastErr != nil {
		logDomainConnectFailure(prefix, host, host, lastErr)
	}

	for _, ip := range candidates {
		if ws != nil {
			break
		}
		ws, lastErr = wsConnect(ip, host, "/apiws", 10)
		if lastErr == nil {
			usedTarget = ip
			break
		}
		logDomainConnectFailure(prefix, host, ip, lastErr)
		var wsErr *WsHandshakeError
		if errors.As(lastErr, &wsErr) && wsErr.IsRedirect() {
			break
		}
	}

	if ws == nil {
		if lastErr != nil {
			return false, fmt.Sprintf("ws_connect_failed: %v", lastErr), classifyCFFailure(lastErr), elapsedMillis(started)
		}
		return false, "ws_connect_failed", tgwsroute.CFFailureWebSocket, elapsedMillis(started)
	}

	logInfo.Printf("[%s] DC%d%s cfproxy connected host=%s via=%s", label, dc, mTag, host, usedTarget)
	noteActiveRoute(routeCFProxyWS)
	stats.connectionsCfProxy.Add(1)

	if err := ws.Send(init); err != nil {
		ws.Close()
		return false, fmt.Sprintf("first_client_to_ws_write_failed: %v", err), tgwsroute.CFFailureWebSocket, elapsedMillis(started)
	}

	summary := bridgeWS(ctx, client, ws, label, dc, host, 443, isMedia, splitter)
	return true, summary.String(), "", elapsedMillis(started)
}

func runRouteChain(ctx context.Context, client net.Conn, init []byte, label string, dc int, isMedia bool, dst string, port int, splitter *MsgSplitter, routes []routeKind) bool {
	settings := getRuntimeSettings()
	mTag := mediaTag(isMedia)
	target := fallbackTarget(dc, dst)
	cfFailed := false
	var lastFailRoute routeKind
	var lastFailReason string

	if len(routes) > 0 {
		noteRouteSelected(routes[0])
	}

	if settings.Mode == modeWorkerOnly && (!settings.Worker.Enabled || settings.Worker.Domain == "") {
		logWarn.Printf("[%s] DC%d%s Worker domain is empty in worker-only mode", label, dc, mTag)
		return false
	}
	if settings.Mode == modeCFOnly && !settings.CF.Enabled {
		logWarn.Printf("[%s] DC%d%s CF proxy is unavailable in cf-only mode", label, dc, mTag)
		return false
	}

	for _, route := range routes {
		if lastFailRoute != "" {
			noteFallbackActivated(route, lastFailReason)
		}
		switch route {
		case routeCFWorkerWS:
			if cfFailed {
				logInfo.Printf("[%s] DC%d%s CF pool fallback to Worker", label, dc, mTag)
			}
			if !settings.Worker.Enabled || settings.Worker.Domain == "" {
				if settings.Mode == modeWorkerOnly {
					logWarn.Printf("[%s] DC%d%s Worker only mode but Worker domain is empty", label, dc, mTag)
					return false
				}
				logInfo.Printf("[%s] DC%d%s Skipping Worker route: domain is empty", label, dc, mTag)
				continue
			}
			if ok, reason := cfWorkerFallback(ctx, client, init, label, dc, isMedia, splitter); ok {
				logInfo.Printf("[%s] DC%d%s Worker closed: reason=%s", label, dc, mTag, reason)
				noteConnectionClosed(routeCFWorkerWS)
				return true
			}
			lastFailRoute = routeCFWorkerWS
			lastFailReason = "worker_failed"
			if settings.Mode == modeAuto || settings.Mode == modeDirectWithFallback {
				recordAdaptiveFailure(routeCFWorkerWS, dc, isMedia, "worker_failed", 0)
			}
		case routeCFProxyWS:
			ok, cfReason := cfProxyFallbackWithPool(ctx, client, init, label, dc, isMedia, splitter)
			if ok {
				logInfo.Printf("[%s] DC%d%s CF proxy closed: reason=%s", label, dc, mTag, cfReason)
				noteConnectionClosed(routeCFProxyWS)
				return true
			}
			lastFailRoute = routeCFProxyWS
			lastFailReason = cfReason
			if settings.Mode == modeCFOnly {
				if cfReason == "cfproxy_unavailable" || cfReason == "cfproxy_all_domains_failed" {
					logWarn.Printf("[%s] DC%d%s CF proxy is unavailable in cf-only mode", label, dc, mTag)
				} else {
					logWarn.Printf("[%s] DC%d%s CF only mode failed: %s", label, dc, mTag, cfReason)
				}
				return false
			}
			if settings.Mode != modeCFOnly {
				recordAdaptiveFailure(routeCFProxyWS, dc, isMedia, "cfproxy_failed", 0)
			}
			cfFailed = true
		case routeTCPFallback:
			noteRouteConnectStarted(routeTCPFallback)
			logInfo.Printf("[%s] DC%d%s -> TCP fallback to %s", label, dc, mTag, joinAddr(target, port))
			if tcpFallback(ctx, client, target, port, init, label, dc, isMedia) {
				noteRouteConnectSucceeded(routeTCPFallback)
				noteActiveRoute(routeTCPFallback)
				recordAdaptiveSessionSuccess(routeTCPFallback, dc, isMedia, 0, "", settings)
				return true
			}
			noteRouteConnectFailed(routeTCPFallback, "tcp_failed")
			lastFailRoute = routeTCPFallback
			lastFailReason = "tcp_failed"
			recordAdaptiveFailure(routeTCPFallback, dc, isMedia, "tcp_failed", 0)
		case routeDirectWS:
			// Direct WS is handled by the caller before fallback chains.
			continue
		}
	}

	if settings.Mode == modeWorkerOnly {
		logWarn.Printf("[%s] DC%d%s no Worker route available in worker-only mode", label, dc, mTag)
	}
	logWarn.Printf("[%s] DC%d%s no fallback available", label, dc, mTag)
	return false
}

func classifyCFFailure(err error) tgwsroute.CFFailureKind {
	var wsErr *WsHandshakeError
	if errors.As(err, &wsErr) {
		switch {
		case wsErr.StatusCode == 429:
			return tgwsroute.CFFailureRateLimit
		case wsErr.StatusCode == 403:
			return tgwsroute.CFFailureForbidden
		case wsErr.StatusCode >= 500 && wsErr.StatusCode <= 599:
			return tgwsroute.CFFailureServer
		default:
			return tgwsroute.CFFailureWebSocket
		}
	}

	var stageErr *wsStageError
	if errors.As(err, &stageErr) {
		if ne, ok := stageErr.Err.(net.Error); ok && ne.Timeout() {
			return tgwsroute.CFFailureTimeout
		}
		switch stageErr.Stage {
		case "tls_handshake":
			return tgwsroute.CFFailureTLS
		case "ws_upgrade_write", "ws_upgrade_read":
			return tgwsroute.CFFailureWebSocket
		}
	}

	return tgwsroute.CFFailureUnknown
}

func formatCooldownUntil(until float64) string {
	if until <= 0 {
		return "none"
	}
	return time.Unix(int64(until), 0).Format(time.RFC3339)
}

func elapsedMillis(start time.Time) int64 {
	return time.Since(start).Milliseconds()
}

func shouldSkipDirectWS(dcKey [2]int, mode connectionMode) bool {
	if mode == modeCFOnly || mode == modeWorkerOnly {
		return true
	}
	now := monoNow()
	dcFailMu.RLock()
	failUntil := dcFailUntil[dcKey]
	dcFailMu.RUnlock()
	if mode == modeAuto && now < failUntil {
		return true
	}
	if mode == modeDirectWithFallback && now < failUntil {
		return true
	}
	return false
}

func logRuntimeRouteConfig() {
	settings := getRuntimeSettings()
	logInfo.Printf("  Connection mode: %s", settings.Mode)
	if settings.Worker.Enabled {
		if settings.Worker.Domain != "" {
			logInfo.Printf("  CF Worker:     enabled domain=%s", settings.Worker.Domain)
		} else {
			logInfo.Println("  CF Worker:     enabled but domain is empty")
		}
	} else {
		logInfo.Println("  CF Worker:     disabled")
	}
	if settings.CF.Enabled {
		mode := "fallback"
		if settings.CF.Only {
			mode = "only"
		} else if settings.CF.Priority {
			mode = "cf_first"
		}
		poolNote := "builtin pool"
		switch {
		case len(settings.CFManualDomains) > 0:
			poolNote = fmt.Sprintf("manual_domains=%d", len(settings.CFManualDomains))
		case len(settings.CFCachedUpstream) > 0:
			poolNote = fmt.Sprintf("cached_upstream=%d builtin_fallback", len(settings.CFCachedUpstream))
		}
		logInfo.Printf("  CF proxy:     enabled %s legacy_mode=%s", poolNote, mode)
	} else {
		logInfo.Println("  CF proxy:     disabled")
	}
}
