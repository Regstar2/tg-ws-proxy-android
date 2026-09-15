package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
)

const routeAWGWarp routeKind = "awg_warp"

type awgWarpRoutePolicy struct {
	Enabled       bool
	Preferred     bool
	AllowFallback bool
	ConfigPath    string
}

type awgWarpRouteRuntime struct {
	mu     sync.RWMutex
	policy awgWarpRoutePolicy
	dialer *awgWarpDialer
}

var globalAWGWarpRouteRuntime awgWarpRouteRuntime

func (r *awgWarpRouteRuntime) Configure(
	configPath string,
	enabled bool,
	preferred bool,
	allowFallback bool,
) error {
	configPath = strings.TrimSpace(configPath)
	if !enabled {
		r.Reset()
		return nil
	}
	if configPath == "" {
		return errors.New("AWG/WARP route is enabled but config path is empty")
	}

	r.mu.RLock()
	unchanged := r.policy.Enabled &&
		r.policy.ConfigPath == configPath &&
		r.dialer != nil
	existing := r.dialer
	r.mu.RUnlock()

	if unchanged {
		r.mu.Lock()
		r.policy.Preferred = preferred
		r.policy.AllowFallback = allowFallback
		r.mu.Unlock()
		return nil
	}

	fresh, err := newAwgWarpDialerFromFile(configPath)
	if err != nil {
		return err
	}

	r.mu.Lock()
	old := r.dialer
	r.dialer = fresh
	r.policy = awgWarpRoutePolicy{
		Enabled:       true,
		Preferred:     preferred,
		AllowFallback: allowFallback,
		ConfigPath:    configPath,
	}
	r.mu.Unlock()

	if old != nil && old != existing {
		_ = old.Close()
	} else if existing != nil {
		_ = existing.Close()
	}
	return nil
}

func (r *awgWarpRouteRuntime) Reset() {
	r.mu.Lock()
	old := r.dialer
	r.dialer = nil
	r.policy = awgWarpRoutePolicy{}
	r.mu.Unlock()
	if old != nil {
		_ = old.Close()
	}
}

func (r *awgWarpRouteRuntime) Policy() awgWarpRoutePolicy {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.policy
}

func (r *awgWarpRouteRuntime) DialContext(
	ctx context.Context,
	network string,
	address string,
) (net.Conn, error) {
	r.mu.RLock()
	dialer := r.dialer
	enabled := r.policy.Enabled
	r.mu.RUnlock()
	if !enabled || dialer == nil {
		return nil, errors.New("AWG/WARP route is not configured")
	}
	return dialer.DialContext(ctx, network, address)
}

func (r *awgWarpRouteRuntime) Diagnostics() (awgWarpDiagnostics, error) {
	r.mu.RLock()
	dialer := r.dialer
	enabled := r.policy.Enabled
	r.mu.RUnlock()
	if !enabled || dialer == nil {
		return awgWarpDiagnostics{}, errors.New("AWG/WARP route is not configured")
	}
	return dialer.Diagnostics()
}

func withAWGWarpRoute(routes []routeKind) []routeKind {
	policy := globalAWGWarpRouteRuntime.Policy()
	if !policy.Enabled {
		return routes
	}

	withoutAWG := make([]routeKind, 0, len(routes))
	for _, route := range routes {
		if route != routeAWGWarp {
			withoutAWG = append(withoutAWG, route)
		}
	}

	if policy.Preferred {
		if !policy.AllowFallback {
			return []routeKind{routeAWGWarp}
		}
		return append([]routeKind{routeAWGWarp}, withoutAWG...)
	}
	if !policy.AllowFallback {
		return withoutAWG
	}

	result := make([]routeKind, 0, len(withoutAWG)+1)
	inserted := false
	for _, route := range withoutAWG {
		if !inserted && route == routeTCPFallback {
			result = append(result, routeAWGWarp)
			inserted = true
		}
		result = append(result, route)
	}
	if !inserted {
		result = append(result, routeAWGWarp)
	}
	return result
}
