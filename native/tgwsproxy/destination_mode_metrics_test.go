package main

import "testing"

func TestDestinationModeMetricsExport(t *testing.T) {
	resetDestinationModeMetrics()
	noteWorkerDestinationSession(workerDestinationSessionNote{
		effectiveDestinationMode: "PRESERVE_ORIGINAL_DST",
		originalParsedDst:        "149.154.167.41",
		workerDst:                "149.154.167.41",
		mappedDC:                 2,
		upBytes:                  524288,
		downBytes:                0,
		durationMs:               90,
		closeReason:              "ws_read: EOF",
	})
	noteWorkerDestinationSession(workerDestinationSessionNote{
		effectiveDestinationMode: "PRESERVE_ORIGINAL_DST",
		originalParsedDst:        "149.154.167.41",
		workerDst:                "149.154.167.41",
		mappedDC:                 2,
		upBytes:                  1024,
		downBytes:                512,
		durationMs:               5000,
		closeReason:              "client_read: EOF",
	})
	noteWorkerDestinationSession(workerDestinationSessionNote{
		effectiveDestinationMode: "EXPERIMENTAL_FORCE_MEDIA_DC4",
		originalParsedDst:        "149.154.167.151",
		workerDst:                "149.154.167.220",
		mappedDC:                 4,
		isMedia:                  true,
		mediaFixApplied:          true,
		upBytes:                  64,
		downBytes:                0,
		durationMs:               100,
		closeReason:              "ws_read: EOF",
	})

	raw := exportDestinationModeMetrics()
	if raw == "" {
		t.Fatal("expected stats")
	}
	if !containsAll(raw,
		"PRESERVE_ORIGINAL_DST",
		"sessions_total=2",
		"sessions_zero_down=1",
		"sessions_bidirectional=1",
		"up_bytes_total=525312",
		"EXPERIMENTAL_FORCE_MEDIA_DC4",
		"flowseal_media_fix_applied=1",
	) {
		t.Fatalf("stats=%q", raw)
	}
}

func containsAll(s string, parts ...string) bool {
	for _, part := range parts {
		if !contains(s, part) {
			return false
		}
	}
	return true
}

func contains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
