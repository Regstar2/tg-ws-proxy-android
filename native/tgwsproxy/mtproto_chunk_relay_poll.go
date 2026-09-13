package main

import (
	"context"
	"errors"
	"net"
	"sync"
	"time"
)

const (
	mtProtoChunkRelayPollIdleWaitMS          = 12_000
	mtProtoChunkRelayPollDeepIdleWaitMS      = 20_000
	mtProtoChunkRelayPollIdleAfterEmpty      = 3
	mtProtoChunkRelayPollDeepIdleAfterEmpty  = 8
)

type chunkRelayDownPollState struct {
	mu sync.Mutex

	consecutiveEmpty int
	polls            int64
	emptyPolls       int64
	payloadPolls     int64
	requestedWaitMS  int64
	emptyWaitMS      int64
	retries          int64
	timeouts         int64
}

type chunkRelayDownPollSnapshot struct {
	polls           int64
	emptyPolls      int64
	payloadPolls    int64
	requestedWaitMS int64
	emptyWaitMS     int64
	retries         int64
	timeouts        int64
}

func chunkRelayDownPollWaitMS(consecutiveEmpty int) int {
	switch {
	case consecutiveEmpty >= mtProtoChunkRelayPollDeepIdleAfterEmpty:
		return mtProtoChunkRelayPollDeepIdleWaitMS
	case consecutiveEmpty >= mtProtoChunkRelayPollIdleAfterEmpty:
		return mtProtoChunkRelayPollIdleWaitMS
	default:
		return mtProtoChunkRelayPollWaitMS
	}
}

func chunkRelayDownRequestTimeout(waitMS int) time.Duration {
	if waitMS <= mtProtoChunkRelayPollWaitMS {
		return mtProtoChunkRelayDownTimeout
	}
	headroom := mtProtoChunkRelayDownTimeout - time.Duration(mtProtoChunkRelayPollWaitMS)*time.Millisecond
	return time.Duration(waitMS)*time.Millisecond + headroom
}

func (s *chunkRelayDownPollState) waitMS() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return chunkRelayDownPollWaitMS(s.consecutiveEmpty)
}

func (s *chunkRelayDownPollState) recordStarted(waitMS int) {
	s.mu.Lock()
	s.polls++
	s.requestedWaitMS += int64(waitMS)
	s.mu.Unlock()
}

func (s *chunkRelayDownPollState) recordEmpty(waitMS int) (oldWaitMS, newWaitMS int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	oldWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	s.emptyPolls++
	s.emptyWaitMS += int64(waitMS)
	s.consecutiveEmpty++
	newWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	return oldWaitMS, newWaitMS
}

func (s *chunkRelayDownPollState) recordPayload() (oldWaitMS, newWaitMS int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	oldWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	s.payloadPolls++
	s.consecutiveEmpty = 0
	newWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	return oldWaitMS, newWaitMS
}

func (s *chunkRelayDownPollState) resetAfterError() (oldWaitMS, newWaitMS int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	oldWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	s.consecutiveEmpty = 0
	newWaitMS = chunkRelayDownPollWaitMS(s.consecutiveEmpty)
	return oldWaitMS, newWaitMS
}

func (s *chunkRelayDownPollState) recordRetry() {
	s.mu.Lock()
	s.retries++
	s.mu.Unlock()
}

func (s *chunkRelayDownPollState) recordAttemptFailure(err error) {
	if !chunkRelayDownTimeoutError(err) {
		return
	}
	s.mu.Lock()
	s.timeouts++
	s.mu.Unlock()
}

func (s *chunkRelayDownPollState) snapshot() chunkRelayDownPollSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return chunkRelayDownPollSnapshot{
		polls:           s.polls,
		emptyPolls:      s.emptyPolls,
		payloadPolls:    s.payloadPolls,
		requestedWaitMS: s.requestedWaitMS,
		emptyWaitMS:     s.emptyWaitMS,
		retries:         s.retries,
		timeouts:        s.timeouts,
	}
}

func (s chunkRelayDownPollSnapshot) averageWaitMS() int64 {
	if s.polls == 0 {
		return 0
	}
	return s.requestedWaitMS / s.polls
}

func (s chunkRelayDownPollSnapshot) requestsPerMiB(downBytes int64) float64 {
	if downBytes <= 0 {
		return 0
	}
	requests := s.polls + s.retries
	return float64(requests) / (float64(downBytes) / float64(1024*1024))
}

func (s chunkRelayDownPollSnapshot) idleRequestsPerMinute() float64 {
	if s.emptyWaitMS <= 0 {
		return 0
	}
	return float64(s.emptyPolls) / (float64(s.emptyWaitMS) / 60_000.0)
}

func chunkRelayDownTimeoutError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var timeoutErr net.Error
	return errors.As(err, &timeoutErr) && timeoutErr.Timeout()
}

func (c *mtProtoChunkRelayConn) nextDownPollWaitMS() int {
	return c.downPoll.waitMS()
}

func (c *mtProtoChunkRelayConn) downRequestTimeout() time.Duration {
	return chunkRelayDownRequestTimeout(c.nextDownPollWaitMS())
}

func (c *mtProtoChunkRelayConn) recordDownPollStarted(waitMS int) {
	c.downPoll.recordStarted(waitMS)
}

func (c *mtProtoChunkRelayConn) recordDownPollEmpty(waitMS int) {
	oldWaitMS, newWaitMS := c.downPoll.recordEmpty(waitMS)
	c.logDownPollTransition(oldWaitMS, newWaitMS, "empty")
}

func (c *mtProtoChunkRelayConn) recordDownPollPayload() {
	oldWaitMS, newWaitMS := c.downPoll.recordPayload()
	c.logDownPollTransition(oldWaitMS, newWaitMS, "payload")
}

func (c *mtProtoChunkRelayConn) resetDownPollAfterError() {
	oldWaitMS, newWaitMS := c.downPoll.resetAfterError()
	c.logDownPollTransition(oldWaitMS, newWaitMS, "request_error")
}

func (c *mtProtoChunkRelayConn) recordDownRetry() {
	c.downPoll.recordRetry()
}

func (c *mtProtoChunkRelayConn) recordDownAttemptFailure(err error) {
	c.downPoll.recordAttemptFailure(err)
}

func (c *mtProtoChunkRelayConn) logDownPollTransition(oldWaitMS, newWaitMS int, reason string) {
	if oldWaitMS == newWaitMS || logInfo == nil {
		return
	}
	logInfo.Printf(
		"MTProto Worker chunk relay downstream poll profile session_id=%s poll_wait_ms=%d previous_poll_wait_ms=%d reason=%s",
		c.sessionID,
		newWaitMS,
		oldWaitMS,
		reason,
	)
}
