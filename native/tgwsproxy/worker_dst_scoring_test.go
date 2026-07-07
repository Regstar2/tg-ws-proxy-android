package main

import (
	"reflect"
	"testing"
	"time"

	"tg-ws-proxy/tgwsroute"
)

func init() {
	initLogging(false)
}

func TestWorkerDstScoreKey(t *testing.T) {
	got := workerDstScoreKey(2, "149.154.167.41", "PRESERVE_ORIGINAL_DST")
	want := "2|149.154.167.41|PRESERVE_ORIGINAL_DST"
	if got != want {
		t.Fatalf("got=%q want=%q", got, want)
	}
}

func TestDC2BaselineScoreDemotesDefaultCandidate(t *testing.T) {
	if got := dc2BaselineScore(tgwsroute.DC2DemotedIPv4Candidate, false); got >= dc2BaselineScore("149.154.167.41", false) {
		t.Fatalf("expected demoted baseline < .41 baseline, got=%v", got)
	}
	if got := dc2BaselineScore(tgwsroute.DC2DemotedIPv4Candidate, true); got <= dc2BaselineScore(tgwsroute.DC2DemotedIPv4Candidate, false) {
		t.Fatalf("manual override should raise demoted baseline, got=%v", got)
	}
}

func TestWorkerDstSingleHeavyZeroDownDoesNotApplyPenalty(t *testing.T) {
	resetWorkerDstScoring()
	fixed := time.Date(2026, 7, 5, 12, 0, 0, 0, time.UTC)
	workerDstScores.now = func() time.Time { return fixed }

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       tgwsroute.DC2DemotedIPv4Candidate,
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "abc12345",
		Route:           routeCFWorkerWS,
		UpBytes:         524288,
		DownBytes:       0,
	})

	if isWorkerDstPenalized(2, tgwsroute.DC2DemotedIPv4Candidate, tgwsroute.WorkerDestinationPreserveOriginalDst) {
		t.Fatal("single heavy zero down upload should not apply hard penalty")
	}
}

func TestWorkerDstRepeatedHeavyZeroDownAppliesPenalty(t *testing.T) {
	resetWorkerDstScoring()
	fixed := time.Date(2026, 7, 5, 12, 0, 0, 0, time.UTC)
	workerDstScores.now = func() time.Time { return fixed }

	for i := 0; i < 3; i++ {
		noteWorkerDstSessionOutcome(workerDstSessionOutcome{
			DC:              2,
			WorkerDst:       tgwsroute.DC2DemotedIPv4Candidate,
			DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
			SessionID:       "abc12345",
			Route:           routeCFWorkerWS,
			UpBytes:         524288,
			DownBytes:       0,
		})
	}

	if !isWorkerDstPenalized(2, tgwsroute.DC2DemotedIPv4Candidate, tgwsroute.WorkerDestinationPreserveOriginalDst) {
		t.Fatal("expected repeated heavy zero down penalty")
	}
}

func TestSelectDC2WorkerDstRescoresOriginalIPv4AfterZeroDown(t *testing.T) {
	resetWorkerDstScoring()
	workerDstScores.now = time.Now

	selected, reason := selectDC2WorkerDst(dc2WorkerDstSelectInput{
		DC:                2,
		DestinationMode:   tgwsroute.WorkerDestinationPreserveOriginalDst,
		ParsedDstHost:     tgwsroute.DC2DemotedIPv4Candidate,
		InitialWorkerDst:  tgwsroute.DC2DemotedIPv4Candidate,
		DstFamily:         tgwsroute.DstFamilyIPv4,
		PreserveOriginal:  true,
		WorkerDstSource:   tgwsroute.WorkerDstSourceParsedHost,
		ManualDC2Override: false,
	})
	if selected != tgwsroute.DC2DemotedIPv4Candidate || reason != "preserve_original_dst" {
		t.Fatalf("selected=%q reason=%q", selected, reason)
	}

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       tgwsroute.DC2DemotedIPv4Candidate,
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "deadbeef",
		Route:           routeCFWorkerWS,
		UpBytes:         70000,
		DownBytes:       0,
	})

	selected, reason = selectDC2WorkerDst(dc2WorkerDstSelectInput{
		DC:                2,
		DestinationMode:   tgwsroute.WorkerDestinationPreserveOriginalDst,
		ParsedDstHost:     tgwsroute.DC2DemotedIPv4Candidate,
		InitialWorkerDst:  tgwsroute.DC2DemotedIPv4Candidate,
		DstFamily:         tgwsroute.DstFamilyIPv4,
		PreserveOriginal:  true,
		WorkerDstSource:   tgwsroute.WorkerDstSourceParsedHost,
		ManualDC2Override: false,
	})
	if selected == tgwsroute.DC2DemotedIPv4Candidate || reason != "preserve_original_zero_down_rescore" {
		t.Fatalf("expected alternative dst after zero-down, selected=%q reason=%q", selected, reason)
	}
}

func TestSelectAlternativeDC2CandidateExcludesCurrent(t *testing.T) {
	resetWorkerDstScoring()
	alt := selectAlternativeDC2Candidate("149.154.167.41", tgwsroute.WorkerDestinationPreserveOriginalDst, false)
	if alt == "149.154.167.41" {
		t.Fatalf("alternative must exclude current dst, got=%q", alt)
	}
}

