package main

import (
	"context"
	"testing"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestMtProtoWorkerConnectorStopsAfterFirstSuccessfulCandidate(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.Worker.Enabled = true
		settings.Worker.Domain = "first.workers.dev"
		settings.MtProtoWorkerPreconnect = false
		settings.Worker.Failover = workerFailoverSettings{
			Enabled:     true,
			MaxAttempts: 2,
			Candidates: []workerFailoverCandidate{
				{ID: "first", Domain: "first.workers.dev"},
				{ID: "second", Domain: "second.workers.dev"},
			},
		}
		return settings
	})

	socket := &fakeMtProtoFrameSocket{}
	var attempts []string
	connector := &mtProtoWorkerConnector{
		dial: func(domain, _ string, _ string) (mtProtoFrameSocket, error) {
			attempts = append(attempts, domain)
			return socket, nil
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: buildTestInitWithSignedDC(t, 2),
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if len(attempts) != 1 || attempts[0] != "first.workers.dev" {
		t.Fatalf("attempts=%v, want only first successful candidate", attempts)
	}
	if result.ActualBackend != mtProtoWorkerBackend || result.Reason != "connected" {
		t.Fatalf("route truth=%+v", result)
	}
}
