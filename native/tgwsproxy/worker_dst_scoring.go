package main

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"tg-ws-proxy/tgwsroute"
)

const (
	heavyZeroDownUpBytesThreshold = 65536
	workerDstPenaltyDuration      = 5 * time.Minute
	workerRouteCooldownMinUpBytes = 16 * 1024
	workerRouteCooldownDuration   = 2 * time.Minute
	workerMediaSuspectMinUpBytes  = 16 * 1024
	workerMediaSuspectMaxUpBytes  = 256 * 1024
	workerMediaSuspectMinMs       = 10_000
	workerMediaSuspectDuration    = 2 * time.Minute
	workerMediaSuspectUses        = 6
)

type workerDstScoreBucket struct {
	sessionsTotal         int64
	bidirectionalSessions int64
	zeroDownSessions      int64
	upBytesZeroDownTotal  int64
	lastZeroDownAt        time.Time
	lastSuccessAt         time.Time
	penaltyUntil          time.Time
}

type workerDstScoringStore struct {
	mu      sync.Mutex
	buckets map[string]*workerDstScoreBucket
	now     func() time.Time
}

type workerMediaSuspectBucket struct {
	expiresAt time.Time
	remaining int
	reason    string
}

type workerMediaSuspectStore struct {
	mu      sync.Mutex
	buckets map[int]*workerMediaSuspectBucket
	now     func() time.Time
}

type workerRouteCooldownStore struct {
	mu       sync.Mutex
	cooldown map[string]time.Time
	now      func() time.Time
}

var workerDstScores workerDstScoringStore
var workerMediaSuspects workerMediaSuspectStore
var workerRouteCooldowns workerRouteCooldownStore

func init() {
	workerDstScores.buckets = make(map[string]*workerDstScoreBucket)
	workerDstScores.now = time.Now
	workerMediaSuspects.buckets = make(map[int]*workerMediaSuspectBucket)
	workerMediaSuspects.now = time.Now
	workerRouteCooldowns.cooldown = make(map[string]time.Time)
	workerRouteCooldowns.now = time.Now
}

func workerDstScoreKey(dc int, workerDst, destinationMode string) string {
	return fmt.Sprintf("%d|%s|%s", dc, strings.TrimSpace(workerDst), strings.TrimSpace(destinationMode))
}

func resetWorkerDstScoring() {
	workerDstScores.mu.Lock()
	defer workerDstScores.mu.Unlock()
	workerDstScores.buckets = make(map[string]*workerDstScoreBucket)
}

func resetWorkerMediaSuspects() {
	workerMediaSuspects.mu.Lock()
	defer workerMediaSuspects.mu.Unlock()
	workerMediaSuspects.buckets = make(map[int]*workerMediaSuspectBucket)
}

func resetWorkerRouteCooldowns() {
	workerRouteCooldowns.mu.Lock()
	defer workerRouteCooldowns.mu.Unlock()
	workerRouteCooldowns.cooldown = make(map[string]time.Time)
}

func workerDstScoreBucketForKey(key string) *workerDstScoreBucket {
	bucket := workerDstScores.buckets[key]
	if bucket == nil {
		bucket = &workerDstScoreBucket{}
		workerDstScores.buckets[key] = bucket
	}
	return bucket
}

func dc2BaselineScore(workerDst string, manualOverride bool) float64 {
	switch strings.TrimSpace(workerDst) {
	case "149.154.167.41":
		return 100
	case tgwsroute.DC2DefaultIPv4Candidate:
		return 90
	case tgwsroute.DC2DemotedIPv4Candidate:
		if manualOverride {
			return 85
		}
		return 30
	default:
		return 50
	}
}

func workerDstCandidateScore(dc int, workerDst, destinationMode string, manualOverride bool) float64 {
	key := workerDstScoreKey(dc, workerDst, destinationMode)
	now := workerDstScores.now()

	workerDstScores.mu.Lock()
	bucket := workerDstScoreBucketForKey(key)
	penaltyUntil := bucket.penaltyUntil
	sessionsTotal := bucket.sessionsTotal
	bidirectional := bucket.bidirectionalSessions
	zeroDown := bucket.zeroDownSessions
	workerDstScores.mu.Unlock()

	if !penaltyUntil.IsZero() && now.Before(penaltyUntil) {
		return -1_000_000
	}

	baseline := float64(50)
	if dc == 2 {
		baseline = dc2BaselineScore(workerDst, manualOverride)
	}

	if sessionsTotal == 0 {
		return baseline
	}

	total := float64(sessionsTotal)
	score := baseline +
		(float64(bidirectional)/total)*50 -
		(float64(zeroDown)/total)*100
	return score
}

