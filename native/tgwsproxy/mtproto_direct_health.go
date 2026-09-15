package main

import (
	"errors"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	directTCPFailureTimeout            = "timeout"
	directTCPFailureNetworkUnreachable = "network_unreachable"
	directTCPFailureConnectionRefused  = "connection_refused"

	directTCPTimeoutCooldownBase            = 15 * time.Second
	directTCPNetworkUnreachableCooldownBase = 30 * time.Second
	directTCPConnectionRefusedCooldownBase  = 5 * time.Second
	directTCPMaxCooldown                     = 5 * time.Minute
)

type directTCPHealthKey struct {
	DCID   int
	Target string
	Port   int
}

type directTCPHealthState struct {
	Reason              string
	ConsecutiveFailures int
	CooldownUntil       time.Time
}

var directTCPHealthRuntime = struct {
	sync.Mutex
	states map[directTCPHealthKey]directTCPHealthState
}{states: make(map[directTCPHealthKey]directTCPHealthState)}

func directTCPHealthKeyFor(dc int, target string, port int) directTCPHealthKey {
	return directTCPHealthKey{
		DCID:   dc,
		Target: strings.TrimSpace(target),
		Port:   port,
	}
}

func activeDirectTCPCooldown(dc int, target string, port int, now time.Time) (directTCPHealthState, bool) {
	key := directTCPHealthKeyFor(dc, target, port)
	if key.Target == "" || key.Port < 1 {
		return directTCPHealthState{}, false
	}

	directTCPHealthRuntime.Lock()
	state, ok := directTCPHealthRuntime.states[key]
	directTCPHealthRuntime.Unlock()
	if !ok || state.CooldownUntil.IsZero() || !now.Before(state.CooldownUntil) {
		return state, false
	}
	return state, true
}

func classifyDirectTCPFailure(err error) (string, bool) {
	if err == nil {
		return "", false
	}

	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return directTCPFailureTimeout, true
	}

	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "connection refused"),
		strings.Contains(message, "actively refused"):
		return directTCPFailureConnectionRefused, true
	case strings.Contains(message, "network is unreachable"),
		strings.Contains(message, "network unreachable"),
		strings.Contains(message, "no route to host"),
		strings.Contains(message, "host is unreachable"),
		strings.Contains(message, "host unreachable"):
		return directTCPFailureNetworkUnreachable, true
	default:
		return "", false
	}
}

func markDirectTCPFailure(dc int, target string, port int, reason string, now time.Time) directTCPHealthState {
	key := directTCPHealthKeyFor(dc, target, port)
	if key.Target == "" || key.Port < 1 {
		return directTCPHealthState{}
	}

	directTCPHealthRuntime.Lock()
	previous := directTCPHealthRuntime.states[key]
	failures := previous.ConsecutiveFailures + 1
	state := directTCPHealthState{
		Reason:              reason,
		ConsecutiveFailures: failures,
		CooldownUntil:       now.Add(directTCPBackoff(reason, failures)),
	}
	directTCPHealthRuntime.states[key] = state
	directTCPHealthRuntime.Unlock()
	return state
}

func markDirectTCPSuccess(dc int, target string, port int) {
	key := directTCPHealthKeyFor(dc, target, port)
	if key.Target == "" || key.Port < 1 {
		return
	}

	directTCPHealthRuntime.Lock()
	delete(directTCPHealthRuntime.states, key)
	directTCPHealthRuntime.Unlock()
}

func directTCPBackoff(reason string, failures int) time.Duration {
	base := directTCPTimeoutCooldownBase
	switch reason {
	case directTCPFailureNetworkUnreachable:
		base = directTCPNetworkUnreachableCooldownBase
	case directTCPFailureConnectionRefused:
		base = directTCPConnectionRefusedCooldownBase
	}

	if failures < 1 {
		failures = 1
	}
	delay := base
	for attempt := 1; attempt < failures && delay < directTCPMaxCooldown; attempt++ {
		if delay >= directTCPMaxCooldown/2 {
			return directTCPMaxCooldown
		}
		delay *= 2
	}
	if delay > directTCPMaxCooldown {
		return directTCPMaxCooldown
	}
	return delay
}

func resetDirectTCPHealthForTests() {
	directTCPHealthRuntime.Lock()
	directTCPHealthRuntime.states = make(map[directTCPHealthKey]directTCPHealthState)
	directTCPHealthRuntime.Unlock()
}
