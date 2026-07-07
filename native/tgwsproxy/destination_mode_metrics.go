package main

import (
	"fmt"
	"strings"
	"sync"
)

type workerDestinationSessionStats struct {
	destinationMode       string
	originalParsedDst     string
	workerDst             string
	mappedDC              int
	isMedia               bool
	mediaFixApplied       bool
	sessionsTotal         int64
	sessionsBidirectional int64
	sessionsZeroDown      int64
	upBytesTotal          int64
	downBytesTotal        int64
	durationMsSum         int64
	closeReasons          map[string]int64
}

type workerDestinationMetrics struct {
	mu      sync.Mutex
	buckets map[string]*workerDestinationSessionStats
}

var workerDestMetrics workerDestinationMetrics

func init() {
	workerDestMetrics.buckets = make(map[string]*workerDestinationSessionStats)
}

type workerDestinationSessionNote struct {
	effectiveDestinationMode string
	originalParsedDst        string
	workerDst                string
	mappedDC                 int
	isMedia                  bool
	mediaFixApplied          bool
	upBytes                  int64
	downBytes                int64
	durationMs               int64
	closeReason              string
}

func noteWorkerDestinationSession(note workerDestinationSessionNote) {
	effectiveMode := strings.TrimSpace(note.effectiveDestinationMode)
	if effectiveMode == "" {
		effectiveMode = "unknown"
	}
	key := fmt.Sprintf("%s|%s|%s|%d|%t|%t",
		effectiveMode,
		strings.TrimSpace(note.originalParsedDst),
		strings.TrimSpace(note.workerDst),
		note.mappedDC,
		note.isMedia,
		note.mediaFixApplied,
	)

	workerDestMetrics.mu.Lock()
	defer workerDestMetrics.mu.Unlock()
	bucket := workerDestMetrics.buckets[key]
	if bucket == nil {
		bucket = &workerDestinationSessionStats{
			destinationMode:   effectiveMode,
			originalParsedDst: strings.TrimSpace(note.originalParsedDst),
			workerDst:         strings.TrimSpace(note.workerDst),
			mappedDC:          note.mappedDC,
			isMedia:           note.isMedia,
			mediaFixApplied:   note.mediaFixApplied,
			closeReasons:      make(map[string]int64),
		}
		workerDestMetrics.buckets[key] = bucket
	}
	bucket.sessionsTotal++
	bucket.upBytesTotal += note.upBytes
	bucket.downBytesTotal += note.downBytes
	if note.downBytes > 0 {
		bucket.sessionsBidirectional++
	} else if note.upBytes > 0 {
		bucket.sessionsZeroDown++
	}
	bucket.durationMsSum += note.durationMs
	reason := strings.TrimSpace(note.closeReason)
	if reason == "" {
		reason = "unknown"
	}
	bucket.closeReasons[reason]++
}

func exportDestinationModeMetrics() string {
	workerDestMetrics.mu.Lock()
	defer workerDestMetrics.mu.Unlock()
	if len(workerDestMetrics.buckets) == 0 {
		return ""
	}
	keys := make([]string, 0, len(workerDestMetrics.buckets))
	for key := range workerDestMetrics.buckets {
		keys = append(keys, key)
	}
	for i := 0; i < len(keys); i++ {
		for j := i + 1; j < len(keys); j++ {
			if keys[j] < keys[i] {
				keys[i], keys[j] = keys[j], keys[i]
			}
		}
	}
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		b := workerDestMetrics.buckets[key]
		avgMs := int64(0)
		if b.sessionsTotal > 0 {
			avgMs = b.durationMsSum / b.sessionsTotal
		}
		reasonParts := make([]string, 0, len(b.closeReasons))
		for reason, count := range b.closeReasons {
			reasonParts = append(reasonParts, fmt.Sprintf("%s=%d", escapeDestModeField(reason), count))
		}
		for i := 0; i < len(reasonParts); i++ {
			for j := i + 1; j < len(reasonParts); j++ {
				if reasonParts[j] < reasonParts[i] {
					reasonParts[i], reasonParts[j] = reasonParts[j], reasonParts[i]
				}
			}
		}
		mediaFixVal := 0
		if b.mediaFixApplied {
			mediaFixVal = 1
		}
		isMediaVal := 0
		if b.isMedia {
			isMediaVal = 1
		}
		parts = append(parts, fmt.Sprintf(
			"%s:destination_mode=%s:original_parsed_dst=%s:worker_dst=%s:mapped_dc=%d:is_media=%d:flowseal_media_fix_applied=%d:sessions_total=%d:sessions_zero_down=%d:sessions_bidirectional=%d:sessions_with_down_bytes=%d:zero_down_sessions=%d:up_bytes_total=%d:down_bytes_total=%d:avg_duration_ms=%d:media_fix_applied=%d:close_reason=%s",
			escapeDestModeField(b.destinationMode),
			escapeDestModeField(b.destinationMode),
			escapeDestModeField(b.originalParsedDst),
			escapeDestModeField(b.workerDst),
			b.mappedDC,
			isMediaVal,
			mediaFixVal,
			b.sessionsTotal,
			b.sessionsZeroDown,
			b.sessionsBidirectional,
			b.sessionsBidirectional,
			b.sessionsZeroDown,
			b.upBytesTotal,
			b.downBytesTotal,
			avgMs,
			mediaFixVal,
			strings.Join(reasonParts, ","),
		))
	}
	return strings.Join(parts, "|")
}

func resetDestinationModeMetrics() {
	workerDestMetrics.mu.Lock()
	defer workerDestMetrics.mu.Unlock()
	workerDestMetrics.buckets = make(map[string]*workerDestinationSessionStats)
}

func escapeDestModeField(s string) string {
	return strings.NewReplacer(":", "_", "|", "_", "=", "_", ";", "_").Replace(s)
}

// noteDestinationModeSession is kept for legacy tests.
func noteDestinationModeSession(mode string, downBytes, durationMs int64, closeReason string, mediaFixApplied bool) {
	var upBytes int64
	if downBytes == 0 {
		upBytes = 1
	}
	noteWorkerDestinationSession(workerDestinationSessionNote{
		effectiveDestinationMode: mode,
		upBytes:                  upBytes,
		downBytes:                downBytes,
		durationMs:               durationMs,
		closeReason:              closeReason,
		mediaFixApplied:          mediaFixApplied,
	})
}
