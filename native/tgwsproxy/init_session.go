package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
)

// initSession holds the original MTProto obfuscated init packet and resolved DC metadata.
// DC resolution is separate from init mutation; route-specific preparation decides whether to patch.
type initSession struct {
	original        []byte
	rawDst          string
	parsedDst       string
	dc              int
	isMedia         bool
	dcOk            bool
	dcFromInitOk    bool
	mappedViaIP     bool
	workerSessionID string
}

func newInitSession(init []byte, dst string, port int, rawDst string) *initSession {
	s := &initSession{
		original:  append([]byte(nil), init...),
		rawDst:    rawDst,
		parsedDst: joinAddr(dst, port),
	}
	dc, isMedia, ok := dcFromInit(init)
	if ok {
		s.dc = dc
		s.isMedia = isMedia
		s.dcOk = true
		s.dcFromInitOk = true
		return s
	}
	if info, found := lookupTelegramDC(dst); found {
		s.dc = info.DC
		s.isMedia = info.Media
		s.dcOk = true
		s.mappedViaIP = true
	}
	return s
}

func routeMutatesInit(route routeKind) bool {
	return route != routeCFWorkerWS
}

func (s *initSession) shouldPatchForRoute(route routeKind) bool {
	if !s.dcOk || s.dcFromInitOk || !routeMutatesInit(route) {
		return false
	}
	dcOptMu.RLock()
	_, hasDC := dcOpt[s.dc]
	dcOptMu.RUnlock()
	settings := getRuntimeSettings()
	return hasDC || settings.CF.Only
}

func (s *initSession) prepareForRoute(route routeKind) ([]byte, *MsgSplitter) {
	payload := append([]byte(nil), s.original...)
	if !s.shouldPatchForRoute(route) {
		return payload, nil
	}
	signedDC := s.dc
	if s.isMedia {
		signedDC = -s.dc
	}
	patched := patchInitDC(payload, signedDC)
	logDebug.Printf("init patched for route=%s via ipToDC: dc=%d is_media=%t signed_dc=%d",
		route, s.dc, s.isMedia, signedDC)
	splitter, _ := newMsgSplitter(patched)
	return patched, splitter
}

// workerFirstPacket returns the unmodified init packet for cf_worker_ws.
func (s *initSession) workerFirstPacket() []byte {
	return append([]byte(nil), s.original...)
}

func (s *initSession) workerFirstPacketForDestination(dc int, isMedia bool, patch bool) []byte {
	payload := s.workerFirstPacket()
	if !patch || dc <= 0 {
		return payload
	}
	signedDC := dc
	if isMedia {
		signedDC = -dc
	}
	patched := patchInitDC(payload, signedDC)
	logDebug.Printf("worker init patched for explicit destination: dc=%d is_media=%t signed_dc=%d",
		dc, isMedia, signedDC)
	return patched
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func initPacketMutated(before, after []byte) bool {
	return !bytes.Equal(before, after)
}
