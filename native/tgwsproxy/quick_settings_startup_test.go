package main

import (
    "context"
    "net"
    "testing"
)

func TestRunProxyReportsBindFailureBeforeRunning(t *testing.T) {
    occupied, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil {
        t.Fatal(err)
    }
    defer occupied.Close()

    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()
    started := make(chan error, 1)
    port := occupied.Addr().(*net.TCPAddr).Port

    err = runProxy(ctx, "127.0.0.1", port, nil, started)
    if err == nil {
        t.Fatal("expected bind failure on occupied port")
    }
    select {
    case startErr := <-started:
        if startErr == nil {
            t.Fatal("failed bind must not report successful startup")
        }
    default:
        t.Fatal("startup result was not delivered")
    }
}
