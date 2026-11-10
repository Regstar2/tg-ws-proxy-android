package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"
)

// This state is for MTProto direct_ws only, not direct_tcp or CF Proxy.
const (
	directWSMaxConcurrentDials = 2
	directWSMaxBackoff         = 5 * time.Minute
)

type directWSHealthKey struct {
	DCID   int
	Target string
}

type directWSHealthEntry struct {
	Generation          uint64
	InFlight            int
	ConsecutiveFailures int
	CooldownUntil       time.Time
}

var directWSHealth = struct {
	sync.Mutex
	entries map[directWSHealthKey]directWSHealthEntry
}{entries: make(map[directWSHealthKey]directWSHealthEntry)}

func beginDirectWSAttempt(dc int, target string, generation uint64, now time.Time) (func(), string) {
	key := directWSHealthKey{DCID: dc, Target: strings.TrimSpace(target)}
	directWSHealth.Lock()
	entry := directWSHealth.entries[key]
	if entry.Generation != generation && entry.InFlight == 0 {
		entry = directWSHealthEntry{Generation: generation}
	}
	switch {
	case now.Before(entry.CooldownUntil):
		directWSHealth.Unlock()
		return nil, "direct_ws_ip_cooldown"
	case entry.InFlight >= directWSMaxConcurrentDials:
		directWSHealth.Unlock()
		return nil, "direct_ws_dial_busy"
	}
	entry.InFlight++
	directWSHealth.entries[key] = entry
	directWSHealth.Unlock()

	return func() {
		directWSHealth.Lock()
		entry := directWSHealth.entries[key]
		if entry.InFlight > 0 {
			entry.InFlight--
		}
		directWSHealth.entries[key] = entry
		directWSHealth.Unlock()
	}, ""
}

func markDirectWSFailure(dc int, target string, generation uint64, reason string, now time.Time) {
	key := directWSHealthKey{DCID: dc, Target: strings.TrimSpace(target)}
	directWSHealth.Lock()
	entry := directWSHealth.entries[key]
	if entry.Generation != generation {
		directWSHealth.Unlock()
		return
	}
	entry.ConsecutiveFailures++
	delay := directTCPBackoff(reason, entry.ConsecutiveFailures)
	if delay > directWSMaxBackoff {
		delay = directWSMaxBackoff
	}
	entry.CooldownUntil = now.Add(delay)
	directWSHealth.entries[key] = entry
	directWSHealth.Unlock()
}

func markDirectWSSuccess(dc int, target string, generation uint64) {
	key := directWSHealthKey{DCID: dc, Target: strings.TrimSpace(target)}
	directWSHealth.Lock()
	entry := directWSHealth.entries[key]
	if entry.Generation == generation {
		entry.ConsecutiveFailures = 0
		entry.CooldownUntil = time.Time{}
		directWSHealth.entries[key] = entry
	}
	directWSHealth.Unlock()
}

// Use the same documented address Flowseal defaults to for DC2/DC4, but
// only as a second option when the existing default IP has failed. Explicit
// custom targets and Telegram test DCs are never overwritten.
func directWSTargetCandidates(dc int, configured string, allowAlternative bool) []string {
	targets := []string{configured}
	if !allowAlternative || (dc != 2 && dc != 4) ||
		configured != fallbackTarget(dc, "") ||
		configured == "149.154.167.220" {
		return targets
	}
	return append(targets, "149.154.167.220")
}

func directWSTCPFailureReason(err error) string {
	var stageErr *wsStageError
	if !errors.As(err, &stageErr) || stageErr.Stage != "tcp_dial" {
		return ""
	}
	reason, valid := classifyDirectTCPFailure(stageErr.Err)
	if !valid {
		return ""
	}
	return reason
}

// The Telegram DC IPv6 addresses are only Direct WS candidates, not proof
// that the WebSocket endpoint accepts TLS on that address. Try them only for
// built-in production targets; never replace manually configured targets or
// production addresses of Telegram test DCs.
func directWSKnownIPv6(targetIP, domain string) string {
	type entry struct {
		dc  int
		ip6 string
	}
	for _, candidate := range []entry{
		{1, "2001:b28:f23d:f001::a"},
		{2, "2001:67c:4e8:f002::a"},
		{3, "2001:b28:f23d:f003::a"},
		{4, "2001:67c:4e8:f004::a"},
		{5, "2001:b28:f23f:f005::a"},
	} {
		if targetIP != fallbackTarget(candidate.dc, "") {
			continue
		}
		normal := fmt.Sprintf("kws%d.web.telegram.org", candidate.dc)
		media := fmt.Sprintf("kws%d-1.web.telegram.org", candidate.dc)
		if domain == normal || domain == media {
			return candidate.ip6
		}
	}
	return ""
}

type directWSLookupFunc func(context.Context, string, float64) (resolvedIPs, error)
type directWSDialFunc func(context.Context, string, string, string, float64) (mtProtoFrameSocket, error)