func isWorkerDstPenalized(dc int, workerDst, destinationMode string) bool {
	return workerDstCandidateScore(dc, workerDst, destinationMode, tgwsroute.HasDC2ManualOverride(snapshotDcOptMap())) <= -999_999
}

func workerDstHasZeroDown(dc int, workerDst, destinationMode string) bool {
	key := workerDstScoreKey(dc, workerDst, destinationMode)
	workerDstScores.mu.Lock()
	defer workerDstScores.mu.Unlock()
	bucket := workerDstScores.buckets[key]
	return bucket != nil && bucket.zeroDownSessions > 0
}

type dc2WorkerDstSelectInput struct {
	DC                int
	DestinationMode   string
	ParsedDstHost     string
	InitialWorkerDst  string
	DstFamily         string
	PreserveOriginal  bool
	WorkerDstSource   string
	ManualDC2Override bool
	ManualDC2IP       string
}

func selectDC2WorkerDst(input dc2WorkerDstSelectInput) (selected string, reason string) {
	if input.DC != 2 {
		return strings.TrimSpace(input.InitialWorkerDst), "not_dc2"
	}

	initial := strings.TrimSpace(input.InitialWorkerDst)
	mode := strings.TrimSpace(input.DestinationMode)
	manualOverride := input.ManualDC2Override
	manualIP := strings.TrimSpace(input.ManualDC2IP)

	if input.PreserveOriginal &&
		input.DstFamily == tgwsroute.DstFamilyIPv4 &&
		input.WorkerDstSource == tgwsroute.WorkerDstSourceParsedHost {
		if tgwsroute.IsDC2WorkerCandidate(initial) && !manualOverride && workerDstHasZeroDown(2, initial, mode) {
			selected = pickBestDC2Candidate(mode, manualOverride, initial)
			logDC2CandidateSelection(selected, mode, manualOverride, "preserve_original_zero_down_rescore")
			return selected, "preserve_original_zero_down_rescore"
		}
		logDC2CandidateSelection(initial, mode, manualOverride, "preserve_original_dst")
		return initial, "preserve_original_dst"
	}

	if manualOverride && manualIP != "" && initial == manualIP {
		if !isWorkerDstPenalized(2, initial, mode) {
			logDC2CandidateSelection(initial, mode, manualOverride, "manual_dc2_override")
			return initial, "manual_dc2_override"
		}
		reason = "manual_dc2_override_penalized_rescore"
	} else if input.PreserveOriginal && tgwsroute.IsDC2WorkerCandidate(initial) {
		if !isWorkerDstPenalized(2, initial, mode) {
			logDC2CandidateSelection(initial, mode, manualOverride, "preserve_original_unpenalized")
			return initial, "preserve_original_unpenalized"
		}
		reason = "preserve_original_penalized_rescore"
	} else if input.WorkerDstSource == tgwsroute.WorkerDstSourceIPv6ToDCIPv4 {
		reason = "ipv6_to_dc_ipv4_rescore"
	} else if input.WorkerDstSource == tgwsroute.WorkerDstSourceFlowsealDCMap && !manualOverride {
		reason = "flowseal_dc_map_rescore"
	} else if tgwsroute.IsDC2WorkerCandidate(initial) {
		reason = "dc2_candidate_rescore"
	} else {
		return initial, "non_dc2_candidate"
	}

	selected = pickBestDC2Candidate(mode, manualOverride, "")
	logDC2CandidateSelection(selected, mode, manualOverride, reason)
	return selected, reason
}

func pickBestDC2Candidate(destinationMode string, manualOverride bool, exclude string) string {
	exclude = strings.TrimSpace(exclude)
	bestIP := tgwsroute.DC2DefaultIPv4Candidate
	bestScore := -1e9
	for _, candidate := range tgwsroute.DC2WorkerCandidates {
		if candidate == exclude {
			continue
		}
		score := workerDstCandidateScore(2, candidate, destinationMode, manualOverride)
		logDC2CandidateScore(candidate, destinationMode, score, manualOverride)
		if score > bestScore {
			bestScore = score
			bestIP = candidate
		}
	}
	return bestIP
}

func selectAlternativeDC2Candidate(fromDst, destinationMode string, manualOverride bool) string {
	return pickBestDC2Candidate(destinationMode, manualOverride, strings.TrimSpace(fromDst))
}

func logDC2CandidateScore(workerDst, destinationMode string, score float64, manualOverride bool) {
	penaltyActive := score <= -999_999
	logInfo.Printf("dc_candidate_score dc=2 worker_dst=%s destination_mode=%s score=%.2f manual_dc2_override=%t dc_candidate_penalty=%t",
		workerDst, destinationMode, score, manualOverride, penaltyActive)
}

