package main

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

type directTCPTimeoutTestError struct{}

func (directTCPTimeoutTestError) Error() string   { return "i/o timeout" }
func (directTCPTimeoutTestError) Timeout() bool   { return true }
func (directTCPTimeoutTestError) Temporary() bool { return true }

func TestMtProtoDirectConnectorCooldownsTimedOutTargetAndRecoversAfterExpiry(t *testing.T) {
	resetDirectTCPHealthForTests()
	t.Cleanup(resetDirectTCPHealthForTests)

	now := time.Unix(1_700_000_000, 0)
	relayInit := make([]byte, 64)
	dialCount := 0
	connector := &mtProtoDirectConnector{
		resolveTarget: func(int) (string, int, bool) {
			return "149.154.167.51", 443, true
		},
		now: func() time.Time {
			return now
		},
		dialContext: func(context.Context, string, string) (net.Conn, error) {
			dialCount++
			if dialCount == 1 {
				return nil, directTCPTimeoutTestError{}
			}
			if dialCount == 2 {
				outbound, remote := net.Pipe()
				go func() {
					defer remote.Close()
					_, _ = io.CopyN(io.Discard, remote, int64(len(relayInit)))
				}()
				return outbound, nil
			}
			return nil, errors.New("unexpected extra dial")
		},
	}
	request := mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportPaddedIntermediate,
		RelayInit: relayInit,
	}

	conn, result := connector.Connect(context.Background(), request)
	if conn != nil {
		t.Fatal("unexpected connection after timeout")
	}
	if result.Reason != "direct_tcp_connect_failed" {
		t.Fatalf("reason=%s", result.Reason)
	}
	if dialCount != 1 {
		t.Fatalf("dial count after timeout=%d", dialCount)
	}

	health, coolingDown := activeDirectTCPCooldown(2, "149.154.167.51", 443, now)
	if !coolingDown {
		t.Fatal("expected active cooldown after timeout")
	}
	if health.Reason != directTCPFailureTimeout || health.ConsecutiveFailures != 1 {
		t.Fatalf("health=%+v", health)
	}

	conn, result = connector.Connect(context.Background(), request)
	if conn != nil {
		t.Fatal("unexpected connection while target is cooling down")
	}
	if result.Reason != "direct_tcp_ip_cooldown" {
		t.Fatalf("cooldown reason=%s", result.Reason)
	}
	if dialCount != 1 {
		t.Fatalf("cooldown performed a new dial: count=%d", dialCount)
	}

	now = health.CooldownUntil.Add(time.Millisecond)
	conn, result = connector.Connect(context.Background(), request)
	if result.Err != nil {
		t.Fatalf("retry after cooldown: %v", result.Err)
	}
	if conn == nil {
		t.Fatal("missing connection after cooldown expiry")
	}
	_ = conn.Close()
	if result.Reason != "connected" || result.ActualBackend != mtProtoDirectBackend {
		t.Fatalf("route truth=%+v", result)
	}
	if dialCount != 2 {
		t.Fatalf("retry dial count=%d", dialCount)
	}

	afterSuccess := markDirectTCPFailure(2, "149.154.167.51", 443, directTCPFailureTimeout, now)
	if afterSuccess.ConsecutiveFailures != 1 {
		t.Fatalf("success did not reset progressive state: %+v", afterSuccess)
	}
}

func TestDirectTCPHealthKeySeparatesDCsTargetsAndPorts(t *testing.T) {
	resetDirectTCPHealthForTests()
	t.Cleanup(resetDirectTCPHealthForTests)

	now := time.Unix(1_700_000_000, 0)
	markDirectTCPFailure(2, "149.154.167.51", 443, directTCPFailureTimeout, now)

	if _, active := activeDirectTCPCooldown(2, "149.154.167.51", 443, now); !active {
		t.Fatal("expected original target to be cooling down")
	}
	if _, active := activeDirectTCPCooldown(1, "149.154.167.51", 443, now); active {
		t.Fatal("cooldown leaked into another DC")
	}
	if _, active := activeDirectTCPCooldown(2, "149.154.167.91", 443, now); active {
		t.Fatal("cooldown leaked into another target")
	}
	if _, active := activeDirectTCPCooldown(2, "149.154.167.51", 80, now); active {
		t.Fatal("cooldown leaked into another port")
	}
}

func TestDirectTCPBackoffIsReasonAwareProgressiveAndBounded(t *testing.T) {
	if got := directTCPBackoff(directTCPFailureConnectionRefused, 1); got != directTCPConnectionRefusedCooldownBase {
		t.Fatalf("connection refused base=%s", got)
	}
	if got := directTCPBackoff(directTCPFailureTimeout, 2); got != 2*directTCPTimeoutCooldownBase {
		t.Fatalf("second timeout cooldown=%s", got)
	}
	if got := directTCPBackoff(directTCPFailureNetworkUnreachable, 2); got != 2*directTCPNetworkUnreachableCooldownBase {
		t.Fatalf("second unreachable cooldown=%s", got)
	}
	if got := directTCPBackoff(directTCPFailureTimeout, 32); got != directTCPMaxCooldown {
		t.Fatalf("bounded cooldown=%s", got)
	}
}

func TestClassifyDirectTCPFailure(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		wantReason string
		wantHealth bool
	}{
		{name: "timeout", err: directTCPTimeoutTestError{}, wantReason: directTCPFailureTimeout, wantHealth: true},
		{name: "network unreachable", err: errors.New("dial tcp: connect: network is unreachable"), wantReason: directTCPFailureNetworkUnreachable, wantHealth: true},
		{name: "no route to host", err: errors.New("dial tcp: connect: no route to host"), wantReason: directTCPFailureNetworkUnreachable, wantHealth: true},
		{name: "connection refused", err: errors.New("dial tcp: connect: connection refused"), wantReason: directTCPFailureConnectionRefused, wantHealth: true},
		{name: "generic", err: errors.New("network unavailable"), wantReason: "", wantHealth: false},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			reason, shouldCooldown := classifyDirectTCPFailure(test.err)
			if reason != test.wantReason || shouldCooldown != test.wantHealth {
				t.Fatalf("reason=%q cooldown=%t", reason, shouldCooldown)
			}
		})
	}
}
