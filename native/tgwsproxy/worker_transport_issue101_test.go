package main

import (
    "context"
    "errors"
    "testing"

    "tg-ws-proxy/mtproxyfrontend"
)

func TestWorkerTransportParsingAndCompatibility(t *testing.T) {
    cases := []struct {
        name string
        encoded string
        want workerTransport
    }{
        {"existing pool entry", "one:one.workers.dev", workerTransportChunkRelay},
        {"chunk relay", "one:one.workers.dev:chunk_relay", workerTransportChunkRelay},
        {"Flowseal", "one:one.workers.dev:legacy_websocket", workerTransportLegacyWebSocket},
    }
    for _, tc := range cases {
        t.Run(tc.name, func(t *testing.T) {
            got := parseWorkerFailoverCandidates(tc.encoded)
            if len(got) != 1 || got[0].Transport != tc.want {
                t.Fatalf("candidates=%+v want transport=%q", got, tc.want)
            }
        })
    }
    if got := parseWorkerFailoverCandidates("one:one.workers.dev:unknown_protocol"); len(got) != 0 {
        t.Fatalf("explicitly unknown protocol must never downgrade: %+v", got)
    }
    if normalizeWorkerTransport("") != workerTransportChunkRelay {
        t.Fatal("old single-worker settings must default to Chunk Relay")
    }
    if normalizeWorkerTransport("unsupported") == workerTransportChunkRelay {
        t.Fatal("unknown explicit single-worker transport silently downgraded")
    }
    strict := workerFailoverSettings{
        Enabled: true,
        CandidatesSpecified: true,
        Candidates: parseWorkerFailoverCandidates("id:worker.workers.dev:unsupported"),
    }
    if got := strict.effectiveCandidates("worker.workers.dev"); len(got) != 0 {
        t.Fatalf("invalid pool candidate silently replaced with fallback: %+v", got)
    }
}

func TestMtProtoWorkerConnectorUsesCandidateTransportAndSafeFailover(t *testing.T) {
    withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
        settings.Worker.Enabled = true
        settings.Worker.Domain = "first.workers.dev"
        settings.Worker.Transport = workerTransportChunkRelay
        settings.Worker.Failover = workerFailoverSettings{
            Enabled: true,
            MaxAttempts: 2,
            Candidates: []workerFailoverCandidate{
                {ID: "first", Domain: "first.workers.dev", Transport: workerTransportLegacyWebSocket},
                {ID: "second", Domain: "second.workers.dev", Transport: workerTransportChunkRelay},
            },
        }
        return settings
    })
    var got []workerTransport
    connector := &mtProtoWorkerConnector{
        flowsealParity: true,
        dialTransportContext: func(_ context.Context, protocol workerTransport, _, _, _ string) (mtProtoFrameSocket, error) {
            got = append(got, protocol)
            if protocol == workerTransportLegacyWebSocket {
                return nil, errors.New("WebSocket upstream unavailable")
            }
            return &fakeMtProtoFrameSocket{}, nil
        },
    }
    conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
        DCID: 2,
        Transport: mtproxyfrontend.TransportAbridged,
        RelayInit: buildTestInitWithSignedDC(t, 2),
    })
    if result.Err != nil {
        t.Fatalf("connect: %v", result.Err)
    }
    defer conn.Close()
    if len(got) != 2 || got[0] != workerTransportLegacyWebSocket || got[1] != workerTransportChunkRelay {
        t.Fatalf("attempt transports=%+v", got)
    }
    if result.ActualBackend != mtProtoWorkerBackend {
        t.Fatalf("wrong actual backend %+v", result)
    }
}

func TestUnsupportedTransportNeverReachesNetwork(t *testing.T) {
    _, err := dialWorkerTransportContext(context.Background(), workerTransport("unknown"), "any.workers.dev", "/apiws", "test")
    if err == nil {
        t.Fatal("unknown transport accepted")
    }
}