// A single bounded Direct-only address experiment: resolve the genuine
// Telegram WS hostname FIRST and try at most one AAAA and one A result,
// with the known DC IPv6 address and the original IPv4 as independent
// candidates. Every connection keeps the original WS Host, TLS SNI and path.
//
// No relay, Cloudflare, Android VpnService or manual IP override is involved.
func dialDirectWSDualStack(
	ctx context.Context,
	targetIP, knownIPv6, domain, path string,
	timeout float64,
	lookup directWSLookupFunc,
	dial directWSDialFunc,
) (mtProtoFrameSocket, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if timeout <= 0 {
		timeout = 10
	}

	// DNS must not consume the whole per-address dial budget as it did when
	// the original pinned IPv4 timed out first.
	dnsCtx, stopDNS := context.WithTimeout(ctx, 1200*time.Millisecond)
	resolved, dnsErr := lookup(dnsCtx, domain, 1.2)
	stopDNS()
	if dnsErr != nil && logDebug != nil {
		logDebug.Printf("MTProto Direct DNS-first failed domain=%s: %v", domain, dnsErr)
	}

	candidates := make([]string, 0, 4)
	seen := make(map[string]bool)
	add := func(ip string) {
		if net.ParseIP(ip) == nil || seen[ip] {
			return
		}
		seen[ip] = true
		candidates = append(candidates, ip)
	}
	if len(resolved.IPv6) > 0 {
		add(resolved.IPv6[0])
	}
	add(knownIPv6)
	if len(resolved.IPv4) > 0 {
		add(resolved.IPv4[0])
	}
	add(targetIP)

	var lastErr error
	for i, ip := range candidates {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		remaining := time.Duration(timeout * float64(time.Second))
		if deadline, ok := ctx.Deadline(); ok {
			if until := time.Until(deadline); until < remaining {
				remaining = until
			}
		}
		if remaining <= 0 {
			break
		}
		// Reserve a usable slice for later candidates instead of letting
		// an unreachable IPv6 address consume the entire attempt.
		budget := remaining / time.Duration(len(candidates)-i)
		if budget > 3*time.Second {
			budget = 3 * time.Second
		}
		if budget <= 0 {
			break
		}
		dialCtx, stopDial := context.WithTimeout(ctx, budget)
		ws, err := dial(dialCtx, ip, domain, path, budget.Seconds())
		stopDial()
		if err == nil {
			if logInfo != nil {
				logInfo.Printf("MTProto Direct DNS/IPv6 WS route connected domain=%s via=%s", domain, ip)
			}
			return ws, nil
		}
		lastErr = err
		if logInfo != nil {
			logInfo.Printf("MTProto Direct DNS/IPv6 candidate failed domain=%s via=%s error=%v", domain, ip, err)
		}
		// Preserve the original TCP failure stage for health/backoff if
		// the route deadline expires during the final dial.
		if ctx.Err() != nil {
			var stageErr *wsStageError
			if errors.As(err, &stageErr) && stageErr.Stage == "tcp_dial" {
				return nil, err
			}
			return nil, ctx.Err()
		}
	}
	if lastErr != nil {
		return nil, lastErr
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return nil, &wsStageError{Stage: "tcp_dial", Err: fmt.Errorf("no Direct WS IP candidate was attempted")}
}

// A context-aware equivalent of the established Direct pinned-IP + DNS
// fallback. Never changes TLS configuration, SNI, WebSocket framing, or CF.
func defaultMtProtoDirectWSDialContext(
	ctx context.Context,
	targetIP, domain, path string,
	timeout float64,
) (mtProtoFrameSocket, error) {
	if knownIPv6 := directWSKnownIPv6(targetIP, domain); knownIPv6 != "" {
		return dialDirectWSDualStack(
			ctx, targetIP, knownIPv6, domain, path, timeout,
			resolvePreferredIPsContext,
			func(ctx context.Context, ip, domain, path string, timeout float64) (mtProtoFrameSocket, error) {
				return wsConnectWithCurvesContext(ctx, ip, domain, path, timeout, nil)
			},
		)
	}
	ws, err := wsConnectWithCurvesContext(ctx, targetIP, domain, path, timeout, nil)
	if err == nil {
		return ws, nil
	}
	if ctx.Err() != nil {
		// Preserve the TCP stage in the originating DialContext error. Losing
		// it would bypass cooldown precisely when a deadline expires.
		var stageErr *wsStageError
		if errors.As(err, &stageErr) && stageErr.Stage == "tcp_dial" {
			return nil, err
		}
		return nil, ctx.Err()
	}
	if !shouldTryDomainDial(err) {
		return nil, err
	}
	resolved, resolveErr := resolvePreferredIPsContext(ctx, domain, timeout)
	if resolveErr != nil {
		return nil, &wsStageError{Stage: "tcp_dial", Err: resolveErr}
	}
	candidates := resolved.Preferred()
	if len(candidates) == 0 {
		return nil, &wsStageError{
			Stage: "tcp_dial",
			Err: fmt.Errorf("no resolved addresses for Direct WS domain"),
		}
	}
	lastErr := err
	for _, ip := range candidates {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		// The pinned address has already been tried.
		if ip == targetIP {
			continue
		}
		ws, lastErr = wsConnectWithCurvesContext(ctx, ip, domain, path, timeout, nil)
		if lastErr == nil {
			return ws, nil
		}
		var wsErr *WsHandshakeError
		if errors.As(lastErr, &wsErr) && wsErr.IsRedirect() {
			break
		}
	}
	return nil, lastErr
}


func resetDirectWSHealthForTests() {
	directWSHealth.Lock()
	directWSHealth.entries = make(map[directWSHealthKey]directWSHealthEntry)
	directWSHealth.Unlock()
}