func logDC2CandidateSelection(selected, destinationMode string, manualOverride bool, reason string) {
	score := workerDstCandidateScore(2, selected, destinationMode, manualOverride)
	penaltyActive := isWorkerDstPenalized(2, selected, destinationMode)
	logInfo.Printf("dc_candidate_selected dc=2 worker_dst=%s destination_mode=%s score=%.2f reason=%s manual_dc2_override=%t dc_candidate_penalty=%t",
		selected, destinationMode, score, reason, manualOverride, penaltyActive)
}

func logIPv6DC2CandidateSelection(originalIPv6, selected string, destinationMode string) {
	logInfo.Printf("original_ipv6_dst=%s mapped_dc=2 selected_dc2_candidate=%s previous_default=%s destination_mode=%s",
		originalIPv6, selected, tgwsroute.DC2DefaultIPv4Candidate, destinationMode)
}

type workerDstSessionOutcome struct {
	DC              int
	WorkerDst       string
	DestinationMode string
	SessionID       string
	Route           routeKind
	IsMedia         bool
	UpBytes         int64
	DownBytes       int64
	DurationMs      int64
}

func noteWorkerDstSessionOutcome(outcome workerDstSessionOutcome) {
	if outcome.Route != routeCFWorkerWS {
		return
	}
	if outcome.UpBytes <= 0 {
		return
	}

	workerDst := strings.TrimSpace(outcome.WorkerDst)
	mode := strings.TrimSpace(outcome.DestinationMode)
	if workerDst == "" || mode == "" {
		return
	}

	isZeroDown := outcome.DownBytes == 0
	isHeavyZeroDown := isZeroDown && outcome.UpBytes >= heavyZeroDownUpBytesThreshold
	now := workerDstScores.now()
	key := workerDstScoreKey(outcome.DC, workerDst, mode)

	workerDstScores.mu.Lock()
	bucket := workerDstScoreBucketForKey(key)
	bucket.sessionsTotal++
	if isZeroDown {
		bucket.zeroDownSessions++
		bucket.upBytesZeroDownTotal += outcome.UpBytes
		bucket.lastZeroDownAt = now
	} else {
		bucket.bidirectionalSessions++
		bucket.lastSuccessAt = now
	}
	applyHardPenalty := false
	if isHeavyZeroDown {
		applyHardPenalty = bucket.zeroDownSessions >= 3 && bucket.bidirectionalSessions == 0
	}
	if applyHardPenalty {
		bucket.penaltyUntil = now.Add(workerDstPenaltyDuration)
	}
	workerDstScores.mu.Unlock()

	if isZeroDown {
		logInfo.Printf("zero_down_detected dc=%d worker_dst=%s destination_mode=%s session_id=%s ws_up_bytes=%d ws_down_bytes=%d",
			outcome.DC, workerDst, mode, outcome.SessionID, outcome.UpBytes, outcome.DownBytes)
		mediaSuspectMarked := markWorkerMediaSuspectIfNeeded(outcome, workerDst, mode)
		if !mediaSuspectMarked {
			markWorkerRouteCooldownIfNeeded(outcome, workerDst, mode)
		}
	}
	if isHeavyZeroDown {
		logWarn.Printf("heavy_zero_down_detected dc=%d worker_dst=%s destination_mode=%s session_id=%s ws_up_bytes=%d ws_down_bytes=%d hard_penalty=%t",
			outcome.DC, workerDst, mode, outcome.SessionID, outcome.UpBytes, outcome.DownBytes, applyHardPenalty)
		if applyHardPenalty {
			logWarn.Printf("worker_dst_penalized=true reason=repeated_heavy_zero_down dc=%d worker_dst=%s session_id=%s penalty_until=%s",
				outcome.DC, workerDst, outcome.SessionID, now.Add(workerDstPenaltyDuration).Format(time.RFC3339))
		}
	}
}

func workerRouteCooldownKey(dc int, isMedia bool) string {
	media := "core"
	if isMedia {
		media = "media"
	}
	return fmt.Sprintf("%d|%s", dc, media)
}

