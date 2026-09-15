package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type awgWarpDialer struct {
	device *device.Device
	stack  *netstack.Net
	config awgWarpConfig

	closeOnce sync.Once
	closed    atomic.Bool

	appBytesUp   atomic.Int64
	appBytesDown atomic.Int64
	lastTargetMu sync.RWMutex
	lastTarget   string
}

type awgWarpDiagnostics struct {
	Endpoint        string
	LastHandshakeAt time.Time
	TunnelTxBytes   uint64
	TunnelRxBytes   uint64
	AppBytesUp      int64
	AppBytesDown    int64
	LastInnerTarget string
}

func newAwgWarpDialerFromFile(path string) (*awgWarpDialer, error) {
	cfg, err := loadAwgWarpConfigFile(path)
	if err != nil {
		return nil, err
	}
	return newAwgWarpDialer(cfg)
}

func newAwgWarpDialer(cfg awgWarpConfig) (*awgWarpDialer, error) {
	if err := cfg.validate(); err != nil {
		return nil, err
	}

	tunDevice, stack, err := netstack.CreateNetTUN(cfg.addresses, nil, cfg.mtu)
	if err != nil {
		return nil, fmt.Errorf("create AWG/WARP userspace netstack: %w", err)
	}
	awgDevice := device.NewDevice(
		tunDevice,
		conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelSilent, ""),
	)
	if err := awgDevice.IpcSet(cfg.uapiConfig()); err != nil {
		awgDevice.Close()
		return nil, fmt.Errorf("configure AWG/WARP userspace device: %w", err)
	}
	if err := awgDevice.Up(); err != nil {
		awgDevice.Close()
		return nil, fmt.Errorf("start AWG/WARP userspace device: %w", err)
	}

	return &awgWarpDialer{
		device: awgDevice,
		stack:  stack,
		config: cfg,
	}, nil
}

func (d *awgWarpDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if d == nil || d.device == nil || d.stack == nil {
		return nil, errors.New("AWG/WARP dialer is not initialized")
	}
	if d.closed.Load() {
		return nil, errors.New("AWG/WARP dialer is closed")
	}
	if ctx == nil {
		return nil, errors.New("AWG/WARP DialContext requires a non-nil context")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	target, err := parseAwgWarpDialTarget(network, address)
	if err != nil {
		return nil, err
	}
	if !d.config.allowsTarget(target.Addr()) {
		return nil, fmt.Errorf("AWG/WARP target %s is outside configured AllowedIPs", target.Addr())
	}

	transportConn, err := d.stack.DialContextTCPAddrPort(ctx, target)
	if err != nil {
		return nil, fmt.Errorf("AWG/WARP connect to %s: %w", target, err)
	}
	if d.closed.Load() {
		_ = transportConn.Close()
		return nil, errors.New("AWG/WARP dialer closed while connecting")
	}

	d.lastTargetMu.Lock()
	d.lastTarget = target.String()
	d.lastTargetMu.Unlock()

	return &awgWarpCountedConn{
		Conn:      transportConn,
		bytesUp:   &d.appBytesUp,
		bytesDown: &d.appBytesDown,
	}, nil
}

func (d *awgWarpDialer) Diagnostics() (awgWarpDiagnostics, error) {
	if d == nil || d.device == nil {
		return awgWarpDiagnostics{}, errors.New("AWG/WARP dialer is not initialized")
	}
	if d.closed.Load() {
		return awgWarpDiagnostics{}, errors.New("AWG/WARP dialer is closed")
	}

	uapiState, err := d.device.IpcGet()
	if err != nil {
		return awgWarpDiagnostics{}, fmt.Errorf("read AWG/WARP diagnostics: %w", err)
	}
	diagnostics, err := parseAwgWarpDiagnostics(uapiState)
	if err != nil {
		return awgWarpDiagnostics{}, err
	}
	diagnostics.AppBytesUp = d.appBytesUp.Load()
	diagnostics.AppBytesDown = d.appBytesDown.Load()
	d.lastTargetMu.RLock()
	diagnostics.LastInnerTarget = d.lastTarget
	d.lastTargetMu.RUnlock()
	return diagnostics, nil
}

func (d *awgWarpDialer) Close() error {
	if d == nil {
		return nil
	}
	d.closeOnce.Do(func() {
		d.closed.Store(true)
		if d.device != nil {
			d.device.Close()
		}
	})
	return nil
}

func parseAwgWarpDialTarget(network, address string) (netip.AddrPort, error) {
	switch network {
	case "tcp", "tcp4", "tcp6":
	default:
		return netip.AddrPort{}, fmt.Errorf("AWG/WARP PoC supports TCP only, got %q", network)
	}

	target, err := netip.ParseAddrPort(strings.TrimSpace(address))
	if err != nil {
		return netip.AddrPort{}, errors.New("AWG/WARP PoC requires an IP-literal target with port")
	}
	if target.Port() == 0 {
		return netip.AddrPort{}, errors.New("AWG/WARP target port must be non-zero")
	}
	if network == "tcp4" && !target.Addr().Is4() {
		return netip.AddrPort{}, errors.New("tcp4 requires an IPv4 target")
	}
	if network == "tcp6" && !target.Addr().Is6() {
		return netip.AddrPort{}, errors.New("tcp6 requires an IPv6 target")
	}
	return target, nil
}

func parseAwgWarpDiagnostics(uapiState string) (awgWarpDiagnostics, error) {
	var diagnostics awgWarpDiagnostics
	var handshakeSeconds int64
	var handshakeNanos int64

	scanner := bufio.NewScanner(strings.NewReader(uapiState))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch key {
		case "endpoint":
			diagnostics.Endpoint = value
		case "last_handshake_time_sec":
			parsed, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return awgWarpDiagnostics{}, fmt.Errorf("parse AWG/WARP handshake seconds: %w", err)
			}
			handshakeSeconds = parsed
		case "last_handshake_time_nsec":
			parsed, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return awgWarpDiagnostics{}, fmt.Errorf("parse AWG/WARP handshake nanoseconds: %w", err)
			}
			handshakeNanos = parsed
		case "tx_bytes":
			parsed, err := strconv.ParseUint(value, 10, 64)
			if err != nil {
				return awgWarpDiagnostics{}, fmt.Errorf("parse AWG/WARP TX bytes: %w", err)
			}
			diagnostics.TunnelTxBytes = parsed
		case "rx_bytes":
			parsed, err := strconv.ParseUint(value, 10, 64)
			if err != nil {
				return awgWarpDiagnostics{}, fmt.Errorf("parse AWG/WARP RX bytes: %w", err)
			}
			diagnostics.TunnelRxBytes = parsed
		}
	}
	if err := scanner.Err(); err != nil {
		return awgWarpDiagnostics{}, fmt.Errorf("scan AWG/WARP diagnostics: %w", err)
	}
	if handshakeSeconds != 0 || handshakeNanos != 0 {
		diagnostics.LastHandshakeAt = time.Unix(handshakeSeconds, handshakeNanos)
	}
	return diagnostics, nil
}

type awgWarpCountedConn struct {
	net.Conn
	bytesUp   *atomic.Int64
	bytesDown *atomic.Int64
}

func (c *awgWarpCountedConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.bytesDown.Add(int64(n))
	}
	return n, err
}

func (c *awgWarpCountedConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		c.bytesUp.Add(int64(n))
	}
	return n, err
}