func TestWorkerMediaSuspectMarkedForVoiceLikeZeroDown(t *testing.T) {
	resetWorkerDstScoring()
	resetWorkerMediaSuspects()
	resetWorkerRouteCooldowns()
	fixed := time.Date(2026, 7, 6, 9, 42, 51, 0, time.UTC)
	workerMediaSuspects.now = func() time.Time { return fixed }
	workerRouteCooldowns.now = func() time.Time { return fixed }
	defer func() { workerMediaSuspects.now = time.Now }()
	defer func() { workerRouteCooldowns.now = time.Now }()

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       "149.154.167.41",
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "voice1",
		Route:           routeCFWorkerWS,
		UpBytes:         50 * 1024,
		DownBytes:       0,
		DurationMs:      25_000,
	})

	ok, reason := consumeWorkerMediaSuspect(2)
	if !ok || reason != "voice_like_zero_down" {
		t.Fatalf("consume media suspect ok=%t reason=%q", ok, reason)
	}

	routes := filterWorkerRouteCooldown(
		[]routeKind{routeCFWorkerWS, routeCFProxyWS, routeTCPFallback},
		2,
		false,
	)
	want := []routeKind{routeCFWorkerWS, routeCFProxyWS, routeTCPFallback}
	if !reflect.DeepEqual(routes, want) {
		t.Fatalf("voice-like zero-down should keep worker for media-fix retry, routes=%v want %v", routes, want)
	}
}

func TestWorkerRouteCooldownSkipsWorkerWhenFallbackRoutesExist(t *testing.T) {
	resetWorkerRouteCooldowns()
	fixed := time.Date(2026, 7, 7, 9, 42, 0, 0, time.UTC)
	workerRouteCooldowns.now = func() time.Time { return fixed }
	defer func() { workerRouteCooldowns.now = time.Now }()

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       "149.154.167.41",
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "cooldown1",
		Route:           routeCFWorkerWS,
		UpBytes:         36 * 1024,
		DownBytes:       0,
		DurationMs:      8_000,
	})

	routes := filterWorkerRouteCooldown(
		[]routeKind{routeCFWorkerWS, routeCFProxyWS, routeTCPFallback},
		2,
		false,
	)
	want := []routeKind{routeCFProxyWS, routeTCPFallback}
	if !reflect.DeepEqual(routes, want) {
		t.Fatalf("routes=%v want %v", routes, want)
	}
}

func TestWorkerRouteCooldownKeepsStrictWorkerOnlyRoute(t *testing.T) {
	resetWorkerRouteCooldowns()
	fixed := time.Date(2026, 7, 7, 9, 42, 0, 0, time.UTC)
	workerRouteCooldowns.now = func() time.Time { return fixed }
	defer func() { workerRouteCooldowns.now = time.Now }()

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       "149.154.167.41",
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "cooldown2",
		Route:           routeCFWorkerWS,
		UpBytes:         36 * 1024,
		DownBytes:       0,
		DurationMs:      8_000,
	})

	routes := filterWorkerRouteCooldown([]routeKind{routeCFWorkerWS}, 2, false)
	want := []routeKind{routeCFWorkerWS}
	if !reflect.DeepEqual(routes, want) {
		t.Fatalf("routes=%v want %v", routes, want)
	}
}

func TestWorkerMediaSuspectIgnoresLargeImageLikeZeroDown(t *testing.T) {
	resetWorkerDstScoring()
	resetWorkerMediaSuspects()
	fixed := time.Date(2026, 7, 6, 9, 42, 51, 0, time.UTC)
	workerMediaSuspects.now = func() time.Time { return fixed }
	defer func() { workerMediaSuspects.now = time.Now }()

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       "149.154.167.41",
		DestinationMode: tgwsroute.WorkerDestinationPreserveOriginalDst,
		SessionID:       "image1",
		Route:           routeCFWorkerWS,
		UpBytes:         512 * 1024,
		DownBytes:       0,
		DurationMs:      25_000,
	})

	if ok, reason := consumeWorkerMediaSuspect(2); ok {
		t.Fatalf("large zero-down upload should not mark media suspect, reason=%q", reason)
	}
}

func TestPickBestDC2CandidatePrefersBidirectionalHistory(t *testing.T) {
	resetWorkerDstScoring()
	workerDstScores.now = time.Now

	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       "149.154.167.41",
		DestinationMode: tgwsroute.WorkerDestinationIPv6ToDCIPv4,
		SessionID:       "1",
		Route:           routeCFWorkerWS,
		UpBytes:         1000,
		DownBytes:       500,
	})
	noteWorkerDstSessionOutcome(workerDstSessionOutcome{
		DC:              2,
		WorkerDst:       tgwsroute.DC2DefaultIPv4Candidate,
		DestinationMode: tgwsroute.WorkerDestinationIPv6ToDCIPv4,
		SessionID:       "2",
		Route:           routeCFWorkerWS,
		UpBytes:         70000,
		DownBytes:       0,
	})

	got := pickBestDC2Candidate(tgwsroute.WorkerDestinationIPv6ToDCIPv4, false, "")
	if got != "149.154.167.41" {
		t.Fatalf("got=%q want best historical candidate .41", got)
	}
}