func markWorkerRouteCooldownIfNeeded(outcome workerDstSessionOutcome, workerDst, mode string) {
	if outcome.Route != routeCFWorkerWS {
		return
	}
	if outcome.DownBytes != 0 || outcome.UpBytes < workerRouteCooldownMinUpBytes {
		return
	}
	now := workerRouteCooldowns.now()
	until := now.Add(workerRouteCooldownDuration)
	if outcome.DC <= 0 {
		return
	}
	key := workerRouteCooldownKey(outcome.DC, outcome.IsMedia)

	workerRouteCooldowns.mu.Lock()
	workerRouteCooldowns.cooldown[key] = until
	workerRouteCooldowns.mu.Unlock()

	if logWarn != nil {
		logWarn.Printf("worker_route_cooldown_marked=true dc=%d media=%t reason=zero_down worker_dst=%s destination_mode=%s session_id=%s ws_up_bytes=%d duration_ms=%d until=%s",
			outcome.DC, outcome.IsMedia, workerDst, mode, outcome.SessionID, outcome.UpBytes, outcome.DurationMs, until.Format(time.RFC3339))
	}
}

func workerRouteCooldownActive(dc int, isMedia bool) (bool, time.Time) {
	if dc <= 0 {
		return false, time.Time{}
	}
	key := workerRouteCooldownKey(dc, isMedia)
	now := workerRouteCooldowns.now()

	workerRouteCooldowns.mu.Lock()
	defer workerRouteCooldowns.mu.Unlock()
	until := workerRouteCooldowns.cooldown[key]
	if until.IsZero() {
		return false, time.Time{}
	}
	if !now.Before(until) {
		delete(workerRouteCooldowns.cooldown, key)
		return false, time.Time{}
	}
	return true, until
}

func filterWorkerRouteCooldown(routes []routeKind, dc int, isMedia bool) []routeKind {
	active, until := workerRouteCooldownActive(dc, isMedia)
	if !active || len(routes) == 0 {
		return routes
	}

	filtered := make([]routeKind, 0, len(routes))
	removed := false
	for _, route := range routes {
		if route == routeCFWorkerWS {
			removed = true
			continue
		}
		filtered = append(filtered, route)
	}
	if !removed || len(filtered) == 0 {
		return routes
	}

	if logInfo != nil {
		logInfo.Printf("worker_route_cooldown_active=true dc=%d media=%t until=%s before=%s after=%s",
			dc, isMedia, until.Format(time.RFC3339), strings.Join(routeKindStrings(routes), "|"), strings.Join(routeKindStrings(filtered), "|"))
	}
	return filtered
}

func markWorkerMediaSuspectIfNeeded(outcome workerDstSessionOutcome, workerDst, mode string) bool {
	if outcome.DC != 2 {
		return false
	}
	if outcome.DownBytes != 0 {
		return false
	}
	if outcome.UpBytes < workerMediaSuspectMinUpBytes || outcome.UpBytes > workerMediaSuspectMaxUpBytes {
		return false
	}
	if outcome.DurationMs < workerMediaSuspectMinMs {
		return false
	}
	if mode == tgwsroute.WorkerDestinationExperimentalForceMediaDC4 {
		return false
	}

	now := workerMediaSuspects.now()
	expiresAt := now.Add(workerMediaSuspectDuration)
	reason := "voice_like_zero_down"

	workerMediaSuspects.mu.Lock()
	bucket := workerMediaSuspects.buckets[outcome.DC]
	if bucket == nil {
		bucket = &workerMediaSuspectBucket{}
		workerMediaSuspects.buckets[outcome.DC] = bucket
	}
	bucket.expiresAt = expiresAt
	bucket.remaining = workerMediaSuspectUses
	bucket.reason = reason
	workerMediaSuspects.mu.Unlock()

	logWarn.Printf("worker_media_suspect_marked=true dc=%d reason=%s worker_dst=%s destination_mode=%s session_id=%s ws_up_bytes=%d duration_ms=%d expires_at=%s uses=%d",
		outcome.DC, reason, workerDst, mode, outcome.SessionID, outcome.UpBytes, outcome.DurationMs, expiresAt.Format(time.RFC3339), workerMediaSuspectUses)
	return true
}

func consumeWorkerMediaSuspect(dc int) (bool, string) {
	if dc <= 0 {
		return false, ""
	}
	now := workerMediaSuspects.now()
	workerMediaSuspects.mu.Lock()
	defer workerMediaSuspects.mu.Unlock()

	bucket := workerMediaSuspects.buckets[dc]
	if bucket == nil {
		return false, ""
	}
	if bucket.remaining <= 0 || (!bucket.expiresAt.IsZero() && now.After(bucket.expiresAt)) {
		delete(workerMediaSuspects.buckets, dc)
		return false, ""
	}
	bucket.remaining--
	reason := bucket.reason
	if reason == "" {
		reason = "recent_zero_down"
	}
	if bucket.remaining <= 0 {
		delete(workerMediaSuspects.buckets, dc)
	}
	return true, reason
}
