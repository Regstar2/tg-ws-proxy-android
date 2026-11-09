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

// A context-aware equivalent of the established Direct pinned-IP + DNS
// fallback. Never changes TLS configuration, SNI, WebSocket framing, or CF.
func defaultMtProtoDirectWSDialContext(
	ctx context.Context,
	targetIP, domain, path string,
	timeout float64,
) (mtProtoFrameSocket, error) {
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

// Compile-time type contract: the Direct dialer continues to return the same
// WebSocket frame socket as the original implementation.
var _ net.Conn = (*mtProtoWebSocketStream)(nil)

func resetDirectWSHealthForTests() {
	directWSHealth.Lock()
	directWSHealth.entries = make(map[directWSHealthKey]directWSHealthEntry)
	directWSHealth.Unlock()
}
