package main

import (
    "context"
    "errors"
    "net"
    "net/netip"
    "testing"
)

type consumerWarpTestResolver struct {
    addresses []netip.Addr
    err       error
}

func (r consumerWarpTestResolver) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
    return r.addresses, r.err
}

func TestConsumerWarpBootstrapRequestAllowlistRejectsArbitraryDestinationShapes(t *testing.T) {
    rejected := [][2]string{
        {"GET", "/v0a4005/reg"},
        {"POST", "/"},
        {"POST", "/v0a4005/reg?x=1"},
        {"PATCH", "/v0a4005/reg/"},
        {"PATCH", "/v0a4005/reg/id/extra"},
        {"DELETE", "/v0a4005/reg/id"},
    }
    for _, item := range rejected {
        if err := validateConsumerWarpBootstrapRequest(item[0], item[1]); err == nil {
            t.Fatalf("unexpectedly allowed %s %s", item[0], item[1])
        }
    }

    if err := validateConsumerWarpBootstrapRequest("POST", consumerWarpRegisterPath); err != nil {
        t.Fatalf("registration path rejected: %v", err)
    }
    if err := validateConsumerWarpBootstrapRequest("PATCH", consumerWarpRegisterPath+"/abc-123"); err != nil {
        t.Fatalf("activation path rejected: %v", err)
    }
}

func TestConsumerWarpDialRejectsNonCloudflareHostWithoutCallingAWG(t *testing.T) {
    calls := 0
    _, err := consumerWarpDialContext(
        context.Background(),
        "tcp",
        "example.com:443",
        consumerWarpTestResolver{addresses: []netip.Addr{netip.MustParseAddr("203.0.113.10")}},
        func(context.Context, string, string) (net.Conn, error) {
            calls++
            return nil, errors.New("unexpected")
        },
    )
    if err == nil {
        t.Fatal("arbitrary host unexpectedly allowed")
    }
    if calls != 0 {
        t.Fatalf("AWG dial called %d times for rejected host", calls)
    }
}

func TestConsumerWarpDialUsesOnlyAWGTransportAndDoesNotFallbackDirect(t *testing.T) {
    calls := 0
    _, err := consumerWarpDialContext(
        context.Background(),
        "tcp",
        consumerWarpAPIHost+":443",
        consumerWarpTestResolver{addresses: []netip.Addr{netip.MustParseAddr("203.0.113.10")}},
        func(_ context.Context, network, address string) (net.Conn, error) {
            calls++
            if network != "tcp" {
                t.Fatalf("unexpected network %q", network)
            }
            if address != "203.0.113.10:443" {
                t.Fatalf("unexpected AWG target %q", address)
            }
            return nil, errors.New("awg blocked")
        },
    )
    if err == nil {
        t.Fatal("failed AWG dial unexpectedly succeeded")
    }
    if calls != 1 {
        t.Fatalf("AWG dial calls = %d, want 1", calls)
    }
}

func TestConsumerWarpDialPrefersIPv4(t *testing.T) {
    calls := make([]string, 0, 2)
    client, server := net.Pipe()
    defer server.Close()

    conn, err := consumerWarpDialContext(
        context.Background(),
        "tcp",
        consumerWarpAPIHost+":443",
        consumerWarpTestResolver{addresses: []netip.Addr{
            netip.MustParseAddr("2001:db8::10"),
            netip.MustParseAddr("203.0.113.10"),
        }},
        func(_ context.Context, _ string, address string) (net.Conn, error) {
            calls = append(calls, address)
            if address == "203.0.113.10:443" {
                return client, nil
            }
            return nil, errors.New("unexpected order")
        },
    )
    if err != nil {
        t.Fatalf("allowed API dial failed: %v", err)
    }
    _ = conn.Close()
    if len(calls) != 1 || calls[0] != "203.0.113.10:443" {
        t.Fatalf("dial order = %v", calls)
    }
}
